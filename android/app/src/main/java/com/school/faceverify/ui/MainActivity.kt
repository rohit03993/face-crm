package com.school.faceverify.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.data.KioskConfig
import com.school.faceverify.databinding.ActivityMainBinding
import com.school.faceverify.face.ArcFaceEmbedder
import com.school.faceverify.face.FacePipeline
import com.school.faceverify.face.FrameConverter
import com.school.faceverify.net.FaceApiClient
import com.school.faceverify.net.JsonLite
import com.school.faceverify.net.KioskWebSocket
import com.school.faceverify.net.OfflineResultQueue
import com.school.faceverify.net.VerificationRequestMsg
import com.school.faceverify.util.AttendanceFormat
import com.school.faceverify.util.FeedbackPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var embedder: ArcFaceEmbedder? = null
    private var pipeline: FacePipeline? = null
    private var ws: KioskWebSocket? = null
    private var apiClient: FaceApiClient? = null
    private var feedback: FeedbackPlayer? = null
    private var config: KioskConfig = KioskConfig()
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val verifyMutex = Mutex()
    private lateinit var offlineQueue: OfflineResultQueue
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraAttendanceJob: Job? = null
    private var verificationJob: Job? = null
    private var modeConfigJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root, extraTopDp = 8, extraBottomDp = 8)
        offlineQueue = OfflineResultQueue(this)
        feedback = FeedbackPlayer(this)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnEnroll.setOnClickListener {
            startActivity(Intent(this, EnrollActivity::class.java))
        }

        binding.statusBanner.text = getString(R.string.loading_model)
        binding.statusHint.text = "Preparing face recognition…"
        setStatusTone(StatusTone.IDLE)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Load heavy ONNX model off the main thread (prevents white-screen crash/OOM)
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val emb = ArcFaceEmbedder(this@MainActivity)
                    emb to FacePipeline(emb)
                }
                embedder = loaded.first
                pipeline = loaded.second
                showIdleStatus()
                Toast.makeText(this@MainActivity, getString(R.string.model_ready), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "ONNX model failed to load", t)
                binding.statusBanner.text = getString(R.string.model_failed)
                binding.statusHint.text = t.message ?: "Check model asset"
                setStatusTone(StatusTone.FAIL)
                Toast.makeText(
                    this@MainActivity,
                    "Face model failed: ${t.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        lifecycleScope.launch {
            FaceVerifyApp.instance.settings.configFlow.collectLatest { cfg ->
                val previous = config
                config = cfg
                modeConfigJob?.cancel()
                modeConfigJob = lifecycleScope.launch {
                    configureMode(previous)
                    flushOffline()
                }
            }
        }
    }

    private suspend fun configureMode(previous: KioskConfig? = null) {
        cameraAttendanceJob?.cancelAndJoin()
        cameraAttendanceJob = null
        verificationJob?.cancelAndJoin()
        verificationJob = null

        val connectionChanged = previous == null
            || previous.apiBaseUrl != config.apiBaseUrl
            || previous.deviceId != config.deviceId
            || previous.deviceToken != config.deviceToken
        val modeChanged = previous == null || previous.cameraAttendanceMode != config.cameraAttendanceMode

        if (connectionChanged) {
            apiClient = FaceApiClient(config.apiBaseUrl, config.deviceToken)
        }

        if (config.cameraAttendanceMode) {
            if (modeChanged || ws != null) {
                ws?.stop()
                ws = null
            }
            setConnection(false)
            showIdleStatus()
            cameraAttendanceJob = lifecycleScope.launch { runCameraAttendanceLoop() }
            return
        }

        cameraAttendanceJob?.cancelAndJoin()
        cameraAttendanceJob = null
        if (modeChanged || connectionChanged || ws == null) {
            ws?.stop()
            ws = null
            reconnectWs()
        }
        showIdleStatus()
    }

    private fun reconnectWs() {
        if (config.deviceId.isBlank() || config.deviceToken.isBlank()) {
            setConnection(false)
            return
        }
        ws = KioskWebSocket(
            scope = lifecycleScope,
            onMessage = { text -> handleWsMessage(text) },
            onStatus = { online ->
                runOnUiThread { setConnection(online) }
            },
        ).also { it.start(config.apiBaseUrl, config.deviceId, config.deviceToken) }
    }

    private fun setConnection(online: Boolean) {
        ConnectionStatus.bind(binding.connectionDot, binding.wsStatus, online, this)
    }

    private enum class StatusTone { IDLE, VERIFY, PASS, FAIL }

    private fun setStatusTone(tone: StatusTone) {
        val color = ContextCompat.getColor(
            this,
            when (tone) {
                StatusTone.IDLE -> R.color.panel
                StatusTone.VERIFY -> R.color.verify_soft
                StatusTone.PASS -> R.color.pass_soft
                StatusTone.FAIL -> R.color.fail_soft
            },
        )
        binding.statusPanel.background = GradientDrawable().apply {
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(color)
        }
        binding.statusPanel.animate().cancel()
        binding.statusPanel.alpha = 0.75f
        binding.statusPanel.animate()
            .alpha(1f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val guideAlpha = when (tone) {
            StatusTone.PASS -> 0.35f
            StatusTone.FAIL -> 0.45f
            StatusTone.VERIFY -> 1f
            StatusTone.IDLE -> 0.85f
        }
        binding.faceGuide.animate().alpha(guideAlpha).setDuration(250).start()
    }

    private fun showIdleStatus() {
        binding.statusBanner.text = getString(
            if (config.cameraAttendanceMode) R.string.camera_ready else R.string.waiting,
        )
        binding.statusHint.text = getString(
            if (config.cameraAttendanceMode) R.string.camera_ready_hint else R.string.waiting_hint,
        )
        setStatusTone(StatusTone.IDLE)
    }

    private fun handleWsMessage(text: String) {
        if (config.cameraAttendanceMode) return
        val msg = JsonLite.parseVerification(text) ?: return
        verificationJob = lifecycleScope.launch { runVerification(msg) }
    }

    private suspend fun runVerification(msg: VerificationRequestMsg) {
        if (!verifyMutex.tryLock()) return
        try {
            withContext(Dispatchers.Main) {
                binding.statusBanner.text = getString(R.string.verifying)
                binding.statusHint.text = msg.name
                setStatusTone(StatusTone.VERIFY)
            }
            val pipe = pipeline
            if (pipe == null) {
                finishFail(msg, 0f, "model_not_loaded")
                return
            }

            var bestScore = -1f
            var bestFailBmp: Bitmap? = null
            val deadline = System.currentTimeMillis() + (msg.timeoutSeconds.coerceAtLeast(5) * 1000L)
            while (System.currentTimeMillis() < deadline) {
                val frame = pendingFrame.getAndSet(null)
                if (frame != null) {
                    val result = withContext(Dispatchers.Default) {
                        pipe.embedFromBitmap(frame)
                    }
                    if (result != null) {
                        val (emb, _) = result
                        val score = ArcFaceEmbedder.cosine(emb, msg.embedding)
                        if (score > bestScore) {
                            bestScore = score
                            bestFailBmp = frame
                        }
                        val threshold = if (config.threshold > 0f) config.threshold else msg.threshold
                        withContext(Dispatchers.Main) {
                            binding.statusBanner.text = getString(R.string.verifying)
                            binding.statusHint.text = "${msg.name}  ·  ${"%.2f".format(bestScore)}"
                        }
                        if (score >= threshold) {
                            finishPass(msg, score)
                            return
                        }
                    }
                }
                delay(80)
            }
            finishFail(msg, bestScore.coerceAtLeast(0f), "timeout_or_low_score", bestFailBmp)
        } finally {
            verifyMutex.unlock()
        }
    }

    private suspend fun runCameraAttendanceLoop() {
        while (currentCoroutineContext().isActive && config.cameraAttendanceMode) {
            val pipe = pipeline
            if (pipe == null) {
                delay(500)
                continue
            }

            val frame = pendingFrame.getAndSet(null)
            if (frame == null) {
                delay(120)
                continue
            }

            if (!verifyMutex.tryLock()) {
                delay(120)
                continue
            }

            try {
                val embedded = withContext(Dispatchers.Default) {
                    pipe.embedFromBitmap(frame)
                }
                if (embedded == null) {
                    delay(250)
                    continue
                }

                withContext(Dispatchers.Main) {
                    binding.statusBanner.text = getString(R.string.verifying)
                    binding.statusHint.text = "Identifying enrolled student…"
                    setStatusTone(StatusTone.VERIFY)
                }

                try {
                    val result = withContext(Dispatchers.IO) {
                        requireNotNull(apiClient)
                            .identifyCameraFace(embedded.first, ArcFaceEmbedder.MODEL_VERSION)
                    }
                    withContext(Dispatchers.Main) { setConnection(true) }

                    when {
                        result.matched && result.attendanceRecorded && !result.alreadyProcessed -> {
                            val student = result.name ?: result.enrollmentNumber ?: "Student"
                            withContext(Dispatchers.Main) {
                                binding.statusBanner.text = getString(R.string.attendance_recorded)
                                binding.statusHint.text =
                                    "$student  ·  ${"%.2f".format(result.score ?: 0f)}"
                                setStatusTone(StatusTone.PASS)
                                feedback?.playPass(student)
                            }
                            delay(2500)
                        }
                        result.matched && result.alreadyProcessed -> {
                            withContext(Dispatchers.Main) {
                                binding.statusBanner.text = getString(R.string.already_marked)
                                binding.statusHint.text = AttendanceFormat.alreadyMarkedHint(
                                    result.markedAt,
                                    result.message,
                                )
                                setStatusTone(StatusTone.VERIFY)
                            }
                            delay(1800)
                        }
                        result.matched -> {
                            withContext(Dispatchers.Main) {
                                binding.statusBanner.text = getString(R.string.fail)
                                binding.statusHint.text =
                                    result.message ?: "Attendance not recorded"
                                setStatusTone(StatusTone.FAIL)
                            }
                            delay(1500)
                        }
                        else -> {
                            delay(500)
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "camera attendance identify failed", t)
                    withContext(Dispatchers.Main) {
                        setConnection(false)
                        binding.statusBanner.text = getString(R.string.camera_ready)
                        binding.statusHint.text = "Face API unavailable · retrying"
                        setStatusTone(StatusTone.FAIL)
                    }
                    delay(2000)
                }

                withContext(Dispatchers.Main) { showIdleStatus() }
                delay(250)
            } finally {
                verifyMutex.unlock()
            }
        }
    }

    private suspend fun finishPass(msg: VerificationRequestMsg, score: Float) {
        withContext(Dispatchers.Main) {
            binding.statusBanner.text = getString(R.string.pass)
            binding.statusHint.text = "${msg.name}  ·  ${"%.2f".format(score)}"
            setStatusTone(StatusTone.PASS)
            feedback?.playPass(msg.name)
        }
        postResult(msg.requestId, score, true, null)
        delay(2500)
        withContext(Dispatchers.Main) { showIdleStatus() }
    }

    private suspend fun finishFail(
        msg: VerificationRequestMsg,
        score: Float,
        note: String,
        bmp: Bitmap? = null,
    ) {
        withContext(Dispatchers.Main) {
            binding.statusBanner.text = getString(R.string.fail)
            binding.statusHint.text = "Score ${"%.2f".format(score)}  ·  try again"
            setStatusTone(StatusTone.FAIL)
            feedback?.playFail()
        }
        val failFile = bmp?.let { saveJpeg(it, "fail_${msg.requestId}.jpg") }
        postResult(msg.requestId, score, false, failFile, note)
        delay(2500)
        withContext(Dispatchers.Main) { showIdleStatus() }
    }

    private suspend fun postResult(
        requestId: String,
        score: Float,
        passed: Boolean,
        failFile: File?,
        note: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val client = apiClient ?: FaceApiClient(config.apiBaseUrl, config.deviceToken)
                val ok = client.submitResult(requestId, score, passed, failFile, note)
                if (!ok) {
                    offlineQueue.enqueue(requestId, score, passed, failFile?.absolutePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "submit result failed", e)
                offlineQueue.enqueue(requestId, score, passed, failFile?.absolutePath)
            }
        }
    }

    private fun flushOffline() {
        lifecycleScope.launch(Dispatchers.IO) {
            val client = apiClient ?: FaceApiClient(config.apiBaseUrl, config.deviceToken)
            offlineQueue.drain { requestId, score, passed, failPath ->
                try {
                    client.submitResult(requestId, score, passed, failPath?.let { File(it) })
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private fun saveJpeg(bitmap: Bitmap, name: String): File {
        val file = File(cacheDir, name)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file
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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
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
        modeConfigJob?.cancel()
        cameraAttendanceJob?.cancel()
        verificationJob?.cancel()
        ws?.stop()
        pipeline?.close()
        embedder?.close()
        feedback?.release()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
