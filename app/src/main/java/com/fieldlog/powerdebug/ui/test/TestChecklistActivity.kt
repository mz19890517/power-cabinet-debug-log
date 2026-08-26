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
import com.fieldlog.powerdebug.data.db.PlannedItem
import com.fieldlog.powerdebug.util.DT
import com.fieldlog.powerdebug.util.SyncStore
import kotlinx.coroutines.launch

/**
 * 「开始测试」现场模式：每项三态操作——✓通过 / ✗未通过(强制填故障现象)。
 * 未通过项下次仍出现在清单里供复测；生成一条合并日志，未通过项自动带故障记录。
 * v2.18：支持多故障输入、故障列表对话框逐个通过、驳回通过。
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
    /** 已通过项（本次只读展示，不再操作） */
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
            // 加载上次未通过的故障原因（支持逗号分隔多faultId）
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
                Toast.makeText(
                    this@TestChecklistActivity,
                    R.string.debugger_empty_hint, Toast.LENGTH_LONG
                ).show()
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
        val total = pendingTotal
        tvCount.text = getString(
            R.string.check_count_fmt,
            passIds.size + failNotes.size, total, passIds.size, failNotes.size
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
                val logId = App.repo.startTestSave(
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
                // 换行分隔，过滤空行
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

        val adapter = FaultListAdapter(faults) { position ->
            // 单条故障通过
            if (position in faults.indices) {
                faults.removeAt(position)
                if (faults.isEmpty()) {
                    failNotes.remove(item.id)
                    passIds.add(item.id)
                } else {
                    failNotes[item.id] = faults.joinToString("\n")
                }
                adapter.notifyDataSetChanged()
                this.adapter.notifyDataSetChanged()
                refreshCount()
                Toast.makeText(this, getString(R.string.fault_pass_ok, item.content), Toast.LENGTH_SHORT).show()
            }
        }

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TestChecklistActivity)
            this.adapter = adapter
            setPadding(48, 16, 48, 0)
        }

        AlertDialog.Builder(this)
            .setTitle("故障列表 · ${item.content}")
            .setView(rv)
            .setPositiveButton(R.string.fault_add_new) { _, _ ->
                showMultiFaultDialog(item)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 已通过项点击：弹窗驳回通过+添加故障原因 */
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
                failNotes[item.id] = faults.joinToString("\n")
                passIds.remove(item.id)
                adapter.notifyDataSetChanged()
                refreshCount()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class CheckAdapter : RecyclerView.Adapter<CheckVH>() {
        private val data = mutableListOf<PlannedItem>()

        fun submit(list: List<PlannedItem>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CheckVH(LayoutInflater.from(parent.context).inflate(R.layout.item_check, parent, false))

        override fun getItemCount() = data.size + passedItems.size

        override fun onBindViewHolder(h: CheckVH, pos: Int) {
            // 已通过只读项
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
                // 已通过项点击：驳回通过
                h.tvText.setOnClickListener { showRejectPassDialog(p) }
                return
            }

            val item = data[pos]
            val markedPass = item.id in passIds
            val markedFail = item.id in failNotes

            // 故障显示逻辑
            val faultText = failNotes[item.id]
            val faultCount = faultText?.split("\n")?.count { it.isNotBlank() } ?: 0

            // 故障摘要文本
            val faultSummary = when {
                markedFail && faultCount == 1 -> faultText?.trim() ?: ""
                markedFail && faultCount > 1 -> getString(R.string.fault_count_fmt, faultCount)
                !markedFail && item.result == PlannedItem.RESULT_FAIL && item.faultId.isNotBlank() -> {
                    // 上次未通过的原因
                    val ids = item.faultId.split(",").filter { it.isNotEmpty() }
                    if (ids.size == 1) {
                        "上次未通过：${lastReasons[ids[0]] ?: "原因见日志"}"
                    } else {
                        "上次未通过：${ids.size}条故障"
                    }
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
            h.btnFail.apply {
                visibility = View.VISIBLE
                alpha = if (markedFail) 1f else 0.55f
                text = if (markedFail) "✗ 已记" else getString(R.string.btn_fail)
                setOnClickListener {
                    if (markedFail) {
                        // 已有故障时：弹出故障列表对话框（可逐个通过或继续添加）
                        showFaultListDialog(item)
                    } else {
                        showMultiFaultDialog(item)
                    }
                }
            }

            // 点击测试项行（不含按钮）→故障列表对话框
            h.tvText.setOnClickListener {
                if (markedFail) {
                    showFaultListDialog(item)
                } else if (item.result == PlannedItem.RESULT_FAIL && item.faultId.isNotBlank()) {
                    showRejectPassDialog(item)
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
