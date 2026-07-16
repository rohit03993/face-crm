package com.school.faceverify.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class FaceApiClient(
    private val apiBaseUrl: String,
    private val deviceToken: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    private val base get() = apiBaseUrl.trimEnd('/')

    fun submitResult(
        requestId: String,
        score: Float,
        passed: Boolean,
        failImage: File? = null,
        note: String? = null,
    ): Boolean {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("request_id", requestId)
            .addFormDataPart("score", score.toString())
            .addFormDataPart("passed", passed.toString())
        if (note != null) builder.addFormDataPart("note", note)
        if (failImage != null && failImage.exists()) {
            builder.addFormDataPart(
                "fail_image",
                failImage.name,
                failImage.asRequestBody("image/jpeg".toMediaType()),
            )
        }
        val request = Request.Builder()
            .url("$base/verification-results")
            .header("Authorization", "Bearer $deviceToken")
            .post(builder.build())
            .build()
        client.newCall(request).execute().use { resp ->
            return resp.isSuccessful
        }
    }

    fun enroll(studentId: String, images: List<File>, angles: List<String>): Pair<Boolean, String> {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (angles.isNotEmpty()) {
            builder.addFormDataPart("angles", angles.joinToString(","))
        }
        images.forEachIndexed { idx, file ->
            builder.addFormDataPart(
                "images",
                file.name,
                file.asRequestBody("image/jpeg".toMediaType()),
            )
        }
        val request = Request.Builder()
            .url("$base/students/$studentId/enroll")
            .header("Authorization", "Bearer $deviceToken")
            .post(builder.build())
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return resp.isSuccessful to body
        }
    }

    fun health(): Boolean {
        val request = Request.Builder().url("$base/health").get().build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
