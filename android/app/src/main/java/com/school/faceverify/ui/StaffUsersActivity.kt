package com.school.faceverify.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.databinding.ActivityStaffUsersBinding
import com.school.faceverify.databinding.ItemAppUserBinding
import com.school.faceverify.net.AppUserInfo
import com.school.faceverify.net.FaceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StaffUsersActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStaffUsersBinding
    private val adapter = UserAdapter(
        onDeactivate = { confirmDeactivate(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStaffUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.usersList.layoutManager = LinearLayoutManager(this)
        binding.usersList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddStaff.setOnClickListener { showCreateDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            try {
                val settings = FaceVerifyApp.instance.settings
                val cfg = settings.configFlow.first()
                val session = settings.currentSession()
                if (!session.isAdmin) {
                    Toast.makeText(this@StaffUsersActivity, R.string.admin_only, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                val users = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).listAppUsers(session.userToken)
                }
                adapter.submit(users)
                binding.emptyState.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@StaffUsersActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCreateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_create_staff, null)
        val inputName = view.findViewById<TextInputEditText>(R.id.inputStaffName)
        val inputEmail = view.findViewById<TextInputEditText>(R.id.inputStaffEmail)
        val inputPassword = view.findViewById<TextInputEditText>(R.id.inputStaffPassword)

        AlertDialog.Builder(
            this,
            com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert,
        )
            .setTitle(R.string.staff_add)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = inputName.text?.toString()?.trim().orEmpty()
                val email = inputEmail.text?.toString()?.trim().orEmpty()
                val password = inputPassword.text?.toString().orEmpty()
                if (name.isBlank() || email.isBlank() || password.length < 6) {
                    Toast.makeText(this, R.string.staff_create_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                createStaff(name, email, password)
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun createStaff(name: String, email: String, password: String) {
        lifecycleScope.launch {
            try {
                val settings = FaceVerifyApp.instance.settings
                val cfg = settings.configFlow.first()
                val session = settings.currentSession()
                withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                        .createStaff(session.userToken, email, password, name)
                }
                Toast.makeText(this@StaffUsersActivity, R.string.staff_created, Toast.LENGTH_SHORT).show()
                refresh()
            } catch (e: Exception) {
                Toast.makeText(this@StaffUsersActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeactivate(user: AppUserInfo) {
        if (user.role.equals("admin", ignoreCase = true)) {
            Toast.makeText(this, R.string.staff_cannot_remove_admin, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.staff_deactivate)
            .setMessage(getString(R.string.staff_deactivate_message, user.name, user.email))
            .setPositiveButton(R.string.staff_deactivate) { _, _ -> deactivate(user) }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun deactivate(user: AppUserInfo) {
        lifecycleScope.launch {
            try {
                val settings = FaceVerifyApp.instance.settings
                val cfg = settings.configFlow.first()
                val session = settings.currentSession()
                withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                        .deactivateUser(session.userToken, user.id)
                }
                Toast.makeText(this@StaffUsersActivity, R.string.staff_deactivated, Toast.LENGTH_SHORT).show()
                refresh()
            } catch (e: Exception) {
                Toast.makeText(this@StaffUsersActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private class UserAdapter(
        private val onDeactivate: (AppUserInfo) -> Unit,
    ) : RecyclerView.Adapter<UserAdapter.Holder>() {
        private var items: List<AppUserInfo> = emptyList()

        fun submit(list: List<AppUserInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemAppUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding, onDeactivate)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        class Holder(
            private val binding: ItemAppUserBinding,
            private val onDeactivate: (AppUserInfo) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: AppUserInfo) {
                binding.userName.text = item.name
                binding.userEmail.text = item.email
                binding.userRole.text = item.role.replaceFirstChar { it.uppercase() }
                val isAdmin = item.role.equals("admin", ignoreCase = true)
                binding.btnDeactivate.visibility = if (isAdmin) View.GONE else View.VISIBLE
                binding.btnDeactivate.setOnClickListener { onDeactivate(item) }
            }
        }
    }
}
