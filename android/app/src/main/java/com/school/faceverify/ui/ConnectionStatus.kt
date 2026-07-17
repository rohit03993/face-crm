package com.school.faceverify.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared online / offline pill used across Home, Students, Enroll, Attendance. */
object ConnectionStatus {

    fun bind(dot: View, label: TextView, online: Boolean?, context: Context) {
        when (online) {
            null -> {
                label.text = context.getString(R.string.status_checking)
                setDot(dot, context, R.color.amber)
            }
            true -> {
                label.text = context.getString(R.string.status_connected)
                setDot(dot, context, R.color.pass)
            }
            false -> {
                label.text = context.getString(R.string.status_offline)
                setDot(dot, context, R.color.fail)
            }
        }
    }

    /**
     * Polls Face API health while the screen is visible.
     * Returns a Job the caller can cancel in onPause if needed.
     */
    fun startPolling(
        owner: LifecycleOwner,
        intervalMs: Long = 8_000L,
        onStatus: (Boolean?) -> Unit,
    ): Job {
        onStatus(null)
        return owner.lifecycleScope.launch {
            while (isActive) {
                val online = withContext(Dispatchers.IO) {
                    try {
                        val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                        FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).healthQuick()
                    } catch (_: Exception) {
                        false
                    }
                }
                onStatus(online)
                delay(intervalMs)
            }
        }
    }

    private fun setDot(dot: View, context: Context, colorRes: Int) {
        val color = ContextCompat.getColor(context, colorRes)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }
}
