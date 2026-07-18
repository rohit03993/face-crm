package com.school.faceverify.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("face_verify_settings")

data class KioskConfig(
    // Only API URL usually needs changing for a real phone (use PC LAN IP).
    val apiBaseUrl: String = DEFAULT_API_URL,
    val deviceId: String = DEFAULT_DEVICE_ID,
    val deviceToken: String = DEFAULT_DEVICE_TOKEN,
    val threshold: Float = DEFAULT_THRESHOLD,
    val cameraAttendanceMode: Boolean = false,
) {
    companion object {
        // Shared Face Platform for all schools. Per-school pairing = device number + token.
        const val DEFAULT_API_URL = "https://face.taskbook.co.in"
        const val DEFAULT_DEVICE_ID = ""
        const val DEFAULT_DEVICE_TOKEN = ""
        const val DEFAULT_THRESHOLD = 0.30f
    }
}

class AppSettings(private val context: Context) {
    private val keyApi = stringPreferencesKey("api_base_url")
    private val keyDeviceId = stringPreferencesKey("device_id")
    private val keyToken = stringPreferencesKey("device_token")
    private val keyThreshold = floatPreferencesKey("threshold")
    private val keyCameraAttendanceMode = booleanPreferencesKey("camera_attendance_mode")

    val configFlow: Flow<KioskConfig> = context.dataStore.data.map { prefs ->
        KioskConfig(
            apiBaseUrl = prefs[keyApi]?.ifBlank { null } ?: KioskConfig.DEFAULT_API_URL,
            deviceId = prefs[keyDeviceId]?.ifBlank { null } ?: KioskConfig.DEFAULT_DEVICE_ID,
            deviceToken = prefs[keyToken]?.ifBlank { null } ?: KioskConfig.DEFAULT_DEVICE_TOKEN,
            threshold = prefs[keyThreshold] ?: KioskConfig.DEFAULT_THRESHOLD,
            cameraAttendanceMode = prefs[keyCameraAttendanceMode] ?: false,
        )
    }

    suspend fun current(): KioskConfig = configFlow.first()

    suspend fun save(config: KioskConfig) {
        context.dataStore.edit { prefs ->
            prefs[keyApi] = config.apiBaseUrl.trim().trimEnd('/').ifBlank { KioskConfig.DEFAULT_API_URL }
            prefs[keyDeviceId] = config.deviceId.trim()
            prefs[keyToken] = config.deviceToken.trim()
            prefs[keyThreshold] = config.threshold
            prefs[keyCameraAttendanceMode] = config.cameraAttendanceMode
        }
    }
}
