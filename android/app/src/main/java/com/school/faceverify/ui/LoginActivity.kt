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
import com.school.faceverify.databinding.ActivityLoginBinding
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private var needsBootstrap = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.btnLogin.setOnClickListener { submit() }
        binding.btnDeviceSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val session = FaceVerifyApp.instance.settings.currentSession()
            if (session.isLoggedIn) {
                goHome()
                return@launch
            }
            refreshMode()
        }
    }

    private fun refreshMode() {
        lifecycleScope.launch {
            val cfg = FaceVerifyApp.instance.settings.configFlow.first()
            if (!cfg.hasDeviceAuth) {
                binding.loginTitle.text = getString(R.string.login_title)
                binding.loginSubtitle.text = getString(R.string.login_need_device)
                binding.btnLogin.isEnabled = false
                return@launch
            }
            binding.btnLogin.isEnabled = true
            try {
                val (bootstrap, _) = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).authStatus()
                }
                needsBootstrap = bootstrap
                if (bootstrap) {
                    binding.loginTitle.text = getString(R.string.login_bootstrap_title)
                    binding.loginSubtitle.text = getString(R.string.login_bootstrap_subtitle)
                    binding.nameLayout.visibility = View.VISIBLE
                    binding.btnLogin.text = getString(R.string.login_create_admin)
                } else {
                    binding.loginTitle.text = getString(R.string.login_title)
                    binding.loginSubtitle.text = getString(R.string.login_subtitle)
                    binding.nameLayout.visibility = View.GONE
                    binding.btnLogin.text = getString(R.string.login_action)
                }
            } catch (e: Exception) {
                binding.loginSubtitle.text = e.message ?: getString(R.string.login_need_device)
                Toast.makeText(this@LoginActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submit() {
        val email = binding.inputEmail.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, R.string.login_required_fields, Toast.LENGTH_SHORT).show()
            return
        }
        if (needsBootstrap && name.isBlank()) {
            Toast.makeText(this, R.string.login_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, R.string.login_password_short, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnLogin.isEnabled = false
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val client = FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                val result = withContext(Dispatchers.IO) {
                    if (needsBootstrap) {
                        client.bootstrapAdmin(email, password, name)
                    } else {
                        client.login(email, password)
                    }
                }
                FaceVerifyApp.instance.settings.saveSession(
                    UserSession(
                        userToken = result.userToken,
                        userId = result.user.id,
                        email = result.user.email,
                        name = result.user.name,
                        role = result.user.role,
                    ),
                )
                Toast.makeText(
                    this@LoginActivity,
                    result.message ?: getString(R.string.login_success),
                    Toast.LENGTH_SHORT,
                ).show()
                goHome()
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, e.message, Toast.LENGTH_LONG).show()
                binding.btnLogin.isEnabled = true
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }
}
