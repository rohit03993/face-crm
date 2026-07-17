package com.school.faceverify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var loopJob: Job? = null

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
            if (pipe == null || client == null) {
                delay(400)
                continue
            }

            val frame = pendingFrame.getAndSet(null)
            if (frame == null) {
                delay(100)
                continue
            }
            if (!identifyMutex.tryLock()) {
                delay(100)
                continue
            }

            try {
                val embedded = withContext(Dispatchers.Default) { pipe.embedFromBitmap(frame) }
                if (embedded == null) {
                    delay(200)
                    continue
                }

                withContext(Dispatchers.Main) {
                    binding.statusBanner.text = getString(R.string.verifying)
                    binding.statusHint.text = getString(R.string.identifying)
                    setTone(Tone.VERIFY)
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
                                setTone(Tone.PASS)
                                feedback?.playPass(student)
                            }
                            // Short success flash so the next student can punch quickly.
                            delay(700)
                        }
                        result.matched && result.alreadyProcessed -> delay(250)
                        result.matched -> {
                            withContext(Dispatchers.Main) {
                                binding.statusBanner.text = getString(R.string.fail)
                                binding.statusHint.text =
                                    result.message ?: getString(R.string.attendance_not_recorded)
                                setTone(Tone.FAIL)
                            }
                            delay(800)
                        }
                        else -> delay(150)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "identify failed", t)
                    withContext(Dispatchers.Main) {
                        setOnline(false)
                        binding.statusBanner.text = getString(R.string.camera_ready)
                        binding.statusHint.text = getString(R.string.api_retry)
                        setTone(Tone.FAIL)
                    }
                    delay(1000)
                }

                withContext(Dispatchers.Main) { showIdle() }
                delay(80)
            } finally {
                identifyMutex.unlock()
            }
        }
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
        binding.statusBanner.text = getString(R.string.camera_ready)
        binding.statusHint.text = getString(R.string.camera_ready_hint)
        setTone(Tone.IDLE)
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
