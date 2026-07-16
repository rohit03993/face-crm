package com.school.faceverify.net

data class VerificationRequestMsg(
    val type: String,
    val requestId: String,
    val studentId: String,
    val enrollmentNumber: String,
    val name: String,
    val modelVersion: String,
    val embedding: FloatArray,
    val threshold: Float,
    val timeoutSeconds: Int,
)

object JsonLite {
    fun parseVerification(json: String): VerificationRequestMsg? {
        if (!json.contains("\"verification_request\"")) return null
        fun str(key: String): String {
            val re = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return re.find(json)?.groupValues?.get(1) ?: ""
        }
        fun num(key: String): Double {
            val re = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)")
            return re.find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        }
        val embMatch = Regex("\"embedding\"\\s*:\\s*\\[([^\\]]*)\\]").find(json) ?: return null
        val embedding = embMatch.groupValues[1]
            .split(",")
            .mapNotNull { it.trim().toFloatOrNull() }
            .toFloatArray()
        if (embedding.isEmpty()) return null
        return VerificationRequestMsg(
            type = "verification_request",
            requestId = str("request_id"),
            studentId = str("student_id"),
            enrollmentNumber = str("enrollment_number"),
            name = str("name"),
            modelVersion = str("model_version"),
            embedding = embedding,
            threshold = num("threshold").toFloat(),
            timeoutSeconds = num("timeout_seconds").toInt(),
        )
    }
}
