package com.school.faceverify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.school.faceverify.data.KioskConfig
import com.school.faceverify.databinding.ActivityAttendanceBinding
import com.school.faceverify.face.ArcFaceEmbedder
import com.school.faceverify.face.FacePipeline
import com.school.faceverify.face.PresenceGate
import com.school.faceverify.face.PresenceIssue
import com.school.faceverify.net.FaceApiClient
import com.school.faceverify.util.FeedbackPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AttendanceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAttendanceBinding
    private var embedder: ArcFaceEmbedder? = null
    private var pipeline: FacePipeline? = null
    private var apiClient: FaceApiClient? = null
    private var feedback: FeedbackPlayer? = null
    private var config: KioskConfig = KioskConfig()
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val identifyMutex = Mutex()
    private val identifying = AtomicBoolean(false)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var loopJob: Job? = null
    private var holdStartedAt = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root, extraTopDp = 8, extraBottomDp = 8)
        feedback = FeedbackPlayer(this)

        binding.btnStop.setOnClickListener { finish() }
        showIdle()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        lifecycleScope.launch {
            try {
                config = FaceVerifyApp.instance.settings.configFlow.first()
                apiClient = FaceApiClient(config.apiBaseUrl, config.deviceToken)
                val loaded = withContext(Dispatchers.IO) {
                    val emb = ArcFaceEmbedder(this@AttendanceActivity)
                    emb to FacePipeline(emb)
                }
                embedder = loaded.first
                pipeline = loaded.second
                showIdle()
                loopJob = lifecycleScope.launch { runLoop() }
            } catch (t: Throwable) {
                Log.e(TAG, "Attendance startup failed", t)
                binding.statusBanner.text = getString(R.string.model_failed)
                binding.statusHint.text = t.message ?: "Check model / API settings"
                setTone(Tone.FAIL)
            }
        }
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val pipe = pipeline
            val client = apiClient
            if (pipe == null || client == null || identifying.get()) {
                delay(100)
                continue
            }

            val frame = pendingFrame.getAndSet(null)
            if (frame == null) {
                delay(80)
                continue
            }

            val ovalInBitmap = ovalInBitmap(frame.width, frame.height)
            val reading = withContext(Dispatchers.Default) { pipe.detectPresence(frame) }
            val issue = PresenceGate.check(reading, frame.width, frame.height, ovalInBitmap)

            if (issue == PresenceIssue.NONE) {
                val now = System.currentTimeMillis()
                if (holdStartedAt == 0L) holdStartedAt = now
                val held = now - holdStartedAt
                val progress = (held.toFloat() / PresenceGate.HOLD_MS).coerceIn(0f, 1f)
                withContext(Dispatchers.Main) {
                    updatePresenceUi(issue, progress)
                }
                if (held >= PresenceGate.HOLD_MS) {
                    holdStartedAt = 0L
                    performIdentify(frame, pipe, client)
                }
            } else {
                holdStartedAt = 0L
                withContext(Dispatchers.Main) {
                    updatePresenceUi(issue, 0f)
                }
            }

            delay(60)
        }
    }

    private suspend fun performIdentify(
        frame: Bitmap,
        pipe: FacePipeline,
        client: FaceApiClient,
    ) {
        if (!identifyMutex.tryLock()) return
        identifying.set(true)
        try {
            withContext(Dispatchers.Main) {
                binding.faceGuide.ovalState = FaceOvalOverlay.OvalState.VERIFYING
                binding.faceGuide.holdProgress = 0f
                binding.faceGuide.applyAlpha()
                binding.statusBanner.text = getString(R.string.verifying)
                binding.statusHint.text = getString(R.string.identifying)
                setTone(Tone.VERIFY)
                setActiveStep(Step.HOLD, done = true)
            }

            val embedded = withContext(Dispatchers.Default) { pipe.embedFromBitmap(frame) }
            if (embedded == null) {
                withContext(Dispatchers.Main) { showIdle() }
                return
            }

            try {
                val result = withContext(Dispatchers.IO) {
                    client.identifyCameraFace(embedded.first, ArcFaceEmbedder.MODEL_VERSION)
                }
                withContext(Dispatchers.Main) { setOnline(true) }

                when {
                    result.matched && result.attendanceRecorded && !result.alreadyProcessed -> {
                        val student = result.name ?: result.enrollmentNumber ?: "Student"
                        withContext(Dispatchers.Main) {
                            binding.statusBanner.text = getString(R.string.attendance_recorded)
                            binding.statusHint.text =
                                "$student  ·  ${"%.2f".format(result.score ?: 0f)}"
                            binding.faceGuide.ovalState = FaceOvalOverlay.OvalState.SUCCESS
                            binding.faceGuide.applyAlpha()
                            setTone(Tone.PASS)
                            feedback?.playPass(student)
                        }
                        delay(900)
                    }
                    result.matched && result.alreadyProcessed -> delay(300)
                    result.matched -> {
                        withContext(Dispatchers.Main) {
                            binding.statusBanner.text = getString(R.string.fail)
                            binding.statusHint.text =
                                result.message ?: getString(R.string.attendance_not_recorded)
                            binding.faceGuide.ovalState = FaceOvalOverlay.OvalState.FAIL
                            binding.faceGuide.applyAlpha()
                            setTone(Tone.FAIL)
                        }
                        delay(800)
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            binding.statusBanner.text = getString(R.string.face_not_registered)
                            binding.statusHint.text = getString(R.string.face_not_registered_hint)
                            binding.faceGuide.ovalState = FaceOvalOverlay.OvalState.FAIL
                            binding.faceGuide.applyAlpha()
                            setTone(Tone.FAIL)
                        }
                        delay(1500)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "identify failed", t)
                withContext(Dispatchers.Main) {
                    setOnline(false)
                    binding.statusBanner.text = getString(R.string.camera_ready)
                    binding.statusHint.text = getString(R.string.api_retry)
                    binding.faceGuide.ovalState = FaceOvalOverlay.OvalState.FAIL
                    binding.faceGuide.applyAlpha()
                    setTone(Tone.FAIL)
                }
                delay(1000)
            }

            withContext(Dispatchers.Main) { showIdle() }
        } finally {
            identifying.set(false)
            identifyMutex.unlock()
        }
    }

    private fun ovalInBitmap(bitmapW: Int, bitmapH: Int): RectF? {
        val guide = binding.faceGuide
        if (guide.width == 0 || guide.height == 0) return null
        val preview = binding.previewView
        if (preview.width == 0 || preview.height == 0) return null

        val viewRect = RectF(
            guide.left.toFloat(),
            guide.top.toFloat(),
            guide.right.toFloat(),
            guide.bottom.toFloat(),
        )
        return FrameCoordinateMapper.mapViewRectToBitmap(
            viewRect,
            preview.width,
            preview.height,
            bitmapW,
            bitmapH,
        )
    }

    private fun updatePresenceUi(issue: PresenceIssue, holdProgress: Float) {
        val oval = binding.faceGuide
        when (issue) {
            PresenceIssue.NO_FACE -> {
                oval.ovalState = FaceOvalOverlay.OvalState.IDLE
                oval.holdProgress = 0f
                binding.statusBanner.text = getString(R.string.presence_no_face)
                binding.statusHint.text = getString(R.string.presence_no_face_hint)
                setTone(Tone.IDLE)
                setActiveStep(Step.POSITION)
            }
            PresenceIssue.OFF_CENTER, PresenceIssue.TOO_SMALL -> {
                oval.ovalState = FaceOvalOverlay.OvalState.DETECTED
                oval.holdProgress = 0f
                binding.statusBanner.text = if (issue == PresenceIssue.TOO_SMALL) {
                    getString(R.string.presence_too_small)
                } else {
                    getString(R.string.presence_off_center)
                }
                binding.statusHint.text = if (issue == PresenceIssue.TOO_SMALL) {
                    getString(R.string.presence_too_small_hint)
                } else {
                    getString(R.string.presence_off_center_hint)
                }
                setTone(Tone.IDLE)
                setActiveStep(Step.POSITION)
            }
            PresenceIssue.WRONG_POSE, PresenceIssue.EYES_CLOSED -> {
                oval.ovalState = FaceOvalOverlay.OvalState.ALIGN
                oval.holdProgress = 0f
                binding.statusBanner.text = if (issue == PresenceIssue.EYES_CLOSED) {
                    getString(R.string.presence_eyes_closed)
                } else {
                    getString(R.string.presence_wrong_pose)
                }
                binding.statusHint.text = if (issue == PresenceIssue.EYES_CLOSED) {
                    getString(R.string.presence_eyes_closed_hint)
                } else {
                    getString(R.string.presence_wrong_pose_hint)
                }
                setTone(Tone.IDLE)
                setActiveStep(Step.LOOK)
            }
            PresenceIssue.NONE -> {
                oval.ovalState = FaceOvalOverlay.OvalState.HOLDING
                oval.holdProgress = holdProgress
                binding.statusBanner.text = getString(R.string.presence_holding)
                binding.statusHint.text = getString(R.string.presence_holding_hint)
                setTone(Tone.VERIFY)
                setActiveStep(Step.HOLD)
            }
        }
        oval.applyAlpha()
    }

    private enum class Step { POSITION, LOOK, HOLD }

    private fun setActiveStep(step: Step, done: Boolean = false) {
        styleStep(binding.stepPosition, step == Step.POSITION, step.ordinal > Step.POSITION.ordinal)
        styleStep(binding.stepLook, step == Step.LOOK, step.ordinal > Step.LOOK.ordinal)
        styleStep(binding.stepHold, step == Step.HOLD, done)
    }

    private fun styleStep(view: TextView, active: Boolean, completed: Boolean) {
        val bg = when {
            active -> R.drawable.bg_step_chip_active
            completed -> R.drawable.bg_step_chip_active
            else -> R.drawable.bg_step_chip
        }
        view.setBackgroundResource(bg)
        val color = if (active || completed) R.color.white else R.color.mist_dim
        view.setTextColor(ContextCompat.getColor(this, color))
    }

    private enum class Tone { IDLE, VERIFY, PASS, FAIL }

    private fun setTone(tone: Tone) {
        val color = ContextCompat.getColor(
            this,
            when (tone) {
                Tone.IDLE -> R.color.panel
                Tone.VERIFY -> R.color.verify_soft
                Tone.PASS -> R.color.pass_soft
                Tone.FAIL -> R.color.fail_soft
            },
        )
        binding.statusPanel.background = GradientDrawable().apply {
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(color)
        }
    }

    private fun showIdle() {
        holdStartedAt = 0L
        binding.statusBanner.text = getString(R.string.camera_ready)
        binding.statusHint.text = getString(R.string.camera_ready_hint)
        binding.faceGuide.ovalState = FaceOvalOverlay.OvalState.IDLE
        binding.faceGuide.holdProgress = 0f
        binding.faceGuide.applyAlpha()
        setTone(Tone.IDLE)
        setActiveStep(Step.POSITION)
    }

    private fun setOnline(online: Boolean) {
        ConnectionStatus.bind(binding.connectionDot, binding.wsStatus, online, this)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
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
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
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
            Log.w(TAG, "frame convert failed", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        pipeline?.close()
        embedder?.close()
        feedback?.release()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "AttendanceActivity"
    }
}
