package com.school.faceverify.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.data.AccessLevel
import com.school.faceverify.data.AccessSession
import com.school.faceverify.data.DeviceMode
import com.school.faceverify.data.KioskConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object PinGate {
    fun requireStaff(activity: AppCompatActivity, onGranted: () -> Unit) {
        activity.lifecycleScope.launch {
            val cfg = FaceVerifyApp.instance.settings.configFlow.first()
            if (cfg.deviceMode == DeviceMode.STAFF || AccessSession.hasStaff()) {
                onGranted()
                return@launch
            }
            prompt(
                activity = activity,
                titleRes = R.string.pin_staff_title,
                hintRes = R.string.pin_staff_hint,
                required = AccessLevel.STAFF,
                config = cfg,
                onGranted = onGranted,
            )
        }
    }

    fun requireAdmin(activity: AppCompatActivity, onGranted: () -> Unit) {
        if (AccessSession.hasAdmin()) {
            onGranted()
            return
        }
        activity.lifecycleScope.launch {
            val cfg = FaceVerifyApp.instance.settings.configFlow.first()
            prompt(
                activity = activity,
                titleRes = R.string.pin_admin_title,
                hintRes = R.string.pin_admin_hint,
                required = AccessLevel.ADMIN,
                config = cfg,
                onGranted = onGranted,
            )
        }
    }

    private fun prompt(
        activity: AppCompatActivity,
        titleRes: Int,
        hintRes: Int,
        required: AccessLevel,
        config: KioskConfig,
        onGranted: () -> Unit,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_pin, null)
        val hint = view.findViewById<android.widget.TextView>(R.id.pinHint)
        val input = view.findViewById<TextInputEditText>(R.id.inputPin)
        hint.setText(hintRes)

        val dialog = AlertDialog.Builder(
            activity,
            com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert,
        )
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(R.string.pin_unlock, null)
            .setNegativeButton(R.string.back, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString().orEmpty()
                val ok = when (required) {
                    AccessLevel.ADMIN -> config.matchesAdminPin(pin)
                    AccessLevel.STAFF ->
                        config.matchesStaffPin(pin) || config.matchesAdminPin(pin)
                    AccessLevel.NONE -> true
                }
                if (!ok) {
                    Toast.makeText(activity, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AccessSession.unlock(required)
                if (required == AccessLevel.STAFF && config.matchesAdminPin(pin)) {
                    AccessSession.unlock(AccessLevel.ADMIN)
                }
                dialog.dismiss()
                onGranted()
            }
        }
        dialog.show()
    }

    fun modeLabel(context: Context, mode: DeviceMode): String =
        when (mode) {
            DeviceMode.KIOSK -> context.getString(R.string.device_mode_kiosk)
            DeviceMode.STAFF -> context.getString(R.string.device_mode_staff)
        }
}
