package com.school.faceverify.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.databinding.ActivityHomeBinding
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardAttendance.setOnClickListener {
            startActivity(Intent(this, AttendanceActivity::class.java))
        }
        binding.cardStudents.setOnClickListener {
            startActivity(Intent(this, StudentsActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
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

    private fun refreshSummary() {
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val students = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).listStudents()
                }
                val enrolled = students.count { it.enrolled }
                binding.studentsSummary.text = getString(
                    R.string.students_summary,
                    enrolled,
                    students.size,
                )
            } catch (_: Exception) {
                binding.studentsSummary.text = getString(R.string.students_menu_hint)
            }
        }
    }
}
