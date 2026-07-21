package com.school.faceverify.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Converts CameraX frames to Bitmaps with correct stride handling.
 * Prefer [ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888] so YUV packing bugs are avoided.
 */
object FrameConverter {
    fun toBitmap(image: ImageProxy, mirrorFrontCamera: Boolean = true): Bitmap? {
        return try {
            var bmp = when (image.format) {
                ImageFormat.YUV_420_888 -> yuv420ToBitmap(image)
                // CameraX RGBA_8888 reports PixelFormat.RGBA_8888 (1) or sometimes 0x1
                else -> rgbaToBitmap(image) ?: yuv420ToBitmap(image)
            } ?: return null

            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            if (mirrorFrontCamera) {
                val mirror = Matrix()
                mirror.preScale(-1f, 1f)
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mirror, true)
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun rgbaToBitmap(image: ImageProxy): Bitmap? {
        if (image.planes.isEmpty()) return null
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || pixelStride <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        if (rowStride == width * pixelStride) {
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        // Row padding: copy row-by-row into a packed ARGB buffer.
        val rowData = ByteArray(rowStride)
        val pixels = IntArray(width * height)
        var pixelIndex = 0
        for (row in 0 until height) {
            buffer.get(rowData, 0, rowStride.coerceAtMost(buffer.remaining()))
            var col = 0
            while (col < width) {
                val offset = col * pixelStride
                val r = rowData[offset].toInt() and 0xff
                val g = rowData[offset + 1].toInt() and 0xff
                val b = rowData[offset + 2].toInt() and 0xff
                val a = if (pixelStride >= 4) rowData[offset + 3].toInt() and 0xff else 0xff
                pixels[pixelIndex++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                col++
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun yuv420ToBitmap(image: ImageProxy): Bitmap? {
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    /** Correct YUV_420_888 → NV21 with rowStride / pixelStride handling. */
    private fun yuv420ToNv21(image: ImageProxy): ByteArray? {
        val width = image.width
        val height = image.height
        if (image.planes.size < 3 || width <= 0 || height <= 0) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        // Y plane
        var outputPos = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
            outputPos = width * height
        } else {
            var inputPos = 0
            for (row in 0 until height) {
                yBuffer.position(inputPos)
                yBuffer.get(nv21, outputPos, width)
                inputPos += yRowStride
                outputPos += width
            }
        }

        // VU interleaved (NV21)
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uvRowStart = row * uvRowStride
            for (col in 0 until chromaWidth) {
                val uvOffset = uvRowStart + col * uvPixelStride
                nv21[outputPos++] = vBuffer.getSafe(uvOffset)
                nv21[outputPos++] = uBuffer.getSafe(uvOffset)
            }
        }
        return nv21
    }

    private fun ByteBuffer.getSafe(index: Int): Byte {
        return if (index in 0 until capacity()) get(index) else 0
    }
}
