package com.fieldlog.powerdebug.ui.test

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.DebugLog
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.data.db.PlannedItem
import com.fieldlog.powerdebug.util.DT
import com.fieldlog.powerdebug.util.SyncStore
import kotlinx.coroutines.launch

/**
 * 「开始测试」现场模式：
 * - 未测项：点通过→直接通过；点问题→弹多故障输入对话框；点文字→无操作
 * - 有故障项：点通过→直接通过并消除故障；点问题/点文字→弹故障列表对话框（每条可单独通过）
 * - 已通过项（历史）：点文字→弹测试流程+历史故障时间线；长按→弹菜单→驳回
 * - 生成日志：每个测试项独立一条日志（不再合并）
 */
class TestChecklistActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, instanceId: String) =
            Intent(ctx, TestChecklistActivity::class.java)
                .putExtra("instance_id", instanceId)
    }

    private var instanceId = ""
    private val passIds = mutableSetOf<String>()
    private val failNotes = mutableMapOf<String, String>() // itemId -> 换行分隔的多条故障现象
    private val lastReasons = mutableMapOf<String, String>() // 上次未通过的原因（faultId->symptom）
    private val passedItems = mutableListOf<PlannedItem>()
    private lateinit var adapter: CheckAdapter
    private lateinit var tvCount: TextView
    private lateinit var btnGenerate: Button
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_checklist)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        instanceId = intent.getStringExtra("instance_id").orEmpty()
        tvCount = findViewById(R.id.tv_count)
        btnGenerate = findViewById(R.id.btn_generate)
        tvInfo = findViewById(R.id.tv_info)
        btnGenerate.setOnClickListener { confirmGenerate() }
        tvInfo.setOnClickListener { showSwitchDebuggerDialog() }

        adapter = CheckAdapter()
        findViewById<RecyclerView>(R.id.rv_checks).apply {
            layoutManager = LinearLayoutManager(this@TestChecklistActivity)
            adapter = this@TestChecklistActivity.adapter
        }

        lifecycleScope.launch {
            val inst = App.repo.getInstance(instanceId) ?: run { finish(); return@launch }
            supportActionBar?.title = "开始测试 · ${inst.name}"
            refreshInfo()

            val pending = App.db.plannedItemDao().pendingForTestOnce(instanceId)
            passedItems.clear()
            passedItems.addAll(
                App.db.plannedItemDao().allOfInstanceOnce(instanceId)
                    .filter { it.enabled && it.result == PlannedItem.RESULT_PASS }
            )
            val allFaultIds = pending.map { it.faultId }
                .flatMap { id -> id.split(",").filter { it.isNotEmpty() } }
            if (allFaultIds.isNotEmpty()) {
                App.db.faultRecordDao().byIdsOnce(allFaultIds).forEach { lastReasons[it.id] = it.symptom }
            }
            adapter.submit(pending)
            pendingTotal = pending.size
            findViewById<TextView>(R.id.tv_check_empty).visibility =
                if (pending.isEmpty() && passedItems.isEmpty()) View.VISIBLE else View.GONE
            findViewById<RecyclerView>(R.id.rv_checks).visibility =
                if (pending.isEmpty() && passedItems.isEmpty()) View.GONE else View.VISIBLE
            refreshCount()
        }
    }

    private fun refreshInfo() {
        lifecycleScope.launch {
            val inst = App.repo.getInstance(instanceId) ?: return@launch
            val dbg = SyncStore.currentDebugger(this@TestChecklistActivity)
            tvInfo.text = getString(
                R.string.start_test_hint,
                inst.name,
                inst.deviceCode.ifBlank { getString(R.string.whole_cabinet) },
                dbg.ifEmpty { getString(R.string.debugger_none_set) }
            )
        }
    }

    private fun showSwitchDebuggerDialog() {
        lifecycleScope.launch {
            val names = App.repo.debuggers().map { it.name }
            if (names.isEmpty()) {
                Toast.makeText(this@TestChecklistActivity, R.string.debugger_empty_hint, Toast.LENGTH_LONG).show()
                return@launch
            }
            val cur = SyncStore.currentDebugger(this@TestChecklistActivity)
            AlertDialog.Builder(this@TestChecklistActivity)
                .setTitle(R.string.debugger_switch_title)
                .setSingleChoiceItems(names.toTypedArray(), names.indexOf(cur)) { dlg, which ->
                    SyncStore.setCurrentDebugger(this@TestChecklistActivity, names[which])
                    refreshInfo()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshCount() {
        tvCount.text = getString(
            R.string.check_count_fmt,
            passIds.size + failNotes.size, pendingTotal, passIds.size, failNotes.size
        )
        btnGenerate.isEnabled = passIds.isNotEmpty() || failNotes.isNotEmpty()
    }

    private var pendingTotal = 0

    private fun confirmGenerate() {
        val dbg = SyncStore.currentDebugger(this)
        if (dbg.isBlank()) {
            Toast.makeText(this, R.string.debugger_pick_first, Toast.LENGTH_LONG).show()
            showSwitchDebuggerDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.generate_log)
            .setMessage(
                getString(R.string.generate_confirm_fmt2, passIds.size + failNotes.size) +
                    "\n" + getString(R.string.generate_as_debugger, dbg)
            )
            .setPositiveButton(R.string.confirm) { _, _ -> doGenerate(dbg) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doGenerate(testerInput: String) {
        lifecycleScope.launch {
            try {
                App.repo.startTestSave(
                    instanceId,
                    passIds.toList(),
                    failNotes.map { it.key to it.value.split("\n").filter { s -> s.isNotBlank() } },
                    testerInput,
                    SyncStore.currentUser(this@TestChecklistActivity).orEmpty()
                )
                Toast.makeText(this@TestChecklistActivity, R.string.log_generated, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@TestChecklistActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 对话框 ----------

    /** 多故障输入对话框：换行分隔多条故障 */
    private fun showMultiFaultDialog(item: PlannedItem) {
        val et = EditText(this).apply {
            hint = getString(R.string.fault_multi_input_hint)
            setText(failNotes[item.id].orEmpty())
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("未通过：${item.content}")
            .setMessage(getString(R.string.fail_symptom_required))
            .setView(et)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = et.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val faults = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (faults.isEmpty()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                failNotes[item.id] = faults.joinToString("\n")
                passIds.remove(item.id)
                adapter.notifyDataSetChanged()
                refreshCount()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /** 故障列表对话框：显示故障条目，每条可单独通过 */
    private fun showFaultListDialog(item: PlannedItem) {
        val faultText = failNotes[item.id].orEmpty()
        if (faultText.isBlank()) {
            Toast.makeText(this, R.string.fault_list_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val faults = faultText.split("\n").filter { it.isNotBlank() }.toMutableList()

        val holder = arrayOfNulls<FaultListAdapter>(1)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TestChecklistActivity)
            setPadding(48, 16, 48, 0)
        }

        holder[0] = FaultListAdapter(faults) { position ->
            if (position in faults.indices) {
                faults.removeAt(position)
                if (faults.isEmpty()) {
                    failNotes.remove(item.id)
                    passIds.add(item.id)
                } else {
                    failNotes[item.id] = faults.joinToString("\n")
                }
                holder[0]?.notifyDataSetChanged()
                this.adapter.notifyDataSetChanged()
                refreshCount()
                Toast.makeText(this, getString(R.string.fault_pass_ok, item.content), Toast.LENGTH_SHORT).show()
            }
        }
        rv.adapter = holder[0]

        AlertDialog.Builder(this)
            .setTitle("故障列表 · ${item.content}")
            .setView(rv)
            .setPositiveButton(R.string.fault_add_new) { _, _ ->
                showMultiFaultDialog(item)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 已通过项：测试流程+历史故障时间线对话框（只读） */
    private fun showTimelineDialog(item: PlannedItem) {
        lifecycleScope.launch {
            val timeline = App.repo.historyTimeline(instanceId, item.content)

            if (timeline.isEmpty()) {
                // 无历史记录时显示基本信息
                val sb = StringBuilder()
                sb.appendLine(item.content)
                if (item.doneAt > 0) sb.appendLine("通过时间：${DT.full(item.doneAt)}")
                AlertDialog.Builder(this@TestChecklistActivity)
                    .setTitle(getString(R.string.timeline_title, item.content))
                    .setMessage(sb.toString())
                    .setPositiveButton(R.string.close, null)
                    .show()
                return@launch
            }

            val tv = TextView(this@TestChecklistActivity).apply {
                setPadding(48, 32, 48, 16)
                textSize = 15f
            }
            val rv = RecyclerView(this@TestChecklistActivity).apply {
                layoutManager = LinearLayoutManager(this@TestChecklistActivity)
                adapter = TimelineAdapter(timeline)
            }

            val container = android.widget.LinearLayout(this@TestChecklistActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(tv)
                addView(rv)
            }

            // 统计信息
            val totalFaults = timeline.sumOf { it.second.size }
            val pendingFaults = timeline.sumOf { it.second.count { f -> f.status == FaultRecord.STATUS_PENDING } }
            tv.text = buildString {
                appendLine("共 ${timeline.size} 次测试")
                if (totalFaults > 0) {
                    append("故障 $totalFaults 条")
                    if (pendingFaults > 0) append("（待处理 $pendingFaults）")
                    appendLine()
                }
                append("当前状态：✓ 已通过")
            }

            AlertDialog.Builder(this@TestChecklistActivity)
                .setTitle(getString(R.string.timeline_title, item.content))
                .setView(container)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    /** 已通过项长按：弹出菜单 → 驳回 */
    private fun showPassedContextMenu(item: PlannedItem) {
        val options = arrayOf(getString(R.string.reject_menu_title))
        AlertDialog.Builder(this)
            .setTitle(item.content)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRejectPassDialog(item)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 驳回通过对话框：输入故障原因 + 删除通过日志 + 重置为未测 */
    private fun showRejectPassDialog(item: PlannedItem) {
        val et = EditText(this).apply {
            hint = getString(R.string.fault_multi_input_hint)
            minLines = 2
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reject_pass_title)
            .setMessage(getString(R.string.reject_pass_msg, item.content))
            .setView(et)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val text = et.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val faults = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (faults.isEmpty()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    App.repo.rejectPassedItem(item)
                    failNotes[item.id] = faults.joinToString("\n")
                    passIds.remove(item.id)
                    // 重新加载数据：通过项删除后应出现在未测列表
                    val pending = App.db.plannedItemDao().pendingForTestOnce(instanceId)
                    passedItems.clear()
                    passedItems.addAll(
                        App.db.plannedItemDao().allOfInstanceOnce(instanceId)
                            .filter { it.enabled && it.result == PlannedItem.RESULT_PASS }
                    )
                    adapter.submit(pending)
                    pendingTotal = pending.size
                    adapter.notifyDataSetChanged()
                    refreshCount()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 适配器 ----------

    private inner class CheckAdapter : RecyclerView.Adapter<CheckVH>() {
        private val data = mutableListOf<PlannedItem>()

        fun submit(list: List<PlannedItem>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CheckVH(LayoutInflater.from(parent.context).inflate(R.layout.item_check, parent, false))

        override fun getItemCount() = data.size + passedItems.size

        override fun onBindViewHolder(h: CheckVH, pos: Int) {
            // ===== 已通过历史项 =====
            if (pos >= data.size) {
                val p = passedItems[pos - data.size]
                h.tvText.text = p.content
                h.tvText.setTextColor(Color.parseColor("#2E7D32"))
                h.tvReason.visibility = View.VISIBLE
                h.tvReason.text =
                    if (p.doneAt > 0) getString(R.string.planned_passed_at_fmt, DT.full(p.doneAt))
                    else getString(R.string.planned_passed)
                h.btnPass.visibility = View.GONE
                h.btnFail.visibility = View.GONE
                // 点击文字 → 时间线
                h.tvText.setOnClickListener { showTimelineDialog(p) }
                // 长按文字 → 驳回菜单
                h.tvText.setOnLongClickListener { showPassedContextMenu(p); true }
                h.tvText.isLongClickable = true
                return
            }

            // ===== 待测试项 =====
            val item = data[pos]
            val markedPass = item.id in passIds
            val markedFail = item.id in failNotes

            // 故障显示逻辑
            val faultText = failNotes[item.id]
            val faultCount = faultText?.split("\n")?.count { it.isNotBlank() } ?: 0

            val faultSummary = when {
                markedFail && faultCount == 1 -> faultText?.trim() ?: ""
                markedFail && faultCount > 1 -> getString(R.string.fault_count_fmt, faultCount)
                !markedFail && item.result == PlannedItem.RESULT_FAIL && item.faultId.isNotBlank() -> {
                    val ids = item.faultId.split(",").filter { it.isNotEmpty() }
                    if (ids.size == 1) "上次未通过：${lastReasons[ids[0]] ?: "原因见日志"}"
                    else "上次未通过：${ids.size}条故障"
                }
                else -> ""
            }

            h.tvText.text = item.content
            h.tvText.setTextColor(
                when {
                    markedPass -> Color.parseColor("#2E7D32")
                    markedFail -> Color.parseColor("#D32F2F")
                    item.result == PlannedItem.RESULT_FAIL -> Color.parseColor("#B8860B")
                    else -> Color.parseColor("#212121")
                }
            )

            h.tvReason.visibility = if (faultSummary.isBlank()) View.GONE else View.VISIBLE
            h.tvReason.text = faultSummary

            // 通过按钮：未测→直接通过；有故障→直接通过并清除故障
            h.btnPass.apply {
                visibility = View.VISIBLE
                alpha = if (markedPass) 1f else 0.55f
                text = if (markedPass) "✓ 通过" else getString(R.string.btn_pass)
                setOnClickListener {
                    if (markedPass) {
                        passIds.remove(item.id)
                    } else {
                        passIds.add(item.id)
                        failNotes.remove(item.id)
                    }
                    notifyDataSetChanged(); refreshCount()
                }
            }

            // 问题按钮：有故障→故障列表；未测→多故障输入
            h.btnFail.apply {
                visibility = View.VISIBLE
                alpha = if (markedFail) 1f else 0.55f
                text = if (markedFail) "✗ 已记" else getString(R.string.btn_fail)
                setOnClickListener {
                    if (markedFail) {
                        showFaultListDialog(item)
                    } else {
                        showMultiFaultDialog(item)
                    }
                }
            }

            // 点击文字：
            // 有故障项 → 故障列表对话框
            // 上次未通过项 → 也可弹故障列表（查看历史故障）
            // 未测项 → 无操作
            h.tvText.setOnClickListener {
                when {
                    markedFail -> showFaultListDialog(item)
                    item.result == PlannedItem.RESULT_FAIL && item.faultId.isNotBlank() -> showFaultListDialog(item)
                }
            }
        }
    }
}

private class CheckVH(v: View) : RecyclerView.ViewHolder(v) {
    val tvText: TextView = v.findViewById(R.id.tv_text)
    val tvReason: TextView = v.findViewById(R.id.tv_reason)
    val btnPass: Button = v.findViewById(R.id.btn_pass)
    val btnFail: Button = v.findViewById(R.id.btn_fail)
}

/** 故障列表适配器：每条故障可点击通过 */
private class FaultListAdapter(
    private val faults: MutableList<String>,
    private val onPass: (Int) -> Unit
) : RecyclerView.Adapter<FaultListAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvFault: TextView = v.findViewById(R.id.tv_fault_text)
        val btnPass: Button = v.findViewById(R.id.btn_fault_pass)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_fault_list, parent, false)
        return VH(v)
    }

    override fun getItemCount() = faults.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        h.tvFault.text = faults[pos]
        h.btnPass.setOnClickListener { onPass(pos) }
    }
}

/** 时间线适配器：显示测试流程+故障记录 */
private class TimelineAdapter(
    private val data: List<Pair<DebugLog, List<FaultRecord>>>
) : RecyclerView.Adapter<TimelineAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvLog: TextView = v.findViewById(R.id.tv_timeline_log)
        val tvFaults: TextView = v.findViewById(R.id.tv_timeline_faults)
        val tvPass: TextView = v.findViewById(R.id.tv_timeline_pass)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline, parent, false)
        return VH(v)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (log, faults) = data[pos]
        val ctx = h.itemView.context
        h.tvLog.text = ctx.getString(R.string.timeline_log_fmt, DT.full(log.createdAt), log.tester)

        if (faults.isNotEmpty()) {
            h.tvFaults.visibility = View.VISIBLE
            val faultText = faults.joinToString("\n") { f ->
                val status = if (f.status == FaultRecord.STATUS_PENDING)
                    ctx.getString(R.string.timeline_fault_pending)
                else ctx.getString(R.string.timeline_fault_resolved)
                ctx.getString(R.string.timeline_fault_fmt, f.symptom) + "[$status]"
            }
            h.tvFaults.text = faultText
            h.tvPass.visibility = View.GONE
        } else {
            h.tvFaults.visibility = View.GONE
            h.tvPass.visibility = View.VISIBLE
            h.tvPass.text = ctx.getString(R.string.timeline_pass)
        }
    }
}
