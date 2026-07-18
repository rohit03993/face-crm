package com.school.faceverify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.databinding.ActivityEnrollBinding
import com.school.faceverify.face.ArcFaceEmbedder
import com.school.faceverify.face.EnrollPose
import com.school.faceverify.face.FacePipeline
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Enroll on the phone with guided auto-capture (front → left → right → up),
 * then upload only the small 512-d template to the API.
 */
class EnrollActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEnrollBinding
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val embeddings = mutableListOf<FloatArray>()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val capturingPaused = AtomicBoolean(false)
    private val captureBusy = AtomicBoolean(false)
    private var statusJob: Job? = null
    private var autoJob: Job? = null
    private var apiOnline: Boolean? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var embedder: ArcFaceEmbedder? = null
    private var pipeline: FacePipeline? = null
    private var editMode = false
    private var existingStudentId: String? = null
    private var poseHoldStartedAt = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        editMode = intent.getBooleanExtra(EXTRA_EDIT_MODE, false)
        existingStudentId = intent.getStringExtra(EXTRA_STUDENT_ID)
        val enrollment = intent.getStringExtra(EXTRA_ENROLLMENT)
        val name = intent.getStringExtra(EXTRA_NAME)
        if (!enrollment.isNullOrBlank()) binding.inputStudentId.setText(enrollment)
        if (!name.isNullOrBlank()) binding.inputStudentName.setText(name)
        if (editMode) {
            binding.enrollTitle.text = getString(R.string.update_face)
            binding.btnUpload.text = getString(R.string.update_face)
            binding.inputStudentId.isEnabled = false
        }

        binding.btnCapture.text = getString(R.string.capture_manual)
        updateUi()
        binding.btnCapture.isEnabled = false
        binding.hintText.text = getString(R.string.enroll_model_loading)

        binding.btnCapture.setOnClickListener { captureShot(manual = true) }
        binding.btnUpload.setOnClickListener { upload() }
        binding.btnCancel.setOnClickListener { finish() }

        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val emb = ArcFaceEmbedder(this@EnrollActivity)
                    emb to FacePipeline(emb)
                }
                embedder = loaded.first
                pipeline = loaded.second
                updateUi()
                startAutoCaptureLoop()
                if (ContextCompat.checkSelfPermission(
                        this@EnrollActivity,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    startCamera()
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            } catch (t: Throwable) {
                Log.e("EnrollActivity", "model load failed", t)
                Toast.makeText(this@EnrollActivity, R.string.model_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        statusJob?.cancel()
        statusJob = ConnectionStatus.startPolling(this) { online ->
            apiOnline = online
            ConnectionStatus.bind(binding.connectionDot, binding.connectionStatus, online, this)
            if (binding.savingOverlay.visibility != View.VISIBLE) updateUi()
        }
        if (pipeline != null) startAutoCaptureLoop()
    }

    override fun onPause() {
        statusJob?.cancel()
        statusJob = null
        autoJob?.cancel()
        autoJob = null
        super.onPause()
    }

    private fun currentPose(): EnrollPose? =
        EnrollPose.SEQUENCE.getOrNull(embeddings.size)

    private fun startAutoCaptureLoop() {
        if (autoJob?.isActive == true) return
        autoJob = lifecycleScope.launch {
            while (isActive) {
                if (capturingPaused.get() ||
                    captureBusy.get() ||
                    pipeline == null ||
                    embeddings.size >= TARGET_SHOTS ||
                    binding.savingOverlay.visibility == View.VISIBLE
                ) {
                    delay(200)
                    continue
                }
                val pose = currentPose() ?: break
                val frame = pendingFrame.get()
                if (frame == null) {
                    delay(80)
                    continue
                }
                val reading = withContext(Dispatchers.Default) {
                    pipeline?.detectPose(frame)
                }
                if (reading == null || !reading.hasLandmarks) {
                    poseHoldStartedAt = 0L
                    withContext(Dispatchers.Main) {
                        binding.hintText.text = getString(pose.hintRes)
                    }
                    delay(120)
                    continue
                }
                if (pose.matches(reading.yaw, reading.pitch)) {
                    val now = System.currentTimeMillis()
                    if (poseHoldStartedAt == 0L) poseHoldStartedAt = now
                    val held = now - poseHoldStartedAt
                    withContext(Dispatchers.Main) {
                        binding.hintText.text = if (held >= HOLD_MS / 2) {
                            getString(R.string.enroll_pose_hold)
                        } else {
                            getString(pose.hintRes)
                        }
                    }
                    if (held >= HOLD_MS) {
                        poseHoldStartedAt = 0L
                        captureShot(manual = false)
                        delay(COOLDOWN_MS)
                    } else {
                        delay(60)
                    }
                } else {
                    poseHoldStartedAt = 0L
                    withContext(Dispatchers.Main) {
                        binding.hintText.text = getString(pose.hintRes)
                    }
                    delay(80)
                }
            }
        }
    }

    private fun captureShot(manual: Boolean) {
        if (pipeline == null) {
            Toast.makeText(this, R.string.enroll_model_loading, Toast.LENGTH_SHORT).show()
            return
        }
        if (embeddings.size >= MAX_SHOTS) {
            Toast.makeText(this, "Max $MAX_SHOTS photos", Toast.LENGTH_SHORT).show()
            return
        }
        val frame = pendingFrame.get()
        if (frame == null) {
            Toast.makeText(this, "No frame yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (!captureBusy.compareAndSet(false, true)) return

        binding.btnCapture.isEnabled = false
        lifecycleScope.launch {
            try {
                val pose = currentPose()
                if (!manual && pose != null) {
                    val reading = withContext(Dispatchers.Default) {
                        pipeline?.detectPose(frame)
                    }
                    if (reading == null || !pose.matches(reading.yaw, reading.pitch)) {
                        return@launch
                    }
                }
                val result = withContext(Dispatchers.Default) {
                    pipeline?.embedWithPose(frame)
                }
                if (result == null) {
                    Toast.makeText(this@EnrollActivity, R.string.enroll_no_face, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val emb = result.embedding
                val duplicateMsg = withContext(Dispatchers.IO) {
                    try {
                        val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                        FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).checkDuplicateFace(
                            embedding = emb,
                            modelVersion = ArcFaceEmbedder.MODEL_VERSION,
                            excludeStudentId = existingStudentId,
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                if (duplicateMsg != null) {
                    AlertDialog.Builder(this@EnrollActivity)
                        .setTitle(R.string.duplicate_face_title)
                        .setMessage(duplicateMsg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    binding.hintText.text = getString(R.string.duplicate_face_hint)
                    return@launch
                }
                embeddings.add(emb)
                updateUi()
                val poseName = pose?.id ?: "shot"
                Toast.makeText(
                    this@EnrollActivity,
                    "Captured $poseName (${embeddings.size}/$TARGET_SHOTS)",
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("EnrollActivity", "capture failed", e)
                Toast.makeText(this@EnrollActivity, "Capture failed", Toast.LENGTH_SHORT).show()
            } finally {
                captureBusy.set(false)
                binding.btnCapture.isEnabled =
                    pipeline != null && binding.savingOverlay.visibility != View.VISIBLE
            }
        }
    }

    private fun updateUi() {
        val n = embeddings.size
        val modelReady = pipeline != null
        val total = EnrollPose.SEQUENCE.size
        val pose = currentPose()
        binding.captureCount.text = when {
            !modelReady -> getString(R.string.enroll_model_loading)
            n >= TARGET_SHOTS -> getString(R.string.enroll_pose_ready)
            pose != null -> getString(
                R.string.enroll_pose_progress,
                n + 1,
                total,
                pose.id,
            )
            else -> "$n captured"
        }
        binding.enrollProgress.max = TARGET_SHOTS
        binding.enrollProgress.progress = n.coerceAtMost(TARGET_SHOTS)
        binding.btnCapture.isEnabled = modelReady && binding.savingOverlay.visibility != View.VISIBLE
        binding.btnUpload.isEnabled =
            modelReady && n in MIN_SHOTS..MAX_SHOTS && apiOnline != false
        if (modelReady && binding.savingOverlay.visibility != View.VISIBLE) {
            binding.hintText.text = when {
                n >= TARGET_SHOTS && editMode -> getString(R.string.enroll_capture_hint_ready).replace(
                    "Save student",
                    "Update face",
                )
                n >= TARGET_SHOTS -> getString(R.string.enroll_pose_ready)
                pose != null -> getString(pose.hintRes)
                else -> getString(R.string.enroll_capture_hint_ready)
            }
        }
    }

    private fun setSaving(visible: Boolean, step: String? = null) {
        binding.savingOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (step != null) binding.savingStep.text = step
        capturingPaused.set(visible)
        if (visible) {
            autoJob?.cancel()
            autoJob = null
        } else {
            startAutoCaptureLoop()
        }
    }

    private fun upload() {
        val studentId = binding.inputStudentId.text?.toString()?.trim().orEmpty()
        val studentName = binding.inputStudentName.text?.toString()?.trim().orEmpty()
        if (studentId.isBlank()) {
            Toast.makeText(this, "Enter roll / student ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (apiOnline == false) {
            Toast.makeText(this, R.string.enroll_offline_block, Toast.LENGTH_LONG).show()
            return
        }
        if (embeddings.size < MIN_SHOTS) return

        binding.btnUpload.isEnabled = false
        binding.btnCapture.isEnabled = false
        binding.btnCancel.isEnabled = false
        setSaving(true, getString(R.string.enroll_saving_prepare))

        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val client = FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)

                val healthy = withContext(Dispatchers.IO) { client.healthQuick() }
                if (!healthy) {
                    apiOnline = false
                    ConnectionStatus.bind(
                        binding.connectionDot,
                        binding.connectionStatus,
                        false,
                        this@EnrollActivity,
                    )
                    throw IllegalStateException(getString(R.string.enroll_offline_block))
                }

                val template = withContext(Dispatchers.Default) {
                    ArcFaceEmbedder.averageEmbeddings(embeddings.toList())
                }

                binding.savingStep.text = getString(R.string.enroll_saving_upload)
                cameraProvider?.let { provider ->
                    provider.unbindAll()
                    val preview = Preview.Builder().build().also { p ->
                        p.surfaceProvider = binding.previewView.surfaceProvider
                    }
                    provider.bindToLifecycle(
                        this@EnrollActivity,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                    )
                }

                binding.savingStep.text = getString(R.string.enroll_saving_process)
                val enrollKey = existingStudentId?.takeIf { it.isNotBlank() } ?: studentId
                val (ok, body) = withContext(Dispatchers.IO) {
                    client.enrollTemplate(
                        studentId = enrollKey,
                        embedding = template,
                        modelVersion = ArcFaceEmbedder.MODEL_VERSION,
                        imageCount = embeddings.size,
                        name = studentName.ifBlank { null },
                        enrollmentNumber = studentId,
                    )
                }
                if (ok) {
                    val msg = if (editMode) getString(R.string.student_updated) else "Student saved"
                    Toast.makeText(this@EnrollActivity, msg, Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    binding.hintText.text = "Save failed"
                    if (body.contains("already belongs", ignoreCase = true)) {
                        AlertDialog.Builder(this@EnrollActivity)
                            .setTitle(R.string.duplicate_face_title)
                            .setMessage(body)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    } else {
                        Toast.makeText(this@EnrollActivity, body, Toast.LENGTH_LONG).show()
                    }
                    restoreAfterSaveFailure()
                }
            } catch (e: Exception) {
                Log.e("EnrollActivity", "enroll upload failed", e)
                binding.hintText.text = e.message ?: "Upload error"
                Toast.makeText(
                    this@EnrollActivity,
                    e.message ?: "Upload error",
                    Toast.LENGTH_LONG,
                ).show()
                restoreAfterSaveFailure()
            }
        }
    }

    private fun restoreAfterSaveFailure() {
        setSaving(false)
        binding.btnCancel.isEnabled = true
        updateUi()
        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (capturingPaused.get()) return@setAnalyzer
                    val bmp = imageProxyToBitmap(image)
                    if (bmp != null) pendingFrame.set(bmp)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
            var bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: return null
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val mirror = Matrix()
            mirror.preScale(-1f, 1f)
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mirror, true)
        } catch (e: Exception) {
            Log.w("EnrollActivity", "frame convert failed", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoJob?.cancel()
        cameraExecutor.shutdown()
        pipeline?.close()
        embedder?.close()
    }

    companion object {
        const val EXTRA_EDIT_MODE = "edit_mode"
        const val EXTRA_STUDENT_ID = "student_id"
        const val EXTRA_ENROLLMENT = "enrollment"
        const val EXTRA_NAME = "name"

        private const val MIN_SHOTS = 3
        private const val TARGET_SHOTS = 4
        private const val MAX_SHOTS = 6
        private const val HOLD_MS = 700L
        private const val COOLDOWN_MS = 900L
    }
}
