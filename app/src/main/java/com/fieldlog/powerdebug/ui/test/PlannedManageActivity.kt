package com.fieldlog.powerdebug.ui.test

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.PlannedItem
import com.fieldlog.powerdebug.util.DT
import kotlinx.coroutines.launch

/** 预选待测清单管理：启用/停用、加自定义条目、从候选池补充、删除 */
class PlannedManageActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, instanceId: String) =
            Intent(ctx, PlannedManageActivity::class.java)
                .putExtra("instance_id", instanceId)
    }

    private var instanceId = ""
    private var typeId = ""
    private lateinit var adapter: PlannedAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planned_manage)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        instanceId = intent.getStringExtra("instance_id").orEmpty()

        adapter = PlannedAdapter()
        findViewById<RecyclerView>(R.id.rv_planned).apply {
            layoutManager = LinearLayoutManager(this@PlannedManageActivity)
            adapter = this@PlannedManageActivity.adapter
        }

        lifecycleScope.launch {
            val inst = App.repo.getInstance(instanceId) ?: run { finish(); return@launch }
            typeId = inst.typeId
            supportActionBar?.title = "预选待测 · ${inst.name}"
        }

        lifecycleScope.launch {
            App.repo.watchPlannedOf(instanceId).collect { list ->
                adapter.submit(list)
                findViewById<TextView>(R.id.tv_planned_empty).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                findViewById<RecyclerView>(R.id.rv_planned).visibility =
                    if (list.isEmpty()) View.GONE else View.VISIBLE
                refreshInfo(list)
            }
        }

        val etNewItem = findViewById<EditText>(R.id.et_new_item)
        findViewById<View>(R.id.btn_add_item).setOnClickListener {
            val text = etNewItem.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            etNewItem.setText("")
            lifecycleScope.launch {
                val added = App.repo.addPlannedFromText(instanceId, text)
                Toast.makeText(
                    this@PlannedManageActivity,
                    getString(R.string.added_fmt, added, if (added == 0) 1 else 0),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 从所属类型候选池补充缺失条目
        findViewById<View>(R.id.btn_sync_pool).setOnClickListener {
            lifecycleScope.launch {
                val added = App.repo.syncPlannedFromPool(instanceId, typeId)
                Toast.makeText(
                    this@PlannedManageActivity,
                    getString(R.string.planned_sync_done, added),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun refreshInfo(list: List<PlannedItem>) {
        val pending = list.count { it.enabled && it.doneAt == 0L }
        val done = list.count { it.doneAt > 0 }
        val disabled = list.count { !it.enabled && it.doneAt == 0L }
        findViewById<TextView>(R.id.tv_info).text =
            getString(R.string.planned_stat_fmt, list.size, pending, done, disabled)
    }

    private inner class PlannedAdapter : RecyclerView.Adapter<PlannedVH>() {
        private val data = mutableListOf<PlannedItem>()

        fun submit(list: List<PlannedItem>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PlannedVH(LayoutInflater.from(parent.context).inflate(R.layout.item_planned, parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: PlannedVH, pos: Int) {
            val item = data[pos]
            h.cbEnabled.setOnCheckedChangeListener(null)
            h.cbEnabled.isChecked = item.enabled
            h.tvText.text = item.content
            h.tvStatus.text = when {
                item.doneAt > 0 -> getString(R.string.planned_done_fmt, DT.full(item.doneAt))
                !item.enabled -> getString(R.string.planned_disabled)
                else -> getString(R.string.planned_pending)
            }
            h.tvStatus.setTextColor(
                if (item.doneAt > 0) android.graphics.Color.parseColor("#2E7D32")
                else android.graphics.Color.GRAY
            )
            h.cbEnabled.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { App.repo.updatePlanned(item.copy(enabled = checked)) }
            }
            // 点已完成项：可撤销完成（恢复待测）
            h.tvText.setOnClickListener {
                if (item.doneAt > 0) {
                    AlertDialog.Builder(this@PlannedManageActivity)
                        .setTitle(R.string.planned_undo_title)
                        .setMessage(item.content)
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            lifecycleScope.launch {
                                App.repo.updatePlanned(item.copy(doneAt = 0, logId = ""))
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            h.btnDel.setOnClickListener {
                AlertDialog.Builder(this@PlannedManageActivity)
                    .setTitle(R.string.delete)
                    .setMessage(item.content)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        lifecycleScope.launch { App.repo.deletePlanned(item) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }
}

private class PlannedVH(v: View) : RecyclerView.ViewHolder(v) {
    val cbEnabled: CheckBox = v.findViewById(R.id.cb_enabled)
    val tvText: TextView = v.findViewById(R.id.tv_text)
    val tvStatus: TextView = v.findViewById(R.id.tv_status)
    val btnDel: ImageButton = v.findViewById(R.id.btn_del)
}
