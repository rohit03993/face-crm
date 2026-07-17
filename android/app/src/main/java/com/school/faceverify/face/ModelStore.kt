package com.school.faceverify.face

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the large ArcFace ONNX out of the APK.
 * Downloads once from `{apiBaseUrl}/models/w600k_r50.onnx` into app files.
 */
object ModelStore {
    const val MODEL_FILE_NAME = ArcFaceEmbedder.MODEL_ASSET
    /** w600k_r50 is ~166MB — reject tiny/corrupt downloads. */
    const val MIN_VALID_BYTES = 80_000_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    fun modelFile(context: Context): File = File(context.filesDir, MODEL_FILE_NAME)

    fun isReady(context: Context): Boolean {
        val file = modelFile(context)
        return file.exists() && file.length() >= MIN_VALID_BYTES
    }

    /**
     * Ensures the model file exists on disk.
     * Order: existing filesDir → optional APK asset (dev) → download from API.
     */
    fun ensureReady(
        context: Context,
        apiBaseUrl: String,
        onProgress: ((Int) -> Unit)? = null,
    ): File {
        val out = modelFile(context)
        if (out.exists() && out.length() >= MIN_VALID_BYTES) {
            onProgress?.invoke(100)
            return out
        }
        if (out.exists()) out.delete()

        // Optional: fat debug builds may still ship the asset.
        try {
            context.assets.open(MODEL_FILE_NAME).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.exists() && out.length() >= MIN_VALID_BYTES) {
                onProgress?.invoke(100)
                return out
            }
            out.delete()
        } catch (_: Exception) {
            out.delete()
        }

        val base = apiBaseUrl.trimEnd('/')
        val url = "$base/models/$MODEL_FILE_NAME"
        Log.i("ModelStore", "Downloading face model from $url")
        download(url, out, onProgress)
        if (!out.exists() || out.length() < MIN_VALID_BYTES) {
            out.delete()
            throw IOException("Face model download incomplete from $url")
        }
        return out
    }

    private fun download(url: String, dest: File, onProgress: ((Int) -> Unit)?) {
        val tmp = File(dest.absolutePath + ".part")
        if (tmp.exists()) tmp.delete()

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Model download failed (${resp.code}) from $url")
            }
            val body = resp.body ?: throw IOException("Empty model response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1024 * 256)
                    var readTotal = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        if (total > 0 && onProgress != null) {
                            val pct = ((readTotal * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        onProgress?.invoke(100)
    }
}
