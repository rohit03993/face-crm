package com.school.faceverify.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.databinding.ActivityStudentsBinding
import com.school.faceverify.databinding.ItemStudentBinding
import com.school.faceverify.net.FaceApiClient
import com.school.faceverify.net.StudentListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudentsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStudentsBinding
    private val adapter = StudentAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.studentsList.layoutManager = LinearLayoutManager(this)
        binding.studentsList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddStudent.setOnClickListener {
            startActivity(Intent(this, EnrollActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.listSummary.text = getString(R.string.students_loading)
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
                binding.listSummary.text = getString(R.string.students_load_failed)
                Toast.makeText(this@StudentsActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private class StudentAdapter : RecyclerView.Adapter<StudentAdapter.Holder>() {
        private val items = mutableListOf<StudentListItem>()

        fun submit(next: List<StudentListItem>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemStudentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        class Holder(private val binding: ItemStudentBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: StudentListItem) {
                binding.studentName.text = item.name
                binding.studentRoll.text = item.enrollmentNumber
                val batch = item.batch?.takeIf { it.isNotBlank() }
                binding.studentMeta.text = when {
                    batch != null && item.enrolled -> "$batch · ${item.imageCount} photos"
                    batch != null -> batch
                    item.enrolled -> "${item.imageCount} photos enrolled"
                    else -> "Face not enrolled yet"
                }
                binding.studentStatus.text = if (item.enrolled) {
                    binding.root.context.getString(R.string.status_ready)
                } else {
                    binding.root.context.getString(R.string.status_pending)
                }
            }
        }
    }
}
