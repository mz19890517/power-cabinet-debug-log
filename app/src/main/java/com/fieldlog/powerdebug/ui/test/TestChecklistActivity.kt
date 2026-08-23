package com.fieldlog.powerdebug.ui.test

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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

/** 「开始测试」现场模式：勾选本次完成的预选项，一键生成合并日志 */
class TestChecklistActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, instanceId: String) =
            Intent(ctx, TestChecklistActivity::class.java)
                .putExtra("instance_id", instanceId)
    }

    private var instanceId = ""
    private val checkedIds = mutableSetOf<String>()
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

            // 进入时对待测项做快照：测试过程中清单变化不影响本次勾选
            val pending = App.db.plannedItemDao().pendingEnabledOnce(instanceId)
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
        tvCount.text = getString(R.string.check_count_fmt, checkedIds.size, total)
        btnGenerate.isEnabled = checkedIds.isNotEmpty()
    }

    private fun confirmGenerate() {
        val et = EditText(this).apply {
            setText(SyncStore.currentUser(this@TestChecklistActivity).orEmpty())
            hint = getString(R.string.tester_name)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.generate_log)
            .setMessage(getString(R.string.generate_confirm_fmt, checkedIds.size))
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
                    instanceId, checkedIds.toList(), testerInput,
                    SyncStore.currentUser(this@TestChecklistActivity).orEmpty()
                )
                Toast.makeText(this@TestChecklistActivity, R.string.log_generated, Toast.LENGTH_SHORT).show()
                // 生成后追问是否立即登记故障
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
            h.cb.setOnCheckedChangeListener(null)
            h.cb.text = item.content
            h.cb.isChecked = item.id in checkedIds
            h.cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checkedIds.add(item.id) else checkedIds.remove(item.id)
                refreshCount()
            }
        }
    }
}

private class CheckVH(v: View) : RecyclerView.ViewHolder(v) {
    val cb: CheckBox = v.findViewById(R.id.cb_check)
}
