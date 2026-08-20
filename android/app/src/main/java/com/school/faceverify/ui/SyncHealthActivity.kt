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
import com.school.faceverify.FaceVerifyApp
import com.school.faceverify.R
import com.school.faceverify.databinding.ActivitySyncHealthBinding
import com.school.faceverify.databinding.ItemOrphanBinding
import com.school.faceverify.net.FaceApiClient
import com.school.faceverify.net.SyncHealthOrphan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncHealthActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySyncHealthBinding
    private val selected = linkedSetOf<String>()
    private val adapter = OrphanAdapter(
        selectedIds = selected,
        onSelectionChanged = { updateDeleteButton() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncHealthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.orphanList.layoutManager = LinearLayoutManager(this)
        binding.orphanList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnDeleteSelected.setOnClickListener { confirmBulkDelete() }

        lifecycleScope.launch {
            val session = FaceVerifyApp.instance.settings.currentSession()
            if (!session.isAdmin) {
                Toast.makeText(this@SyncHealthActivity, R.string.admin_only_delete, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            refresh()
        }
    }

    private fun updateDeleteButton() {
        binding.btnDeleteSelected.isEnabled = selected.isNotEmpty()
        binding.btnDeleteSelected.text = if (selected.isEmpty()) {
            getString(R.string.sync_health_delete_selected)
        } else {
            getString(R.string.sync_health_delete_count, selected.size)
        }
    }

    private fun refresh() {
        binding.listLoading.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val session = FaceVerifyApp.instance.settings.currentSession()
                if (!session.isAdmin || session.userToken.isBlank()) {
                    Toast.makeText(this@SyncHealthActivity, R.string.admin_only_delete, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                val health = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken).syncHealth(session.userToken)
                }
                binding.healthSummary.text = getString(
                    R.string.sync_health_counts,
                    health.studentCount,
                    health.staffCount,
                    health.totalCount,
                )
                binding.orphanSummary.text = getString(
                    R.string.sync_health_orphan_counts,
                    health.orphans.size,
                    health.missingCrmIdCount,
                )
                selected.clear()
                adapter.submit(health.orphans)
                updateDeleteButton()
                val empty = health.orphans.isEmpty()
                binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                binding.orphanList.visibility = if (empty) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this@SyncHealthActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.listLoading.visibility = View.GONE
            }
        }
    }

    private fun confirmBulkDelete() {
        if (selected.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_health_delete_title)
            .setMessage(getString(R.string.sync_health_delete_message, selected.size))
            .setPositiveButton(R.string.delete_student) { _, _ -> bulkDelete() }
            .setNegativeButton(R.string.back, null)
            .show()
    }

    private fun bulkDelete() {
        lifecycleScope.launch {
            try {
                val cfg = FaceVerifyApp.instance.settings.configFlow.first()
                val session = FaceVerifyApp.instance.settings.currentSession()
                if (!session.isAdmin || session.userToken.isBlank()) {
                    Toast.makeText(this@SyncHealthActivity, R.string.admin_only_delete, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val ids = selected.toList()
                val (ok, body) = withContext(Dispatchers.IO) {
                    FaceApiClient(cfg.apiBaseUrl, cfg.deviceToken)
                        .bulkRemoveStudents(ids, session.userToken)
                }
                if (ok) {
                    Toast.makeText(
                        this@SyncHealthActivity,
                        getString(R.string.sync_health_deleted, body.toIntOrNull() ?: ids.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                    refresh()
                } else {
                    Toast.makeText(this@SyncHealthActivity, body.take(140), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SyncHealthActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private class OrphanAdapter(
        private val selectedIds: MutableSet<String>,
        private val onSelectionChanged: () -> Unit,
    ) : RecyclerView.Adapter<OrphanAdapter.Holder>() {
        private var items: List<SyncHealthOrphan> = emptyList()

        fun submit(list: List<SyncHealthOrphan>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemOrphanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(
            private val binding: ItemOrphanBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: SyncHealthOrphan) {
                val ctx = binding.root.context
                binding.orphanName.text = item.name
                binding.orphanMeta.text = buildString {
                    append(item.enrollmentNumber)
                    append(" · ")
                    append(item.subject.replaceFirstChar { it.uppercase() })
                    if (!item.batch.isNullOrBlank()) {
                        append(" · ")
                        append(item.batch)
                    }
                }
                binding.orphanReason.text = when {
                    item.reason.contains("missing_crm_id") ->
                        ctx.getString(R.string.sync_health_reason_missing_crm)
                    else -> item.reason
                }
                binding.checkSelect.setOnCheckedChangeListener(null)
                binding.checkSelect.isChecked = selectedIds.contains(item.id)
                binding.checkSelect.setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds.add(item.id) else selectedIds.remove(item.id)
                    onSelectionChanged()
                }
                binding.root.setOnClickListener {
                    binding.checkSelect.isChecked = !binding.checkSelect.isChecked
                }
            }
        }
    }
}
