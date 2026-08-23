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
import com.fieldlog.powerdebug.ui.log.LogEditActivity
import com.fieldlog.powerdebug.util.SyncStore
import kotlinx.coroutines.launch

/**
 * 「开始测试」现场模式：每项三态操作——✓通过 / ✗未通过(强制填故障现象)。
 * 未通过项下次仍出现在清单里供复测；生成一条合并日志，未通过项自动带故障记录。
 */
class TestChecklistActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, instanceId: String) =
            Intent(ctx, TestChecklistActivity::class.java)
                .putExtra("instance_id", instanceId)
    }

    private var instanceId = ""
    private val passIds = mutableSetOf<String>()
    private val failNotes = mutableMapOf<String, String>() // itemId -> 现场填写的故障现象
    private val lastReasons = mutableMapOf<String, String>() // 上次未通过的原因（faultId->symptom）
    private lateinit var adapter: CheckAdapter
    private lateinit var tvCount: TextView
    private lateinit var btnGenerate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_checklist)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        instanceId = intent.getStringExtra("instance_id").orEmpty()
        tvCount = findViewById(R.id.tv_count)
        btnGenerate = findViewById(R.id.btn_generate)
        btnGenerate.setOnClickListener { confirmGenerate() }

        adapter = CheckAdapter()
        findViewById<RecyclerView>(R.id.rv_checks).apply {
            layoutManager = LinearLayoutManager(this@TestChecklistActivity)
            adapter = this@TestChecklistActivity.adapter
        }

        lifecycleScope.launch {
            val inst = App.repo.getInstance(instanceId) ?: run { finish(); return@launch }
            supportActionBar?.title = "开始测试 · ${inst.name}"
            findViewById<TextView>(R.id.tv_info).text =
                getString(
                    R.string.start_test_hint,
                    inst.name,
                    inst.deviceCode.ifBlank { getString(R.string.whole_cabinet) }
                )

            // 进入时对待办项做快照：未通过项继续出现，供复测
            val pending = App.db.plannedItemDao().pendingForTestOnce(instanceId)
            val faultIds = pending.map { it.faultId }.filter { it.isNotEmpty() }
            if (faultIds.isNotEmpty()) {
                App.db.faultRecordDao().byIdsOnce(faultIds).forEach { lastReasons[it.id] = it.symptom }
            }
            adapter.submit(pending)
            findViewById<TextView>(R.id.tv_check_empty).visibility =
                if (pending.isEmpty()) View.VISIBLE else View.GONE
            findViewById<RecyclerView>(R.id.rv_checks).visibility =
                if (pending.isEmpty()) View.GONE else View.VISIBLE
            refreshCount()
        }
    }

    private fun refreshCount() {
        val total = adapter.itemCount
        tvCount.text = getString(
            R.string.check_count_fmt,
            passIds.size + failNotes.size, total, passIds.size, failNotes.size
        )
        btnGenerate.isEnabled = passIds.isNotEmpty() || failNotes.isNotEmpty()
    }

    private fun confirmGenerate() {
        val et = EditText(this).apply {
            // 默认带出最近一次使用的调试员（与登录账号无关）
            setText(SyncStore.lastDebugger(this@TestChecklistActivity))
            hint = getString(R.string.tester_name)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.generate_log)
            .setMessage(getString(R.string.generate_confirm_fmt, passIds.size + failNotes.size))
            .setView(et)
            .setPositiveButton(R.string.confirm) { _, _ ->
                doGenerate(et.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doGenerate(testerInput: String) {
        lifecycleScope.launch {
            try {
                val logId = App.repo.startTestSave(
                    instanceId,
                    passIds.toList(),
                    failNotes.map { it.key to it.value },
                    testerInput,
                    SyncStore.currentUser(this@TestChecklistActivity).orEmpty()
                )
                // 记住本次使用的调试员
                if (testerInput.isNotBlank()) {
                    SyncStore.setLastDebugger(this@TestChecklistActivity, testerInput)
                }
                Toast.makeText(this@TestChecklistActivity, R.string.log_generated, Toast.LENGTH_SHORT).show()
                // 生成后追问是否立即登记/补充故障
                AlertDialog.Builder(this@TestChecklistActivity)
                    .setTitle(R.string.log_generated)
                    .setMessage(R.string.ask_fault_now)
                    .setPositiveButton(R.string.yes_register_now) { _, _ ->
                        startActivity(
                            Intent(this@TestChecklistActivity, LogEditActivity::class.java)
                                .putExtra(LogEditActivity.KEY_LOG_ID, logId)
                        )
                        finish()
                    }
                    .setNegativeButton(R.string.later) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@TestChecklistActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** ✗未通过：强制当场填写故障现象（空内容不放行） */
    private fun showFailDialog(item: PlannedItem) {
        val et = EditText(this).apply {
            hint = getString(R.string.fail_symptom_hint)
            setText(failNotes[item.id].orEmpty())
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
                failNotes[item.id] = text
                passIds.remove(item.id)
                adapter.notifyDataSetChanged()
                refreshCount()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private inner class CheckAdapter : RecyclerView.Adapter<CheckVH>() {
        private val data = mutableListOf<PlannedItem>()

        fun submit(list: List<PlannedItem>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CheckVH(LayoutInflater.from(parent.context).inflate(R.layout.item_check, parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: CheckVH, pos: Int) {
            val item = data[pos]
            val markedPass = item.id in passIds
            val markedFail = item.id in failNotes

            h.tvText.text = item.content
            h.tvText.setTextColor(
                when {
                    markedPass -> Color.parseColor("#2E7D32")
                    markedFail -> Color.parseColor("#D32F2F")
                    item.result == PlannedItem.RESULT_FAIL -> Color.parseColor("#B8860B")
                    else -> Color.parseColor("#212121")
                }
            )

            // 上次未通过的原因提示（本次还没操作时显示）
            val reason = if (!markedFail && item.result == PlannedItem.RESULT_FAIL)
                "上次未通过：${lastReasons[item.faultId] ?: "原因见日志"}"
            else null
            h.tvReason.visibility = if (reason == null) View.GONE else View.VISIBLE
            h.tvReason.text = reason.orEmpty()

            h.btnPass.apply {
                alpha = if (markedPass) 1f else 0.55f
                text = if (markedPass) "✓ 通过" else getString(R.string.btn_pass)
                setOnClickListener {
                    if (markedPass) {
                        passIds.remove(item.id)
                    } else {
                        passIds.add(item.id); failNotes.remove(item.id)
                    }
                    notifyDataSetChanged(); refreshCount()
                }
            }
            h.btnFail.apply {
                alpha = if (markedFail) 1f else 0.55f
                text = if (markedFail) "✗ 已记" else getString(R.string.btn_fail)
                setOnClickListener {
                    if (markedFail) {
                        failNotes.remove(item.id)
                        notifyDataSetChanged(); refreshCount()
                    } else {
                        showFailDialog(item)
                    }
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
