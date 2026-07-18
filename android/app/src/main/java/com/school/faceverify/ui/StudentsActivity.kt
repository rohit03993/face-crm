package com.school.faceverify.ui

import android.content.Intent
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
import com.school.faceverify.databinding.ActivityStudentsBinding
import com.school.faceverify.databinding.ItemStudentBinding
import com.school.faceverify.net.FaceApiClient
import com.school.faceverify.net.StudentListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudentsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStudentsBinding
    private val adapter = StudentAdapter(
        onEdit = { showEditOptions(it) },
        onDelete = { confirmDelete(it) },
    )
    private var statusJob: Job? = null
    private var apiOnline: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.studentsList.layoutManager = LinearLayoutManager(this)
        binding.studentsList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { refresh() }
        binding.btnAddStudent.setOnClickListener {
            if (apiOnline == false) {
                Toast.makeText(this, R.string.enroll_offline_block, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, EnrollActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        statusJob?.cancel()
        statusJob = ConnectionStatus.startPolling(this) { state ->
            apiOnline = when (state) {
                ConnectionStatus.State.Checking -> null
                ConnectionStatus.State.Connected -> true
                else -> false
            }
            ConnectionStatus.bind(binding.connectionDot, binding.connectionStatus, state, this)
        }
    }

    override fun onPause() {
        statusJob?.cancel()
        statusJob = null
        super.onPause()
    }

    private fun requireOnline(): Boolean {
        if (apiOnline == false) {
            Toast.makeText(this, R.string.enroll_offline_block, Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun showEditOptions(item: StudentListItem) {
        if (!requireOnline()) return
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(
                arrayOf(
                    getString(R.string.edit_details),
                    getString(R.string.update_face),
                ),
            ) { _, which ->
                when (which) {
                    0 -> showEditDetailsDialog(item)
                    1 -> openUpdateFace(item)
                }
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun showEditDetailsDialog(item: StudentListItem) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_student, null)
        val inputId = view.findViewById<TextInputEditText>(R.id.inputEditStudentId)
        val inputName = view.findViewById<TextInputEditText>(R.id.inputEditStudentName)
        inputId.setText(item.enrollmentNumber)
        inputName.setText(item.name)

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
            .setTitle(R.string.edit_details)
            .setView(view)
            .setPositiveButton(R.string.save_details) { _, _ ->
                val roll = inputId.text?.toString()?.trim().orEmpty()
                val name = inputName.text?.toString()?.trim().orEmpty()
                if (roll.isBlank() || name.isBlank()) {
                    Toast.makeText(this, "Name and roll are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveDetails(item.id, name, roll)
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun openUpdateFace(item: StudentListItem) {
        startActivity(
            Intent(this, EnrollActivity::class.java).apply {
                putExtra(EnrollActivity.EXTRA_EDIT_MODE, true)
                putExtra(EnrollActivity.EXTRA_STUDENT_ID, item.id)
                putExtra(EnrollActivity.EXTRA_ENROLLMENT, item.enrollmentNumber)
                putExtra(EnrollActivity.EXTRA_NAME, item.name)
            },
        )
    }

    private fun saveDetails(studentId: String, name: String, enrollment: String) {
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val (ok, body) = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).updateStudent(
                        studentId = studentId,
                        name = name,
                        enrollmentNumber = enrollment,
                    )
                }
                if (ok) {
                    Toast.makeText(this@StudentsActivity, R.string.student_updated, Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(
                        this@StudentsActivity,
                        body.take(120).ifBlank { "Update failed" },
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@StudentsActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(item: StudentListItem) {
        if (!requireOnline()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_student_title)
            .setMessage(
                getString(R.string.delete_student_message, item.name, item.enrollmentNumber),
            )
            .setPositiveButton(R.string.delete_student) { _, _ -> deleteStudent(item) }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun deleteStudent(item: StudentListItem) {
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val (ok, body) = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).deleteStudent(item.id)
                }
                if (ok) {
                    Toast.makeText(this@StudentsActivity, R.string.student_deleted, Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(
                        this@StudentsActivity,
                        body.take(120).ifBlank { "Delete failed" },
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@StudentsActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refresh() {
        binding.listSummary.text = getString(R.string.students_loading)
        binding.listLoading.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val students = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).listStudents()
                }
                adapter.submit(students)
                val enrolled = students.count { it.enrolled }
                binding.listSummary.text = getString(R.string.students_summary, enrolled, students.size)
                binding.emptyState.visibility = if (students.isEmpty()) View.VISIBLE else View.GONE
                binding.studentsList.visibility = if (students.isEmpty()) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                binding.listSummary.text = getString(R.string.students_offline_hint)
                binding.emptyState.visibility = View.GONE
                binding.studentsList.visibility = View.GONE
                binding.btnRetry.visibility = View.VISIBLE
                Toast.makeText(this@StudentsActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.listLoading.visibility = View.GONE
            }
        }
    }

    private class StudentAdapter(
        private val onEdit: (StudentListItem) -> Unit,
        private val onDelete: (StudentListItem) -> Unit,
    ) : RecyclerView.Adapter<StudentAdapter.Holder>() {
        private val items = mutableListOf<StudentListItem>()

        fun submit(next: List<StudentListItem>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemStudentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding, onEdit, onDelete)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        class Holder(
            private val binding: ItemStudentBinding,
            private val onEdit: (StudentListItem) -> Unit,
            private val onDelete: (StudentListItem) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: StudentListItem) {
                binding.studentName.text = item.name
                binding.studentRoll.text = item.enrollmentNumber
                val batch = item.batch?.takeIf { it.isNotBlank() }
                binding.studentMeta.text = when {
                    batch != null && item.enrolled -> "$batch · ${item.imageCount} angles"
                    batch != null -> batch
                    item.enrolled -> "${item.imageCount} face angles"
                    else -> "Face not enrolled yet"
                }
                binding.studentStatus.text = if (item.enrolled) {
                    binding.root.context.getString(R.string.status_ready)
                } else {
                    binding.root.context.getString(R.string.status_pending)
                }
                binding.btnEdit.setOnClickListener { onEdit(item) }
                binding.btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
