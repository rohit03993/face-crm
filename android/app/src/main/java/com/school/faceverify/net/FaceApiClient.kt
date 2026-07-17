package com.school.faceverify.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CameraIdentifyResult(
    val matched: Boolean,
    val attendanceRecorded: Boolean,
    val alreadyProcessed: Boolean,
    val studentId: String?,
    val enrollmentNumber: String?,
    val name: String?,
    val score: Float?,
    val threshold: Float,
    val message: String?,
)

data class StudentListItem(
    val id: String,
    val enrollmentNumber: String,
    val name: String,
    val batch: String?,
    val enrolled: Boolean,
    val imageCount: Int,
)

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

    fun listStudents(): List<StudentListItem> {
        val request = Request.Builder()
            .url("$base/students")
            .header("Authorization", "Bearer $deviceToken")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("List students failed (${resp.code}): $raw")
            }
            val arr = JSONArray(raw)
            return buildList {
                for (i in 0 until arr.length()) {
                    val json = arr.getJSONObject(i)
                    add(
                        StudentListItem(
                            id = json.optString("id"),
                            enrollmentNumber = json.optString("enrollment_number"),
                            name = json.optString("name"),
                            batch = json.optStringOrNull("batch"),
                            enrolled = json.optBoolean("enrolled", false),
                            imageCount = json.optInt("image_count", 0),
                        ),
                    )
                }
            }
        }
    }

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

    fun identifyCameraFace(embedding: FloatArray, modelVersion: String): CameraIdentifyResult {
        val payload = JSONObject()
            .put("model_version", modelVersion)
            .put("embedding", JSONArray().apply {
                embedding.forEach { put(it.toDouble()) }
            })
        val request = Request.Builder()
            .url("$base/camera-identify")
            .header("Authorization", "Bearer $deviceToken")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Camera identify failed (${resp.code}): $raw")
            }
            val json = JSONObject(raw)
            return CameraIdentifyResult(
                matched = json.optBoolean("matched", false),
                attendanceRecorded = json.optBoolean("attendance_recorded", false),
                alreadyProcessed = json.optBoolean("already_processed", false),
                studentId = json.optStringOrNull("student_id"),
                enrollmentNumber = json.optStringOrNull("enrollment_number"),
                name = json.optStringOrNull("name"),
                score = if (json.isNull("score")) null else json.optDouble("score").toFloat(),
                threshold = json.optDouble("threshold", 0.4).toFloat(),
                message = json.optStringOrNull("message"),
            )
        }
    }

    fun enroll(
        studentId: String,
        images: List<File>,
        angles: List<String>,
        name: String? = null,
    ): Pair<Boolean, String> {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (angles.isNotEmpty()) {
            builder.addFormDataPart("angles", angles.joinToString(","))
        }
        if (!name.isNullOrBlank()) {
            builder.addFormDataPart("name", name.trim())
        }
        images.forEach { file ->
            builder.addFormDataPart(
                "images",
                file.name,
                file.asRequestBody("image/jpeg".toMediaType()),
            )
        }
        val encodedId = java.net.URLEncoder
            .encode(studentId.trim(), Charsets.UTF_8.name())
            .replace("+", "%20")
        val request = Request.Builder()
            .url("$base/students/$encodedId/enroll")
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

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}
