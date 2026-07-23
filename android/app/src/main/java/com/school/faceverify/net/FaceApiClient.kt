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
    val markedAt: String? = null,
)

data class StudentListItem(
    val id: String,
    val enrollmentNumber: String,
    val name: String,
    val batch: String?,
    val enrolled: Boolean,
    val imageCount: Int,
    val hasFacePhoto: Boolean = false,
)

sealed class DeviceAuthResult {
    data object Ok : DeviceAuthResult()
    data object Offline : DeviceAuthResult()
    data class Failed(val message: String) : DeviceAuthResult()
}

data class AppUserInfo(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val isActive: Boolean = true,
)

data class AppAuthResult(
    val userToken: String,
    val user: AppUserInfo,
    val message: String? = null,
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

    /** Short timeouts for connection-status probes so the UI stays responsive. */
    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
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
                            hasFacePhoto = json.optBoolean("has_face_photo", false),
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
                markedAt = json.optStringOrNull("marked_at"),
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

    /** Upload phone-computed template only (~2KB) — no face JPEGs. */
    fun enrollTemplate(
        studentId: String,
        embedding: FloatArray,
        modelVersion: String,
        imageCount: Int,
        name: String? = null,
        enrollmentNumber: String? = null,
    ): Pair<Boolean, String> {
        val payload = JSONObject()
            .put("model_version", modelVersion)
            .put("image_count", imageCount)
            .put("student_id", studentId.trim())
            .put(
                "embedding",
                JSONArray().apply { embedding.forEach { put(it.toDouble()) } },
            )
        if (!name.isNullOrBlank()) payload.put("name", name.trim())
        if (!enrollmentNumber.isNullOrBlank()) {
            payload.put("enrollment_number", enrollmentNumber.trim())
        }
        // Collection POST — works even when path-style routes are missing/blocked.
        val request = Request.Builder()
            .url("$base/students/enroll-template")
            .header("Authorization", "Bearer $deviceToken")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            return resp.isSuccessful to friendlyError(resp.code, resp.body?.string().orEmpty())
        }
    }

    /**
     * Returns null if face is unique, or a human-readable message if it matches
     * another enrolled student.
     */
    fun checkDuplicateFace(
        embedding: FloatArray,
        modelVersion: String,
        excludeStudentId: String? = null,
    ): String? {
        val payload = JSONObject()
            .put("model_version", modelVersion)
            .put(
                "embedding",
                JSONArray().apply { embedding.forEach { put(it.toDouble()) } },
            )
        if (!excludeStudentId.isNullOrBlank()) {
            payload.put("exclude_student_id", excludeStudentId.trim())
        }
        val request = Request.Builder()
            .url("$base/students/check-face")
            .header("Authorization", "Bearer $deviceToken")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // Don't block capture on check failures — save will still enforce.
                return null
            }
            val json = JSONObject(raw)
            if (!json.optBoolean("duplicate", false)) return null
            return json.optString("message").takeIf { it.isNotBlank() }
                ?: "This face is already enrolled for another student"
        }
    }

    /** Download the enrollment reference photo used when the face was registered. */
    fun fetchFacePhoto(studentId: String): ByteArray? {
        val encodedId = encodePath(studentId)
        val request = Request.Builder()
            .url("$base/students/$encodedId/face-photo")
            .header("Authorization", "Bearer $deviceToken")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }

    fun uploadFacePhoto(studentId: String, jpegFile: File): Pair<Boolean, String> {
        val encodedId = encodePath(studentId)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "photo",
                jpegFile.name,
                jpegFile.asRequestBody("image/jpeg".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$base/students/$encodedId/face-photo")
            .header("Authorization", "Bearer $deviceToken")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            return resp.isSuccessful to friendlyError(resp.code, resp.body?.string().orEmpty())
        }
    }

    fun updateStudent(
        studentId: String,
        name: String?,
        enrollmentNumber: String?,
        userToken: String,
    ): Pair<Boolean, String> {
        val payload = JSONObject()
        if (!name.isNullOrBlank()) payload.put("name", name.trim())
        if (!enrollmentNumber.isNullOrBlank()) {
            payload.put("enrollment_number", enrollmentNumber.trim())
        }
        val encodedId = encodePath(studentId)
        // Prefer POST /update — PATCH is blocked on some networks / old proxies.
        val request = Request.Builder()
            .url("$base/students/$encodedId/update")
            .header("Authorization", "Bearer $deviceToken")
            .header("X-User-Token", userToken.trim())
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            return resp.isSuccessful to friendlyError(resp.code, resp.body?.string().orEmpty())
        }
    }

    fun deleteStudent(studentId: String, userToken: String): Pair<Boolean, String> {
        val encodedId = encodePath(studentId)
        // Prefer POST /remove — DELETE is blocked on some networks / old proxies.
        val request = Request.Builder()
            .url("$base/students/$encodedId/remove")
            .header("Authorization", "Bearer $deviceToken")
            .header("X-User-Token", userToken.trim())
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            return (resp.isSuccessful || resp.code == 204) to friendlyError(resp.code, raw)
        }
    }

    fun health(): Boolean = healthQuick()

    fun healthQuick(): Boolean {
        val request = Request.Builder().url("$base/health").get().build()
        return try {
            healthClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Confirms Face URL is reachable and device token (and optional device id) are valid.
     * /health alone is not enough — it does not require auth.
     */
    fun verifyDeviceCredentials(expectedDeviceId: String): DeviceAuthResult {
        val expectedId = expectedDeviceId.trim()
        if (deviceToken.isBlank()) {
            return DeviceAuthResult.Failed("Enter device token")
        }
        if (expectedId.isBlank()) {
            return DeviceAuthResult.Failed("Enter device ID")
        }
        return try {
            val meReq = Request.Builder()
                .url("$base/devices/me")
                .header("Authorization", "Bearer $deviceToken")
                .get()
                .build()
            healthClient.newCall(meReq).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        val remoteId = try {
                            JSONObject(raw).optString("id").trim()
                        } catch (_: Exception) {
                            ""
                        }
                        if (remoteId.isNotBlank() && remoteId != expectedId) {
                            DeviceAuthResult.Failed(
                                "Device ID does not match this token (server has $remoteId)",
                            )
                        } else {
                            DeviceAuthResult.Ok
                        }
                    }
                    resp.code == 401 || resp.code == 403 ->
                        DeviceAuthResult.Failed("Invalid device token")
                    resp.code == 404 -> verifyViaStudentsFallback()
                    else -> DeviceAuthResult.Failed(friendlyError(resp.code, raw))
                }
            }
        } catch (_: Exception) {
            DeviceAuthResult.Offline
        }
    }

    fun authStatus(): Pair<Boolean, Boolean> {
        // returns needsBootstrap to hasUsers via Pair(needsBootstrap, hasUsers)
        val request = Request.Builder()
            .url("$base/auth/status")
            .header("Authorization", "Bearer $deviceToken")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(friendlyError(resp.code, raw))
            }
            val json = JSONObject(raw)
            return json.optBoolean("needs_bootstrap", false) to json.optBoolean("has_users", false)
        }
    }

    fun bootstrapAdmin(email: String, password: String, name: String): AppAuthResult =
        postAuth("/auth/bootstrap", email, password, name)

    fun login(email: String, password: String): AppAuthResult =
        postAuth("/auth/login", email, password, name = null)

    private fun postAuth(
        path: String,
        email: String,
        password: String,
        name: String?,
    ): AppAuthResult {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        if (!name.isNullOrBlank()) payload.put("name", name.trim())
        val request = Request.Builder()
            .url("$base$path")
            .header("Authorization", "Bearer $deviceToken")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(friendlyError(resp.code, raw))
            }
            return parseAuth(raw)
        }
    }

    fun logout(userToken: String): Boolean {
        val request = Request.Builder()
            .url("$base/auth/logout")
            .header("Authorization", "Bearer $userToken")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    fun listAppUsers(userToken: String): List<AppUserInfo> {
        val request = Request.Builder()
            .url("$base/auth/users")
            .header("Authorization", "Bearer $userToken")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(friendlyError(resp.code, raw))
            val arr = JSONArray(raw)
            return buildList {
                for (i in 0 until arr.length()) {
                    add(parseUser(arr.getJSONObject(i)))
                }
            }
        }
    }

    fun createStaff(
        userToken: String,
        email: String,
        password: String,
        name: String,
    ): AppUserInfo {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("name", name.trim())
        val request = Request.Builder()
            .url("$base/auth/users")
            .header("Authorization", "Bearer $userToken")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(friendlyError(resp.code, raw))
            return parseUser(JSONObject(raw))
        }
    }

    fun deactivateUser(userToken: String, userId: String): Boolean {
        val request = Request.Builder()
            .url("$base/auth/users/${encodePath(userId)}")
            .header("Authorization", "Bearer $userToken")
            .delete()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException(friendlyError(resp.code, resp.body?.string().orEmpty()))
            }
            return true
        }
    }

    private fun parseAuth(raw: String): AppAuthResult {
        val json = JSONObject(raw)
        val userJson = json.getJSONObject("user")
        return AppAuthResult(
            userToken = json.getString("user_token"),
            user = parseUser(userJson),
            message = json.optString("message").takeIf { it.isNotBlank() },
        )
    }

    private fun parseUser(json: JSONObject): AppUserInfo =
        AppUserInfo(
            id = json.optString("id"),
            email = json.optString("email"),
            name = json.optString("name"),
            role = json.optString("role"),
            isActive = json.optBoolean("is_active", true),
        )

    /** Older Face API without /devices/me — token-only check via /students. */
    private fun verifyViaStudentsFallback(): DeviceAuthResult {
        return try {
            val request = Request.Builder()
                .url("$base/students")
                .header("Authorization", "Bearer $deviceToken")
                .get()
                .build()
            healthClient.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> DeviceAuthResult.Ok
                    resp.code == 401 || resp.code == 403 ->
                        DeviceAuthResult.Failed("Invalid device token")
                    else -> DeviceAuthResult.Failed(
                        friendlyError(resp.code, resp.body?.string().orEmpty()),
                    )
                }
            }
        } catch (_: Exception) {
            DeviceAuthResult.Offline
        }
    }

    private fun encodePath(value: String): String =
        java.net.URLEncoder
            .encode(value.trim(), Charsets.UTF_8.name())
            .replace("+", "%20")

    private fun hostLabel(): String = try {
        java.net.URI(base).host ?: base
    } catch (_: Exception) {
        base
    }

    private fun friendlyError(code: Int, raw: String): String {
        val host = hostLabel()
        if (raw.isBlank()) {
            return when (code) {
                404 -> "Missing on $host — update that server, or point Settings to your PC API"
                401, 403 -> "Not authorized on $host — check device token in Settings"
                else -> "Request failed ($code) on $host"
            }
        }
        val detail = try {
            val json = JSONObject(raw)
            when (val d = json.opt("detail")) {
                is String -> d
                is org.json.JSONArray -> d.optJSONObject(0)?.optString("msg") ?: d.toString()
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
        return when {
            detail.equals("Not Found", ignoreCase = true) || code == 404 ->
                "Edit/Delete not on $host yet. In Settings set API to your PC (e.g. http://192.168.x.x:8000) or deploy latest API to $host"
            else -> "$detail ($host)"
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}
