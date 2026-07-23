package com.school.faceverify.data

/**
 * Device roles for the kiosk app.
 *
 * - [DeviceMode.KIOSK]: gate tablet — attendance only until Staff/Admin PIN unlocks more.
 * - [DeviceMode.STAFF]: office tablet — Students always available; Settings still needs Admin PIN.
 */
enum class DeviceMode {
    KIOSK,
    STAFF,
}

enum class AccessLevel {
    NONE,
    STAFF,
    ADMIN,
}

/** In-memory unlock for the current app session (cleared when process dies). */
object AccessSession {
    @Volatile
    var level: AccessLevel = AccessLevel.NONE
        private set

    fun unlock(level: AccessLevel) {
        if (level.ordinal > this.level.ordinal) {
            this.level = level
        }
    }

    fun lock() {
        level = AccessLevel.NONE
    }

    fun hasStaff(): Boolean = level == AccessLevel.STAFF || level == AccessLevel.ADMIN

    fun hasAdmin(): Boolean = level == AccessLevel.ADMIN
}
