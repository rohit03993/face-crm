package com.school.faceverify.face

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Align face to ArcFace 112x112 template using 5 landmarks
 * (left eye, right eye, nose, left mouth, right mouth).
 *
 * Target landmarks match InsightFace ArcFace reference roughly.
 */
object FaceAligner {
    private const val OUT = 112

    // ArcFace reference 5-points for 112x112
    private val TARGET = arrayOf(
        PointF(38.2946f, 51.6963f),
        PointF(73.5318f, 51.5014f),
        PointF(56.0252f, 71.7366f),
        PointF(41.5493f, 92.3655f),
        PointF(70.7299f, 92.2041f),
    )

    fun align(src: Bitmap, landmarks: List<PointF>): Bitmap {
        require(landmarks.size >= 5) { "Need 5 landmarks" }
        val srcPts = landmarks.take(5).toTypedArray()
        val matrix = similarityTransform(srcPts, TARGET)
        val out = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    /** Umeyama-like similarity: scale, rotation, translation from 5 points. */
    private fun similarityTransform(src: Array<PointF>, dst: Array<PointF>): android.graphics.Matrix {
        val n = src.size
        var srcMeanX = 0f
        var srcMeanY = 0f
        var dstMeanX = 0f
        var dstMeanY = 0f
        for (i in 0 until n) {
            srcMeanX += src[i].x
            srcMeanY += src[i].y
            dstMeanX += dst[i].x
            dstMeanY += dst[i].y
        }
        srcMeanX /= n
        srcMeanY /= n
        dstMeanX /= n
        dstMeanY /= n

        var srcVar = 0.0
        var cov00 = 0.0
        var cov01 = 0.0
        var cov10 = 0.0
        var cov11 = 0.0
        for (i in 0 until n) {
            val sx = (src[i].x - srcMeanX).toDouble()
            val sy = (src[i].y - srcMeanY).toDouble()
            val dx = (dst[i].x - dstMeanX).toDouble()
            val dy = (dst[i].y - dstMeanY).toDouble()
            srcVar += sx * sx + sy * sy
            cov00 += sx * dx
            cov01 += sx * dy
            cov10 += sy * dx
            cov11 += sy * dy
        }
        srcVar /= n
        cov00 /= n
        cov01 /= n
        cov10 /= n
        cov11 /= n

        // 2x2 SVD approximation for rotation from covariance
        val det = cov00 * cov11 - cov01 * cov10
        val trace = cov00 + cov11
        val scale = if (srcVar < 1e-8) 1.0 else {
            // Use Frobenius of cov / srcVar as scale proxy with polar decomposition
            val a = cov00 + cov11
            val b = cov10 - cov01
            hypot(a, b) / srcVar
        }
        val angle = atan2(cov10 - cov01, cov00 + cov11)
        val c = cos(angle) * scale
        val s = sin(angle) * scale

        val m00 = c
        val m01 = -s
        val m10 = s
        val m11 = c
        val tx = dstMeanX - (m00 * srcMeanX + m01 * srcMeanY)
        val ty = dstMeanY - (m10 * srcMeanX + m11 * srcMeanY)

        val matrix = android.graphics.Matrix()
        matrix.setValues(
            floatArrayOf(
                m00.toFloat(), m01.toFloat(), tx.toFloat(),
                m10.toFloat(), m11.toFloat(), ty.toFloat(),
                0f, 0f, 1f,
            )
        )
        // silence unused
        max(det, trace)
        min(scale, 10.0)
        return matrix
    }
}
