package com.school.faceverify.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.databinding.ActivityStudentsBinding
import com.school.faceverify.databinding.ItemStudentBinding
import com.school.faceverify.net.FaceApiClient
import com.school.faceverify.net.FacePhotoLoader
import com.school.faceverify.net.StudentListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudentsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SUBJECT = "subject"
        const val SUBJECT_STUDENT = "student"
        const val SUBJECT_STAFF = "staff"

        fun intentFor(context: android.content.Context, subject: String = SUBJECT_STUDENT): Intent {
            return Intent(context, StudentsActivity::class.java).putExtra(EXTRA_SUBJECT, subject)
        }
    }

    private lateinit var binding: ActivityStudentsBinding
    private val listSubject: String by lazy {
        intent.getStringExtra(EXTRA_SUBJECT)?.lowercase()?.trim().orEmpty().ifBlank { SUBJECT_STUDENT }
    }
    private val isStaffMode: Boolean get() = listSubject == SUBJECT_STAFF
    private val adapter = StudentAdapter(
        onOpenProfile = { openProfile(it) },
        onAddFace = { openAddFace(it) },
        onMore = { showMoreOptions(it) },
        photoLoader = { imageView, item -> loadStudentPhoto(imageView, item) },
    )
    private var statusJob: Job? = null
    private var searchJob: Job? = null
    private var apiOnline: Boolean? = null
    private var allStudents = emptyList<StudentListItem>()
    private var currentFilter = FaceFilter.ALL
    private var selectedBatch: String? = null
    private var batchLabels = listOf<String>()
    private var updatingBatchSpinner = false

    private enum class FaceFilter { ALL, MISSING, READY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.studentsList.layoutManager = LinearLayoutManager(this)
        binding.studentsList.adapter = adapter
        binding.studentsList.setHasFixedSize(true)
        binding.studentsList.itemAnimator = null

        binding.screenTitle.setText(if (isStaffMode) R.string.staff_faces_menu else R.string.students_menu)
        binding.crmHint.setText(if (isStaffMode) R.string.staff_faces_crm_hint else R.string.students_crm_hint)
        binding.searchInput.hint = getString(
            if (isStaffMode) R.string.staff_faces_search_hint else R.string.students_search_hint,
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { refresh() }
        binding.btnShowMissing.setOnClickListener { showMissingOnly() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(200)
                    applyFilterAndSubmit()
                }
            }
        })

        binding.batchSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingBatchSpinner) return
                selectedBatch = if (position <= 0) null else batchLabels.getOrNull(position)
                applyFilterAndSubmit()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.chipAll.setOnClickListener { setFilter(FaceFilter.ALL) }
        binding.chipMissing.setOnClickListener { setFilter(FaceFilter.MISSING) }
        binding.chipReady.setOnClickListener { setFilter(FaceFilter.READY) }
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

    private fun setFilter(filter: FaceFilter) {
        currentFilter = filter
        when (filter) {
            FaceFilter.ALL -> binding.chipAll.isChecked = true
            FaceFilter.MISSING -> binding.chipMissing.isChecked = true
            FaceFilter.READY -> binding.chipReady.isChecked = true
        }
        applyFilterAndSubmit()
    }

    private fun showMissingOnly() {
        binding.searchInput.text?.clear()
        setFilter(FaceFilter.MISSING)
        binding.studentsList.scrollToPosition(0)
    }

    private fun requireOnline(): Boolean {
        if (apiOnline == false) {
            Toast.makeText(this, R.string.enroll_offline_block, Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun requireAdmin(action: () -> Unit) {
        lifecycleScope.launch {
            val session = FaceVerifyApp.instance.settings.currentSession()
            if (!session.isAdmin) {
                Toast.makeText(this@StudentsActivity, R.string.admin_only, Toast.LENGTH_LONG).show()
                return@launch
            }
            action()
        }
    }

    private fun openProfile(item: StudentListItem) {
        startActivity(StudentDetailActivity.intentFor(this, item))
    }

    private fun loadStudentPhoto(
        imageView: android.widget.ImageView,
        item: StudentListItem,
    ) {
        if (!item.enrolled || !item.hasFacePhoto) {
            FacePhotoLoader.cancel(imageView)
            imageView.setImageResource(R.drawable.bg_photo_placeholder)
            return
        }
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val client = FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                FacePhotoLoader.loadInto(
                    scope = lifecycleScope,
                    imageView = imageView,
                    client = client,
                    studentId = item.id,
                    placeholderRes = R.drawable.bg_photo_placeholder,
                )
            } catch (_: Exception) {
                imageView.setImageResource(R.drawable.bg_photo_placeholder)
            }
        }
    }

    private fun openAddFace(item: StudentListItem) {
        if (!requireOnline()) return
        startActivity(
            Intent(this, EnrollActivity::class.java).apply {
                putExtra(EnrollActivity.EXTRA_STUDENT_ID, item.id)
                putExtra(EnrollActivity.EXTRA_ENROLLMENT, item.enrollmentNumber)
                putExtra(EnrollActivity.EXTRA_NAME, item.name)
                putExtra(EnrollActivity.EXTRA_FACE_ONLY, true)
                putExtra(EnrollActivity.EXTRA_EDIT_MODE, item.enrolled)
            },
        )
    }

    private fun showMoreOptions(item: StudentListItem) {
        if (!requireOnline()) return
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(
                arrayOf(
                    getString(R.string.edit_details),
                    if (item.enrolled) getString(R.string.update_face) else getString(R.string.add_face),
                    getString(R.string.delete_student),
                ),
            ) { _, which ->
                when (which) {
                    0 -> requireAdmin { showEditDetailsDialog(item) }
                    1 -> openAddFace(item)
                    2 -> requireAdmin { confirmDelete(item) }
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

    private fun saveDetails(studentId: String, name: String, enrollment: String) {
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val session = FaceVerifyApp.instance.settings.currentSession()
                if (!session.isAdmin || session.userToken.isBlank()) {
                    Toast.makeText(this@StudentsActivity, R.string.admin_only, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val (ok, body) = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).updateStudent(
                        studentId = studentId,
                        name = name,
                        enrollmentNumber = enrollment,
                        userToken = session.userToken,
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
                val session = FaceVerifyApp.instance.settings.currentSession()
                if (!session.isAdmin || session.userToken.isBlank()) {
                    Toast.makeText(this@StudentsActivity, R.string.admin_only, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val (ok, body) = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                        .deleteStudent(item.id, session.userToken)
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
        binding.listSummary.text = getString(
            if (isStaffMode) R.string.staff_faces_loading else R.string.students_loading,
        )
        binding.listLoading.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val students = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).listStudents(listSubject)
                }
                allStudents = students.sortedWith(
                    compareBy<StudentListItem> { it.enrolled }.thenBy { it.name.lowercase() },
                )
                refreshBatchSpinner()
                updateSummary()
                applyFilterAndSubmit()
            } catch (e: Exception) {
                binding.listSummary.text = getString(R.string.students_offline_hint)
                binding.emptyState.visibility = View.GONE
                binding.studentsList.visibility = View.GONE
                binding.btnRetry.visibility = View.VISIBLE
                binding.listFilteredCount.visibility = View.GONE
                Toast.makeText(this@StudentsActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.listLoading.visibility = View.GONE
            }
        }
    }

    private fun refreshBatchSpinner() {
        val batches = allStudents
            .mapNotNull { it.batch?.trim()?.takeIf { b -> b.isNotEmpty() } }
            .distinct()
            .sortedBy { it.lowercase() }
        val previous = selectedBatch
        batchLabels = listOf(
            getString(if (isStaffMode) R.string.staff_faces_batch_all else R.string.students_batch_all),
        ) + batches
        updatingBatchSpinner = true
        binding.batchSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            batchLabels,
        )
        val index = previous?.let { batches.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0
        selectedBatch = if (index > 0) batches[index - 1] else null
        binding.batchSpinner.setSelection(index, false)
        binding.batchSpinner.visibility = if (batches.isEmpty()) View.GONE else View.VISIBLE
        updatingBatchSpinner = false
    }

    private fun updateSummary() {
        val ready = allStudents.count { it.enrolled }
        val missing = allStudents.size - ready
        binding.listSummary.text = getString(
            R.string.students_summary_full,
            ready,
            missing,
            allStudents.size,
        )
        binding.btnShowMissing.text = if (missing > 0) {
            getString(R.string.students_show_missing_count, missing)
        } else {
            getString(R.string.students_show_missing)
        }
        binding.btnShowMissing.visibility = if (missing > 0) View.VISIBLE else View.GONE
    }

    private fun applyFilterAndSubmit() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty().lowercase()
        val batch = selectedBatch
        val filtered = allStudents.filter { item ->
            val matchesFilter = when (currentFilter) {
                FaceFilter.ALL -> true
                FaceFilter.MISSING -> !item.enrolled
                FaceFilter.READY -> item.enrolled
            }
            val matchesBatch = batch == null ||
                item.batch?.trim().equals(batch, ignoreCase = true) == true
            val matchesQuery = query.isEmpty() ||
                item.name.lowercase().contains(query) ||
                item.enrollmentNumber.lowercase().contains(query)
            matchesFilter && matchesBatch && matchesQuery
        }
        adapter.submitList(filtered)
        binding.emptyState.text = when {
            allStudents.isEmpty() -> getString(
                if (isStaffMode) R.string.staff_faces_empty else R.string.students_empty,
            )
            query.isNotEmpty() -> getString(R.string.students_no_search_results, query)
            batch != null && filtered.isEmpty() -> getString(R.string.students_no_batch_match)
            currentFilter == FaceFilter.MISSING -> getString(R.string.students_no_missing)
            currentFilter == FaceFilter.READY -> getString(R.string.students_no_ready)
            else -> getString(if (isStaffMode) R.string.staff_faces_empty else R.string.students_empty)
        }
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.studentsList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        binding.listFilteredCount.visibility =
            if (filtered.size != allStudents.size) View.VISIBLE else View.GONE
        binding.listFilteredCount.text = getString(R.string.students_filtered_count, filtered.size)
    }

    private class StudentAdapter(
        private val onOpenProfile: (StudentListItem) -> Unit,
        private val onAddFace: (StudentListItem) -> Unit,
        private val onMore: (StudentListItem) -> Unit,
        private val photoLoader: (android.widget.ImageView, StudentListItem) -> Unit,
    ) : ListAdapter<StudentListItem, StudentAdapter.Holder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemStudentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding, onOpenProfile, onAddFace, onMore, photoLoader)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onViewRecycled(holder: Holder) {
            holder.onRecycled()
            super.onViewRecycled(holder)
        }

        class Holder(
            private val binding: ItemStudentBinding,
            private val onOpenProfile: (StudentListItem) -> Unit,
            private val onAddFace: (StudentListItem) -> Unit,
            private val onMore: (StudentListItem) -> Unit,
            private val photoLoader: (android.widget.ImageView, StudentListItem) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun onRecycled() {
                FacePhotoLoader.cancel(binding.studentPhoto)
            }

            fun bind(item: StudentListItem) {
                val ctx = binding.root.context
                binding.studentName.text = item.name
                binding.studentRoll.text = item.enrollmentNumber
                binding.studentMeta.text = item.batch?.takeIf { it.isNotBlank() }
                    ?: ctx.getString(R.string.students_synced_from_crm)

                if (item.enrolled) {
                    binding.studentStatus.text = ctx.getString(R.string.status_ready)
                    binding.studentStatus.setBackgroundResource(R.drawable.bg_status_ready)
                    binding.studentStatus.setTextColor(ContextCompat.getColor(ctx, R.color.teal_bright))
                    binding.btnFaceAction.text = ctx.getString(R.string.update_face)
                } else {
                    binding.studentStatus.text = ctx.getString(R.string.status_missing_face)
                    binding.studentStatus.setBackgroundResource(R.drawable.bg_status_missing)
                    binding.studentStatus.setTextColor(ContextCompat.getColor(ctx, R.color.amber))
                    binding.btnFaceAction.text = ctx.getString(R.string.add_face)
                }

                photoLoader(binding.studentPhoto, item)
                binding.btnFaceAction.setOnClickListener { onAddFace(item) }
                binding.btnMore.setOnClickListener { onMore(item) }
                binding.root.setOnClickListener { onOpenProfile(item) }
                binding.studentPhoto.setOnClickListener { onOpenProfile(item) }
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<StudentListItem>() {
                override fun areItemsTheSame(a: StudentListItem, b: StudentListItem) = a.id == b.id
                override fun areContentsTheSame(a: StudentListItem, b: StudentListItem) = a == b
            }
        }
    }
}
