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
    // Empty by default — user must enter Face Platform URL in Settings.
    val apiBaseUrl: String = "",
    val deviceId: String = "",
    val deviceToken: String = "",
    val threshold: Float = DEFAULT_THRESHOLD,
    val cameraAttendanceMode: Boolean = false,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.30f
    }

    val hasFaceUrl: Boolean
        get() = apiBaseUrl.trim().isNotEmpty()
}

class AppSettings(private val context: Context) {
    private val keyApi = stringPreferencesKey("api_base_url")
    private val keyDeviceId = stringPreferencesKey("device_id")
    private val keyToken = stringPreferencesKey("device_token")
    private val keyThreshold = floatPreferencesKey("threshold")
    private val keyCameraAttendanceMode = booleanPreferencesKey("camera_attendance_mode")

    val configFlow: Flow<KioskConfig> = context.dataStore.data.map { prefs ->
        KioskConfig(
            apiBaseUrl = prefs[keyApi]?.trim().orEmpty(),
            deviceId = prefs[keyDeviceId]?.trim().orEmpty(),
            deviceToken = prefs[keyToken]?.trim().orEmpty(),
            threshold = prefs[keyThreshold] ?: KioskConfig.DEFAULT_THRESHOLD,
            cameraAttendanceMode = prefs[keyCameraAttendanceMode] ?: false,
        )
    }

    suspend fun current(): KioskConfig = configFlow.first()

    suspend fun save(config: KioskConfig) {
        context.dataStore.edit { prefs ->
            prefs[keyApi] = config.apiBaseUrl.trim().trimEnd('/')
            prefs[keyDeviceId] = config.deviceId.trim()
            prefs[keyToken] = config.deviceToken.trim()
            prefs[keyThreshold] = config.threshold
            prefs[keyCameraAttendanceMode] = config.cameraAttendanceMode
        }
    }
}
