package com.school.faceverify.ui

import android.graphics.RectF
import kotlin.math.max

/** Maps on-screen preview coordinates to camera bitmap coordinates (fillCenter). */
object FrameCoordinateMapper {
    fun mapViewRectToBitmap(
        viewRect: RectF,
        viewW: Int,
        viewH: Int,
        bitmapW: Int,
        bitmapH: Int,
    ): RectF {
        val scale = max(viewW.toFloat() / bitmapW, viewH.toFloat() / bitmapH)
        val offsetX = (viewW - bitmapW * scale) / 2f
        val offsetY = (viewH - bitmapH * scale) / 2f
        return RectF(
            (viewRect.left - offsetX) / scale,
            (viewRect.top - offsetY) / scale,
            (viewRect.right - offsetX) / scale,
            (viewRect.bottom - offsetY) / scale,
        )
    }
}
