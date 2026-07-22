package com.school.faceverify.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.databinding.ActivityStudentDetailBinding
import com.school.faceverify.net.FaceApiClient
import com.school.faceverify.net.FacePhotoLoader
import com.school.faceverify.net.StudentListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudentDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStudentDetailBinding
    private lateinit var student: StudentListItem
    private var apiClient: FaceApiClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        student = StudentListItem(
            id = intent.getStringExtra(EXTRA_STUDENT_ID).orEmpty(),
            enrollmentNumber = intent.getStringExtra(EXTRA_ENROLLMENT).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            batch = intent.getStringExtra(EXTRA_BATCH),
            enrolled = intent.getBooleanExtra(EXTRA_ENROLLED, false),
            imageCount = intent.getIntExtra(EXTRA_IMAGE_COUNT, 0),
            hasFacePhoto = intent.getBooleanExtra(EXTRA_HAS_PHOTO, false),
        )
        if (student.id.isBlank()) {
            finish()
            return
        }

        binding.studentName.text = student.name
        binding.studentRoll.text = student.enrollmentNumber
        binding.studentMeta.text = student.batch?.takeIf { it.isNotBlank() }
            ?: getString(R.string.students_synced_from_crm)
        binding.btnUpdateFace.text = if (student.enrolled) {
            getString(R.string.update_face)
        } else {
            getString(R.string.add_face)
        }
        applyStatusChip()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnUpdateFace.setOnClickListener { openFaceEnroll() }
        binding.btnEditDetails.setOnClickListener { showEditDetailsDialog() }
    }

    override fun onResume() {
        super.onResume()
        refreshFromServer()
    }

    private fun applyStatusChip() {
        if (student.enrolled) {
            binding.studentStatus.text = getString(R.string.status_ready)
            binding.studentStatus.setBackgroundResource(R.drawable.bg_status_ready)
            binding.studentStatus.setTextColor(ContextCompat.getColor(this, R.color.teal_bright))
        } else {
            binding.studentStatus.text = getString(R.string.status_missing_face)
            binding.studentStatus.setBackgroundResource(R.drawable.bg_status_missing)
            binding.studentStatus.setTextColor(ContextCompat.getColor(this, R.color.amber))
        }
    }

    private fun refreshFromServer() {
        binding.photoLoading.visibility = View.VISIBLE
        binding.photoEmptyHint.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val client = FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                apiClient = client

                val latest = withContext(Dispatchers.IO) {
                    client.listStudents().find { it.id == student.id }
                }
                if (latest != null) {
                    student = latest
                    binding.studentName.text = student.name
                    binding.studentRoll.text = student.enrollmentNumber
                    binding.btnUpdateFace.text = if (student.enrolled) {
                        getString(R.string.update_face)
                    } else {
                        getString(R.string.add_face)
                    }
                    applyStatusChip()
                }

                if (student.enrolled && student.hasFacePhoto) {
                    FacePhotoLoader.loadInto(
                        scope = lifecycleScope,
                        imageView = binding.profilePhoto,
                        client = client,
                        studentId = student.id,
                        placeholderRes = R.drawable.bg_photo_placeholder,
                    ) { loaded ->
                        binding.photoEmptyHint.visibility =
                            if (loaded) View.GONE else View.VISIBLE
                        binding.photoLoading.visibility = View.GONE
                    }
                } else {
                    binding.profilePhoto.setImageResource(R.drawable.bg_photo_placeholder)
                    binding.photoEmptyHint.visibility = View.VISIBLE
                    binding.photoLoading.visibility = View.GONE
                }
            } catch (e: Exception) {
                binding.photoLoading.visibility = View.GONE
                binding.photoEmptyHint.visibility = View.VISIBLE
                Toast.makeText(this@StudentDetailActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFaceEnroll() {
        startActivity(
            Intent(this, EnrollActivity::class.java).apply {
                putExtra(EnrollActivity.EXTRA_STUDENT_ID, student.id)
                putExtra(EnrollActivity.EXTRA_ENROLLMENT, student.enrollmentNumber)
                putExtra(EnrollActivity.EXTRA_NAME, student.name)
                putExtra(EnrollActivity.EXTRA_FACE_ONLY, true)
                putExtra(EnrollActivity.EXTRA_EDIT_MODE, student.enrolled)
            },
        )
    }

    private fun showEditDetailsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_edit_student, null)
        val inputId = view.findViewById<TextInputEditText>(R.id.inputEditStudentId)
        val inputName = view.findViewById<TextInputEditText>(R.id.inputEditStudentName)
        inputId.setText(student.enrollmentNumber)
        inputName.setText(student.name)

        androidx.appcompat.app.AlertDialog.Builder(
            this,
            com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert,
        )
            .setTitle(R.string.edit_details)
            .setView(view)
            .setPositiveButton(R.string.save_details) { _, _ ->
                val roll = inputId.text?.toString()?.trim().orEmpty()
                val name = inputName.text?.toString()?.trim().orEmpty()
                if (roll.isBlank() || name.isBlank()) {
                    Toast.makeText(this, "Name and roll are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveDetails(name, roll)
            }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun saveDetails(name: String, enrollment: String) {
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val (ok, body) = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).updateStudent(
                        studentId = student.id,
                        name = name,
                        enrollmentNumber = enrollment,
                    )
                }
                if (ok) {
                    Toast.makeText(this@StudentDetailActivity, R.string.student_updated, Toast.LENGTH_SHORT).show()
                    student = student.copy(name = name, enrollmentNumber = enrollment)
                    binding.studentName.text = name
                    binding.studentRoll.text = enrollment
                } else {
                    Toast.makeText(
                        this@StudentDetailActivity,
                        body.take(120).ifBlank { "Update failed" },
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@StudentDetailActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_STUDENT_ID = "student_id"
        const val EXTRA_ENROLLMENT = "enrollment"
        const val EXTRA_NAME = "name"
        const val EXTRA_BATCH = "batch"
        const val EXTRA_ENROLLED = "enrolled"
        const val EXTRA_HAS_PHOTO = "has_face_photo"
        const val EXTRA_IMAGE_COUNT = "image_count"

        fun intentFor(parent: android.content.Context, item: StudentListItem): Intent =
            Intent(parent, StudentDetailActivity::class.java).apply {
                putExtra(EXTRA_STUDENT_ID, item.id)
                putExtra(EXTRA_ENROLLMENT, item.enrollmentNumber)
                putExtra(EXTRA_NAME, item.name)
                putExtra(EXTRA_BATCH, item.batch)
                putExtra(EXTRA_ENROLLED, item.enrolled)
                putExtra(EXTRA_HAS_PHOTO, item.hasFacePhoto)
                putExtra(EXTRA_IMAGE_COUNT, item.imageCount)
            }
    }
}
