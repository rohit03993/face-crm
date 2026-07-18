package com.school.faceverify.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.data.KioskConfig
import com.school.faceverify.databinding.ActivitySettingsBinding
import com.school.faceverify.net.DeviceAuthResult
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        lifecycleScope.launch {
            val cfg = FaceVerifyApp.instance.settings.configFlow.first()
            binding.inputApiUrl.setText(cfg.apiBaseUrl)
            binding.inputDeviceId.setText(cfg.deviceId)
            binding.inputDeviceToken.setText(cfg.deviceToken)
            binding.inputThreshold.setText(cfg.threshold.toString())
        }

        binding.btnSave.setOnClickListener {
            val url = binding.inputApiUrl.text?.toString()?.trim().orEmpty().trimEnd('/')
            if (url.isBlank()) {
                Toast.makeText(this, "Enter Face Platform URL", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, "URL must start with https://", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val deviceId = binding.inputDeviceId.text?.toString()?.trim().orEmpty()
            val deviceToken = binding.inputDeviceToken.text?.toString()?.trim().orEmpty()
            if (deviceId.isBlank()) {
                Toast.makeText(this, "Enter device ID", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (deviceToken.isBlank()) {
                Toast.makeText(this, "Enter device token", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val threshold = binding.inputThreshold.text?.toString()?.toFloatOrNull()
                ?: KioskConfig.DEFAULT_THRESHOLD
            val cfg = KioskConfig(
                apiBaseUrl = url,
                deviceId = deviceId,
                deviceToken = deviceToken,
                threshold = threshold,
                cameraAttendanceMode = false,
            )

            binding.btnSave.isEnabled = false
            Toast.makeText(this, R.string.settings_verifying, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val auth = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                        .verifyDeviceCredentials(cfg.deviceId)
                }
                when (auth) {
                    DeviceAuthResult.Ok -> {
                        FaceVerifyApp.instance.settings.save(cfg)
                        Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    DeviceAuthResult.Offline -> {
                        binding.btnSave.isEnabled = true
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.settings_verify_offline,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is DeviceAuthResult.Failed -> {
                        binding.btnSave.isEnabled = true
                        Toast.makeText(this@SettingsActivity, auth.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.btnOpenRfid.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        statusJob?.cancel()
        statusJob = ConnectionStatus.startPolling(this) { state ->
            ConnectionStatus.bind(binding.connectionDot, binding.connectionStatus, state, this)
        }
    }

    override fun onPause() {
        statusJob?.cancel()
        statusJob = null
        super.onPause()
    }
}
