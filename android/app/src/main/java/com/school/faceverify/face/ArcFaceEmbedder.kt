package com.school.faceverify.face

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * ArcFace w600k_r50 ONNX runner.
 * Input: NCHW float32 [1,3,112,112], RGB normalized (x-127.5)/128
 * Output: 512-d embedding (we L2-normalize).
 */
class ArcFaceEmbedder(context: Context) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val modelFile = ModelStore.modelFile(context)
        require(ModelStore.isReady(context)) {
            "Face model missing — wait for download to finish on Home"
        }
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(2)
        // Load from file path (avoids holding a 170MB byte[] on the heap)
        session = env.createSession(modelFile.absolutePath, opts)
        inputName = session.inputNames.iterator().next()
    }

    fun embed(aligned112: Bitmap): FloatArray {
        require(aligned112.width == 112 && aligned112.height == 112)
        val input = preprocess(aligned112)
        val shape = longArrayOf(1, 3, 112, 112)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result[0].value
                val raw: FloatArray = when (out) {
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (out as Array<FloatArray>)[0]
                    }
                    is FloatArray -> out
                    else -> error("Unexpected ONNX output type: ${out?.javaClass}")
                }
                return l2Normalize(raw)
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(112 * 112)
        bitmap.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val chw = FloatArray(3 * 112 * 112)
        var i = 0
        for (y in 0 until 112) {
            for (x in 0 until 112) {
                val c = pixels[i++]
                val r = ((c shr 16) and 0xff).toFloat()
                val g = ((c shr 8) and 0xff).toFloat()
                val b = (c and 0xff).toFloat()
                val idx = y * 112 + x
                chw[0 * 112 * 112 + idx] = (r - 127.5f) / 128f
                chw[1 * 112 * 112 + idx] = (g - 127.5f) / 128f
                chw[2 * 112 * 112 + idx] = (b - 127.5f) / 128f
            }
        }
        return chw
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_ASSET = "w600k_r50.onnx"
        const val MODEL_VERSION = "w600k_r50"
        const val EMBEDDING_DIM = 512

        fun l2Normalize(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x * x
            val n = sqrt(sum).toFloat().coerceAtLeast(1e-6f)
            return FloatArray(v.size) { i -> v[i] / n }
        }

        fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
            require(embeddings.isNotEmpty())
            val dim = embeddings[0].size
            val mean = FloatArray(dim)
            for (emb in embeddings) {
                require(emb.size == dim)
                for (i in 0 until dim) mean[i] += emb[i]
            }
            val n = embeddings.size.toFloat()
            for (i in 0 until dim) mean[i] /= n
            return l2Normalize(mean)
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size)
            var dot = 0.0
            for (i in a.indices) dot += a[i] * b[i]
            return dot.toFloat()
        }
    }
}
