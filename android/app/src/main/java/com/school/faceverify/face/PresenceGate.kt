package com.school.faceverify.face

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs

data class PresenceReading(
    val yaw: Float,
    val pitch: Float,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val faceBounds: Rect,
    val hasLandmarks: Boolean,
)

enum class PresenceIssue {
    NONE,
    NO_FACE,
    OFF_CENTER,
    TOO_SMALL,
    WRONG_POSE,
    EYES_CLOSED,
}

/** Requires intentional face presence before attendance identify runs. */
object PresenceGate {
    const val HOLD_MS = 1000L
    private const val MAX_YAW = 15f
    private const val MAX_PITCH = 15f
    private const val MIN_EYE_OPEN = 0.45f
    private const val MIN_FACE_WIDTH_RATIO = 0.20f
    private const val MAX_CENTER_OFFSET_RATIO = 0.24f
    private const val MIN_OVAL_FACE_RATIO = 0.32f

    fun check(
        reading: PresenceReading?,
        bitmapW: Int,
        bitmapH: Int,
        ovalInBitmap: RectF?,
    ): PresenceIssue {
        if (reading == null || !reading.hasLandmarks) return PresenceIssue.NO_FACE

        val bounds = reading.faceBounds
        val faceW = bounds.width().toFloat()
        val faceWRatio = faceW / bitmapW
        if (faceWRatio < MIN_FACE_WIDTH_RATIO) return PresenceIssue.TOO_SMALL

        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        if (ovalInBitmap != null) {
            if (!ovalInBitmap.contains(cx, cy)) return PresenceIssue.OFF_CENTER
            if (faceW < ovalInBitmap.width() * MIN_OVAL_FACE_RATIO) return PresenceIssue.TOO_SMALL
        } else {
            val offsetX = abs(cx - bitmapW / 2f) / bitmapW
            val offsetY = abs(cy - bitmapH / 2f) / bitmapH
            if (offsetX > MAX_CENTER_OFFSET_RATIO || offsetY > MAX_CENTER_OFFSET_RATIO) {
                return PresenceIssue.OFF_CENTER
            }
        }

        if (abs(reading.yaw) > MAX_YAW || abs(reading.pitch) > MAX_PITCH) {
            return PresenceIssue.WRONG_POSE
        }

        val left = reading.leftEyeOpen
        val right = reading.rightEyeOpen
        if (left != null && right != null) {
            if (left < MIN_EYE_OPEN || right < MIN_EYE_OPEN) return PresenceIssue.EYES_CLOSED
        }

        return PresenceIssue.NONE
    }
}
