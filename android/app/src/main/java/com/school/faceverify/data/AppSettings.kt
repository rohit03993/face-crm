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
    val deviceMode: DeviceMode = DeviceMode.KIOSK,
    val staffPin: String = DEFAULT_STAFF_PIN,
    val adminPin: String = DEFAULT_ADMIN_PIN,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.30f
        const val DEFAULT_STAFF_PIN = "1234"
        const val DEFAULT_ADMIN_PIN = "9999"
    }

    val hasFaceUrl: Boolean
        get() = apiBaseUrl.trim().isNotEmpty()

    fun matchesStaffPin(pin: String): Boolean =
        pin.trim() == staffPin.trim().ifBlank { DEFAULT_STAFF_PIN }

    fun matchesAdminPin(pin: String): Boolean =
        pin.trim() == adminPin.trim().ifBlank { DEFAULT_ADMIN_PIN }
}

class AppSettings(private val context: Context) {
    private val keyApi = stringPreferencesKey("api_base_url")
    private val keyDeviceId = stringPreferencesKey("device_id")
    private val keyToken = stringPreferencesKey("device_token")
    private val keyThreshold = floatPreferencesKey("threshold")
    private val keyCameraAttendanceMode = booleanPreferencesKey("camera_attendance_mode")
    private val keyDeviceMode = stringPreferencesKey("device_mode")
    private val keyStaffPin = stringPreferencesKey("staff_pin")
    private val keyAdminPin = stringPreferencesKey("admin_pin")

    val configFlow: Flow<KioskConfig> = context.dataStore.data.map { prefs ->
        KioskConfig(
            apiBaseUrl = prefs[keyApi]?.trim().orEmpty(),
            deviceId = prefs[keyDeviceId]?.trim().orEmpty(),
            deviceToken = prefs[keyToken]?.trim().orEmpty(),
            threshold = prefs[keyThreshold] ?: KioskConfig.DEFAULT_THRESHOLD,
            cameraAttendanceMode = prefs[keyCameraAttendanceMode] ?: false,
            deviceMode = when (prefs[keyDeviceMode]) {
                DeviceMode.STAFF.name -> DeviceMode.STAFF
                else -> DeviceMode.KIOSK
            },
            staffPin = prefs[keyStaffPin]?.trim()?.ifBlank { null }
                ?: KioskConfig.DEFAULT_STAFF_PIN,
            adminPin = prefs[keyAdminPin]?.trim()?.ifBlank { null }
                ?: KioskConfig.DEFAULT_ADMIN_PIN,
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
            prefs[keyDeviceMode] = config.deviceMode.name
            prefs[keyStaffPin] = config.staffPin.trim().ifBlank { KioskConfig.DEFAULT_STAFF_PIN }
            prefs[keyAdminPin] = config.adminPin.trim().ifBlank { KioskConfig.DEFAULT_ADMIN_PIN }
        }
    }
}
