package com.school.faceverify.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for cosine + L2 normalize (parity helpers).
 * Full server/device parity requires running embed_image.py vs on-device
 * with the same photo after model assets are installed.
 */
class EmbeddingMathTest {
    @Test
    fun l2NormalizeUnitLength() {
        val v = floatArrayOf(3f, 4f)
        val n = ArcFaceEmbedder.l2Normalize(v)
        val len = kotlin.math.sqrt((n[0] * n[0] + n[1] * n[1]).toDouble())
        assertEquals(1.0, len, 1e-5)
    }

    @Test
    fun identicalVectorsCosineOne() {
        val a = ArcFaceEmbedder.l2Normalize(FloatArray(512) { 1f })
        val score = ArcFaceEmbedder.cosine(a, a)
        assertEquals(1.0f, score, 1e-5f)
    }

    @Test
    fun orthogonalNearZero() {
        val a = FloatArray(512) { if (it == 0) 1f else 0f }
        val b = FloatArray(512) { if (it == 1) 1f else 0f }
        val score = ArcFaceEmbedder.cosine(
            ArcFaceEmbedder.l2Normalize(a),
            ArcFaceEmbedder.l2Normalize(b),
        )
        assertTrue(kotlin.math.abs(score) < 1e-5f)
    }
}
