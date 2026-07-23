package com.school.faceverify.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.data.AccessSession
import com.school.faceverify.data.DeviceMode
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
    private var deviceMode: DeviceMode = DeviceMode.KIOSK

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
            PinGate.requireStaff(this) {
                startActivity(Intent(this, StudentsActivity::class.java))
            }
        }
        binding.btnUnlockStaff.setOnClickListener {
            PinGate.requireStaff(this) {
                applyAccessUi()
                Toast.makeText(this, R.string.students_menu, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnLockSession.setOnClickListener {
            AccessSession.lock()
            applyAccessUi()
            Toast.makeText(this, R.string.home_lock_session, Toast.LENGTH_SHORT).show()
        }
        binding.btnSettings.setOnClickListener {
            PinGate.requireAdmin(this) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        prepareFaceModel()
    }

    override fun onResume() {
        super.onResume()
        refreshModeAndAccess()
        refreshSummary()
        // Retry download after user fixes Face URL in Settings.
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

    private fun refreshModeAndAccess() {
        lifecycleScope.launch {
            val cfg = FaceVerifyApp.instance.settings.configFlow.first()
            deviceMode = cfg.deviceMode
            if (deviceMode == DeviceMode.STAFF) {
                AccessSession.unlock(com.school.faceverify.data.AccessLevel.STAFF)
            }
            applyAccessUi()
        }
    }

    private fun applyAccessUi() {
        val staffOpen = deviceMode == DeviceMode.STAFF || AccessSession.hasStaff()
        binding.modeBadge.text = when {
            AccessSession.hasAdmin() -> getString(R.string.pin_admin_title)
            staffOpen && deviceMode == DeviceMode.KIOSK -> getString(R.string.pin_staff_title)
            deviceMode == DeviceMode.STAFF -> getString(R.string.home_mode_staff)
            else -> getString(R.string.home_mode_kiosk)
        }
        binding.cardStudents.visibility = if (staffOpen) View.VISIBLE else View.GONE
        binding.btnUnlockStaff.visibility =
            if (deviceMode == DeviceMode.KIOSK && !staffOpen) View.VISIBLE else View.GONE
        binding.btnLockSession.visibility =
            if (deviceMode == DeviceMode.KIOSK && AccessSession.hasStaff()) View.VISIBLE else View.GONE
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
