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

            val threshold = binding.inputThreshold.text?.toString()?.toFloatOrNull()
                ?: KioskConfig.DEFAULT_THRESHOLD
            val cfg = KioskConfig(
                apiBaseUrl = url,
                deviceId = binding.inputDeviceId.text?.toString()?.trim().orEmpty(),
                deviceToken = binding.inputDeviceToken.text?.toString()?.trim().orEmpty(),
                threshold = threshold,
                cameraAttendanceMode = false,
            )
            lifecycleScope.launch {
                FaceVerifyApp.instance.settings.save(cfg)
                Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
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
