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
import com.school.faceverify.databinding.ActivityEnrollBinding
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class EnrollActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEnrollBinding
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val captures = mutableListOf<File>()
    private val angles = listOf(
        "front", "left", "right", "up", "down",
        "front2", "left2", "right2", "smile", "neutral",
    )
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateUi()

        // Prefill easy roll number (demo seed student)
        binding.inputStudentId.setText("STU001")

        binding.btnCapture.setOnClickListener { captureShot() }
        binding.btnUpload.setOnClickListener { upload() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun captureShot() {
        if (captures.size >= 10) {
            Toast.makeText(this, "Max 10 images", Toast.LENGTH_SHORT).show()
            return
        }
        val frame = pendingFrame.get()
        if (frame == null) {
            Toast.makeText(this, "No frame yet", Toast.LENGTH_SHORT).show()
            return
        }
        val angle = angles.getOrElse(captures.size) { "shot_${captures.size + 1}" }
        val file = File(cacheDir, "enroll_${captures.size + 1}_$angle.jpg")
        file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        captures.add(file)
        updateUi()
        Toast.makeText(this, "Captured $angle", Toast.LENGTH_SHORT).show()
    }

    private fun updateUi() {
        val n = captures.size
        val needed = 5
        binding.captureCount.text = when {
            n < needed -> "$n of $needed captured"
            else -> "$n captured · ready to upload"
        }
        binding.enrollProgress.max = needed
        binding.enrollProgress.progress = n.coerceAtMost(needed)
        binding.btnUpload.isEnabled = n in 5..10
        val hint = when {
            n == 0 -> "Look straight into the oval"
            n == 1 -> "Turn slightly left"
            n == 2 -> "Turn slightly right"
            n == 3 -> "Tilt chin up a little"
            n == 4 -> "Tilt chin down a little"
            else -> "Looking good — tap Upload"
        }
        binding.hintText.text = hint
    }

    private fun upload() {
        val studentId = binding.inputStudentId.text?.toString()?.trim().orEmpty()
        if (studentId.isBlank()) {
            Toast.makeText(this, "Enter student ID", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnUpload.isEnabled = false
        binding.btnCapture.isEnabled = false
        binding.hintText.text = "Uploading… first time can take 2–5 minutes. Wait."
        Toast.makeText(this, "Uploading… please wait", Toast.LENGTH_LONG).show()

        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val (ok, body) = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                        .enroll(studentId, captures.toList(), angles.take(captures.size))
                }
                if (ok) {
                    Toast.makeText(this@EnrollActivity, "Enrolled OK", Toast.LENGTH_LONG).show()
                    captures.clear()
                    updateUi()
                    finish()
                } else {
                    binding.hintText.text = "Enroll failed"
                    Toast.makeText(this@EnrollActivity, "Enroll failed: $body", Toast.LENGTH_LONG).show()
                    binding.btnUpload.isEnabled = captures.size in 5..10
                    binding.btnCapture.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e("EnrollActivity", "enroll upload failed", e)
                binding.hintText.text = "Upload error"
                Toast.makeText(
                    this@EnrollActivity,
                    "Upload error: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
                binding.btnUpload.isEnabled = captures.size in 5..10
                binding.btnCapture.isEnabled = true
            }
        }
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
            Log.w("EnrollActivity", "frame convert failed", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
