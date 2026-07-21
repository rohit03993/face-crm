package com.school.faceverify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import com.school.faceverify.face.FrameConverter
import com.school.faceverify.net.DeviceAuthResult
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Enroll flow: enter roll + name first, then guided face capture
 * (front → left → right → up), then upload the averaged template.
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
    private var faceStepStarted = false
    private var modelLoading = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
            ensureModelAndCapture()
        } else {
            Toast.makeText(this, "Camera required", Toast.LENGTH_LONG).show()
        }
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
            binding.captureTitle.text = getString(R.string.update_face)
            binding.btnUpload.text = getString(R.string.update_face)
            binding.inputStudentId.isEnabled = false
        }

        showDetailsStep()
        binding.btnCapture.text = getString(R.string.capture_manual)
        binding.btnCapture.isEnabled = false

        binding.btnContinueFace.setOnClickListener { continueToFaceCapture() }
        binding.btnCancelDetails.setOnClickListener { finish() }
        binding.btnCapture.setOnClickListener { captureShot(manual = true) }
        binding.btnUpload.setOnClickListener { upload() }
        binding.btnCancel.setOnClickListener { goBackFromCapture() }
    }

    override fun onResume() {
        super.onResume()
        statusJob?.cancel()
        statusJob = ConnectionStatus.startPolling(this) { state ->
            apiOnline = when (state) {
                ConnectionStatus.State.Checking -> null
                ConnectionStatus.State.Connected -> true
                else -> false
            }
            bindConnection(state)
            if (faceStepStarted && binding.savingOverlay.visibility != View.VISIBLE) updateUi()
        }
        if (faceStepStarted && pipeline != null) startAutoCaptureLoop()
    }

    override fun onPause() {
        statusJob?.cancel()
        statusJob = null
        autoJob?.cancel()
        autoJob = null
        super.onPause()
    }

    private fun bindConnection(state: ConnectionStatus.State) {
        ConnectionStatus.bind(binding.connectionDot, binding.connectionStatus, state, this)
        ConnectionStatus.bind(
            binding.connectionDotCapture,
            binding.connectionStatusCapture,
            state,
            this,
        )
    }

    private fun showDetailsStep() {
        faceStepStarted = false
        autoJob?.cancel()
        autoJob = null
        cameraProvider?.unbindAll()
        pendingFrame.set(null)
        binding.detailsStep.visibility = View.VISIBLE
        binding.captureStep.visibility = View.GONE
    }

    private fun continueToFaceCapture() {
        val studentId = binding.inputStudentId.text?.toString()?.trim().orEmpty()
        val studentName = binding.inputStudentName.text?.toString()?.trim().orEmpty()
        if (studentId.isBlank()) {
            Toast.makeText(this, R.string.enroll_enter_roll, Toast.LENGTH_SHORT).show()
            binding.inputStudentId.requestFocus()
            return
        }
        if (studentName.isBlank()) {
            Toast.makeText(this, R.string.enroll_enter_name, Toast.LENGTH_SHORT).show()
            binding.inputStudentName.requestFocus()
            return
        }

        faceStepStarted = true
        binding.detailsStep.visibility = View.GONE
        binding.captureStep.visibility = View.VISIBLE
        binding.studentSummary.text = getString(
            R.string.enroll_capturing_for,
            studentId,
            studentName,
        )
        binding.hintText.text = getString(R.string.enroll_model_loading)
        updateUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
            ensureModelAndCapture()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun goBackFromCapture() {
        if (embeddings.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.back)
                .setMessage("Discard captured faces and edit student details?")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    embeddings.clear()
                    showDetailsStep()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            showDetailsStep()
        }
    }

    private fun ensureModelAndCapture() {
        if (pipeline != null) {
            updateUi()
            startAutoCaptureLoop()
            return
        }
        if (modelLoading) return
        modelLoading = true
        binding.hintText.text = getString(R.string.enroll_model_loading)
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val emb = ArcFaceEmbedder(this@EnrollActivity)
                    emb to FacePipeline(emb)
                }
                embedder = loaded.first
                pipeline = loaded.second
                updateUi()
                if (faceStepStarted) startAutoCaptureLoop()
            } catch (t: Throwable) {
                Log.e("EnrollActivity", "model load failed", t)
                Toast.makeText(this@EnrollActivity, R.string.model_failed, Toast.LENGTH_LONG).show()
                showDetailsStep()
            } finally {
                modelLoading = false
            }
        }
    }

    private fun currentPose(): EnrollPose? =
        EnrollPose.SEQUENCE.getOrNull(embeddings.size)

    private fun startAutoCaptureLoop() {
        if (!faceStepStarted) return
        if (autoJob?.isActive == true) return
        autoJob = lifecycleScope.launch {
            while (isActive && faceStepStarted) {
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
                    pipeline?.detectPresence(frame)
                }
                if (reading == null || !reading.hasLandmarks) {
                    poseHoldStartedAt = 0L
                    withContext(Dispatchers.Main) {
                        binding.hintText.text = getString(pose.hintRes)
                    }
                    delay(120)
                    continue
                }
                val leftEye = reading.leftEyeOpen
                val rightEye = reading.rightEyeOpen
                if (leftEye != null && rightEye != null &&
                    (leftEye < MIN_EYE_OPEN || rightEye < MIN_EYE_OPEN)
                ) {
                    poseHoldStartedAt = 0L
                    withContext(Dispatchers.Main) {
                        binding.hintText.text = getString(R.string.enroll_eyes_closed)
                    }
                    delay(100)
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
        if (!faceStepStarted) return
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
                        pipeline?.detectPresence(frame)
                    }
                    if (reading == null || !pose.matches(reading.yaw, reading.pitch)) {
                        return@launch
                    }
                    val leftEye = reading.leftEyeOpen
                    val rightEye = reading.rightEyeOpen
                    if (leftEye != null && rightEye != null &&
                        (leftEye < MIN_EYE_OPEN || rightEye < MIN_EYE_OPEN)
                    ) {
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
                Toast.makeText(
                    this@EnrollActivity,
                    R.string.enroll_pose_ready,
                    Toast.LENGTH_SHORT,
                ).show()
                // Stop auto-loop once we have the single shot.
                autoJob?.cancel()
                autoJob = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("EnrollActivity", "capture failed", e)
                Toast.makeText(this@EnrollActivity, "Capture failed", Toast.LENGTH_SHORT).show()
            } finally {
                captureBusy.set(false)
                binding.btnCapture.isEnabled =
                    pipeline != null &&
                        embeddings.size < TARGET_SHOTS &&
                        binding.savingOverlay.visibility != View.VISIBLE
            }
        }
    }

    private fun updateUi() {
        if (!faceStepStarted) return
        val n = embeddings.size
        val modelReady = pipeline != null
        val pose = currentPose()
        binding.captureCount.text = when {
            !modelReady -> getString(R.string.enroll_model_loading)
            n >= TARGET_SHOTS -> getString(R.string.enroll_pose_ready)
            pose != null -> getString(R.string.enroll_pose_progress)
            else -> getString(R.string.enroll_one_photo_hint)
        }
        binding.enrollProgress.max = TARGET_SHOTS
        binding.enrollProgress.progress = n.coerceAtMost(TARGET_SHOTS)
        binding.btnCapture.isEnabled =
            modelReady && n < TARGET_SHOTS && binding.savingOverlay.visibility != View.VISIBLE
        binding.btnUpload.isEnabled =
            modelReady && n in MIN_SHOTS..MAX_SHOTS && apiOnline != false
        if (modelReady && binding.savingOverlay.visibility != View.VISIBLE) {
            binding.hintText.text = when {
                n >= TARGET_SHOTS && editMode -> getString(R.string.enroll_pose_ready).replace(
                    "Save student",
                    "Update face",
                )
                n >= TARGET_SHOTS -> getString(R.string.enroll_pose_ready)
                pose != null -> getString(pose.hintRes)
                else -> getString(R.string.enroll_one_photo_hint)
            }
        }
    }

    private fun setSaving(visible: Boolean, step: String? = null) {
        binding.savingOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        capturingPaused.set(visible)
        if (visible) {
            autoJob?.cancel()
            autoJob = null
        } else if (faceStepStarted) {
            startAutoCaptureLoop()
        }
        if (step != null) binding.savingStep.text = step
    }

    private fun upload() {
        val studentId = binding.inputStudentId.text?.toString()?.trim().orEmpty()
        val studentName = binding.inputStudentName.text?.toString()?.trim().orEmpty()
        if (studentId.isBlank()) {
            Toast.makeText(this, R.string.enroll_enter_roll, Toast.LENGTH_SHORT).show()
            return
        }
        if (studentName.isBlank()) {
            Toast.makeText(this, R.string.enroll_enter_name, Toast.LENGTH_SHORT).show()
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

                val auth = withContext(Dispatchers.IO) {
                    client.verifyDeviceCredentials(cfg.deviceId)
                }
                if (auth !is DeviceAuthResult.Ok) {
                    apiOnline = false
                    val state = when (auth) {
                        DeviceAuthResult.Offline -> ConnectionStatus.State.Offline
                        else -> ConnectionStatus.State.Unauthorized
                    }
                    bindConnection(state)
                    val msg = when (auth) {
                        is DeviceAuthResult.Failed -> auth.message
                        else -> getString(R.string.enroll_offline_block)
                    }
                    throw IllegalStateException(msg)
                }

                val template = withContext(Dispatchers.Default) {
                    // Single frontal capture — use it directly (still L2-normalized).
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
                        name = studentName,
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
        if (faceStepStarted) startCamera()
    }

    private fun startCamera() {
        if (!faceStepStarted) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!faceStepStarted) return@addListener
            val provider = providerFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (capturingPaused.get() || !faceStepStarted) return@setAnalyzer
                    val bmp = FrameConverter.toBitmap(image, mirrorFrontCamera = true)
                    if (bmp != null) pendingFrame.set(bmp)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
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

        private const val MIN_SHOTS = 1
        private const val TARGET_SHOTS = 1
        private const val MAX_SHOTS = 1
        private const val HOLD_MS = 900L
        private const val COOLDOWN_MS = 500L
        private const val MIN_EYE_OPEN = 0.40f
    }
}
