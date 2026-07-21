package com.school.faceverify.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AttendanceFormat {
    private val timeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    fun formatMarkedAt(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val instant = Instant.parse(iso)
            timeFormatter.format(instant.atZone(ZoneId.systemDefault()))
        } catch (_: Exception) {
            null
        }
    }

    fun alreadyMarkedHint(iso: String?, fallbackMessage: String?): String {
        val time = formatMarkedAt(iso)
        return if (time != null) {
            "Marked at $time · try again after 15 minutes"
        } else {
            fallbackMessage ?: "Try again after 15 minutes"
        }
    }
}
