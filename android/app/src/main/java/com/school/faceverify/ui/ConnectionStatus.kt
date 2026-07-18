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
import com.school.faceverify.net.DeviceAuthResult
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

    enum class State {
        Checking,
        Connected,
        Offline,
        Unauthorized,
    }

    fun bind(dot: View, label: TextView, state: State, context: Context) {
        when (state) {
            State.Checking -> {
                label.text = context.getString(R.string.status_checking)
                setDot(dot, context, R.color.amber)
            }
            State.Connected -> {
                label.text = context.getString(R.string.status_connected)
                setDot(dot, context, R.color.pass)
            }
            State.Offline -> {
                label.text = context.getString(R.string.status_offline)
                setDot(dot, context, R.color.fail)
            }
            State.Unauthorized -> {
                label.text = context.getString(R.string.status_unauthorized)
                setDot(dot, context, R.color.fail)
            }
        }
    }

    /** Legacy Boolean binder — true/false/null map to Connected/Offline/Checking. */
    fun bind(dot: View, label: TextView, online: Boolean?, context: Context) {
        bind(
            dot,
            label,
            when (online) {
                null -> State.Checking
                true -> State.Connected
                false -> State.Offline
            },
            context,
        )
    }

    /**
     * Polls authenticated device credentials while the screen is visible.
     * /health alone is not used — it ignores device token.
     */
    fun startPolling(
        owner: LifecycleOwner,
        intervalMs: Long = 8_000L,
        onStatus: (State) -> Unit,
    ): Job {
        onStatus(State.Checking)
        return owner.lifecycleScope.launch {
            while (isActive) {
                val state = withContext(Dispatchers.IO) {
                    try {
                        val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                        when (
                            FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                                .verifyDeviceCredentials(cfg.deviceId)
                        ) {
                            DeviceAuthResult.Ok -> State.Connected
                            DeviceAuthResult.Offline -> State.Offline
                            is DeviceAuthResult.Failed -> State.Unauthorized
                        }
                    } catch (_: Exception) {
                        State.Offline
                    }
                }
                onStatus(state)
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
