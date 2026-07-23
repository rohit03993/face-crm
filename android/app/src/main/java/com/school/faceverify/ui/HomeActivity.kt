package com.school.faceverify.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.data.UserSession
import com.school.faceverify.databinding.ActivityHomeBinding
import com.school.faceverify.face.ModelStore
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private var statusJob: Job? = null
    private var modelReady = false
    private var modelJob: Job? = null
    private var session: UserSession = UserSession()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.cardAttendance.setOnClickListener {
            if (!ensureModelReadyOrToast()) return@setOnClickListener
            startActivity(Intent(this, AttendanceActivity::class.java))
        }
        binding.cardStudents.setOnClickListener {
            if (!ensureModelReadyOrToast()) return@setOnClickListener
            startActivity(Intent(this, StudentsActivity::class.java))
        }
        binding.btnStaffUsers.setOnClickListener {
            startActivity(Intent(this, StaffUsersActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnLogout.setOnClickListener { logout() }

        prepareFaceModel()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val cfg = FaceVerifyApp.instance.settings.configFlow.first()
            session = FaceVerifyApp.instance.settings.currentSession()
            if (!cfg.hasDeviceAuth) {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
                return@launch
            }
            if (!session.isLoggedIn) {
                startActivity(Intent(this@HomeActivity, LoginActivity::class.java))
                finish()
                return@launch
            }
            applyRoleUi()
            refreshSummary()
        }
        if (!modelReady && !ModelStore.isReady(this)) {
            prepareFaceModel()
        }
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

    private fun applyRoleUi() {
        binding.modeBadge.text = getString(
            R.string.home_logged_in_as,
            session.name.ifBlank { session.email },
            session.role.replaceFirstChar { it.uppercase() },
        )
        binding.cardStudents.visibility = View.VISIBLE
        binding.btnStaffUsers.visibility = if (session.isAdmin) View.VISIBLE else View.GONE
        binding.btnSettings.visibility = if (session.isAdmin) View.VISIBLE else View.GONE
        binding.btnLogout.visibility = View.VISIBLE
    }

    private fun logout() {
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val token = session.userToken
                if (token.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).logout(token)
                    }
                }
            } catch (_: Exception) {
                // Still clear local session.
            }
            FaceVerifyApp.instance.settings.clearSession()
            startActivity(
                Intent(this@HomeActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
            finish()
        }
    }

    private fun ensureModelReadyOrToast(): Boolean {
        if (modelReady || ModelStore.isReady(this)) {
            modelReady = true
            return true
        }
        Toast.makeText(this, R.string.model_download_wait, Toast.LENGTH_LONG).show()
        prepareFaceModel()
        return false
    }

    private fun prepareFaceModel() {
        if (ModelStore.isReady(this)) {
            modelReady = true
            binding.modelDownloadPanel.visibility = View.GONE
            binding.cardAttendance.isEnabled = true
            binding.cardStudents.isEnabled = true
            return
        }
        if (modelJob?.isActive == true) return

        binding.modelDownloadPanel.visibility = View.VISIBLE
        binding.modelDownloadStatus.text = getString(R.string.model_download_preparing)
        binding.modelDownloadProgress.progress = 0
        binding.cardAttendance.isEnabled = false
        binding.cardStudents.isEnabled = false

        modelJob = lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                if (!cfg.hasFaceUrl) {
                    modelReady = false
                    binding.modelDownloadPanel.visibility = View.VISIBLE
                    binding.modelDownloadStatus.text = getString(R.string.face_url_required)
                    binding.cardAttendance.isEnabled = false
                    binding.cardStudents.isEnabled = false
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ModelStore.ensureReady(this@HomeActivity, cfg.apiBaseUrl) { pct ->
                        runOnUiThread {
                            binding.modelDownloadProgress.progress = pct
                            binding.modelDownloadStatus.text =
                                getString(R.string.model_download_progress, pct)
                        }
                    }
                }
                modelReady = true
                binding.modelDownloadStatus.text = getString(R.string.model_download_done)
                binding.modelDownloadPanel.visibility = View.GONE
                binding.cardAttendance.isEnabled = true
                binding.cardStudents.isEnabled = true
            } catch (e: Exception) {
                modelReady = false
                binding.modelDownloadStatus.text =
                    getString(R.string.model_download_failed, e.message ?: "error")
                binding.cardAttendance.isEnabled = false
                binding.cardStudents.isEnabled = false
                Toast.makeText(this@HomeActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshSummary() {
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val students = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).listStudents()
                }
                val ready = students.count { it.enrolled }
                val missing = students.size - ready
                binding.studentsSummary.text = getString(
                    R.string.students_summary,
                    ready,
                    missing,
                    students.size,
                )
            } catch (_: Exception) {
                binding.studentsSummary.text = getString(R.string.students_menu_hint)
            }
        }
    }
}
