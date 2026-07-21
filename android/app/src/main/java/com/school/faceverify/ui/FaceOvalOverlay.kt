package com.school.faceverify.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.school.faceverify.R

class FaceOvalOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class OvalState {
        IDLE,
        DETECTED,
        ALIGN,
        HOLDING,
        VERIFYING,
        SUCCESS,
        FAIL,
    }

    var ovalState: OvalState = OvalState.IDLE
        set(value) {
            if (field != value) {
                field = value
                strokePaint.color = strokeColorFor(value)
                invalidate()
            }
        }

    var holdProgress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    private val ovalRect = RectF()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        color = strokeColorFor(OvalState.IDLE)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.teal_bright)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f * resources.displayMetrics.density
        alpha = 48
        color = ContextCompat.getColor(context, R.color.teal_bright)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val pad = strokePaint.strokeWidth
        ovalRect.set(pad, pad, width - pad, height - pad)

        if (ovalState == OvalState.HOLDING && holdProgress > 0f) {
            canvas.drawOval(ovalRect, glowPaint)
            canvas.drawArc(
                ovalRect,
                -90f,
                360f * holdProgress,
                false,
                progressPaint,
            )
        }

        canvas.drawOval(ovalRect, strokePaint)
    }

    private fun strokeColorFor(state: OvalState): Int {
        val colorRes = when (state) {
            OvalState.IDLE -> R.color.oval_stroke_dim
            OvalState.DETECTED -> R.color.amber
            OvalState.ALIGN -> R.color.amber
            OvalState.HOLDING -> R.color.teal_bright
            OvalState.VERIFYING -> R.color.verify
            OvalState.SUCCESS -> R.color.pass
            OvalState.FAIL -> R.color.fail
        }
        return ContextCompat.getColor(context, colorRes)
    }

    fun pulseAlpha(): Float = when (ovalState) {
        OvalState.IDLE -> 0.55f
        OvalState.DETECTED, OvalState.ALIGN -> 0.9f
        else -> 1f
    }

    fun applyAlpha() {
        alpha = pulseAlpha()
    }
}
