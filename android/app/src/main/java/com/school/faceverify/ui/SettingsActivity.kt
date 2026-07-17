package com.school.faceverify.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.data.KioskConfig
import com.school.faceverify.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val cfg = FaceVerifyApp.instance.settings.configFlow.first()
            binding.inputApiUrl.setText(cfg.apiBaseUrl)
            binding.inputDeviceId.setText(cfg.deviceId)
            binding.inputDeviceToken.setText(cfg.deviceToken)
            binding.inputThreshold.setText(cfg.threshold.toString())
        }

        binding.btnSave.setOnClickListener {
            val threshold = binding.inputThreshold.text?.toString()?.toFloatOrNull()
                ?: KioskConfig.DEFAULT_THRESHOLD
            val cfg = KioskConfig(
                apiBaseUrl = binding.inputApiUrl.text?.toString()
                    ?.ifBlank { KioskConfig.DEFAULT_API_URL }
                    ?: KioskConfig.DEFAULT_API_URL,
                deviceId = binding.inputDeviceId.text?.toString()
                    ?.ifBlank { KioskConfig.DEFAULT_DEVICE_ID }
                    ?: KioskConfig.DEFAULT_DEVICE_ID,
                deviceToken = binding.inputDeviceToken.text?.toString()
                    ?.ifBlank { KioskConfig.DEFAULT_DEVICE_TOKEN }
                    ?: KioskConfig.DEFAULT_DEVICE_TOKEN,
                threshold = threshold,
                cameraAttendanceMode = false,
            )
            lifecycleScope.launch {
                FaceVerifyApp.instance.settings.save(cfg)
                Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.btnOpenRfid.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        statusJob?.cancel()
        statusJob = ConnectionStatus.startPolling(this) { online ->
            ConnectionStatus.bind(binding.connectionDot, binding.connectionStatus, online, this)
        }
    }

    override fun onPause() {
        statusJob?.cancel()
        statusJob = null
        super.onPause()
    }
}
