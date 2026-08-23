package com.fieldlog.powerdebug.ui.device

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.InstanceStatusRow
import com.fieldlog.powerdebug.data.db.Project
import com.fieldlog.powerdebug.databinding.ItemSimpleCardBinding
import com.fieldlog.powerdebug.ui.log.LogEditActivity
import com.fieldlog.powerdebug.ui.test.PlannedManageActivity
import com.fieldlog.powerdebug.ui.test.TestChecklistActivity
import kotlinx.coroutines.launch

class ProjectDetailActivity : AppCompatActivity() {

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        fun intent(ctx: Context, projectId: String) =
            Intent(ctx, ProjectDetailActivity::class.java).putExtra(KEY_PROJECT_ID, projectId)
    }

    private var projectId = ""
    private lateinit var adapter: InstanceAdapter
    private var typeNames: Map<String, String> = emptyMap()
    private var project: Project? = null
    private var latestRows: List<InstanceStatusRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_detail)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        projectId = intent.getStringExtra(KEY_PROJECT_ID).orEmpty()

        lifecycleScope.launch {
            typeNames = App.repo.allTypes().associate { it.id to it.name }
        }

        adapter = InstanceAdapter(
            onClick = { showInstanceDialog(it.instance) },
            onLongClick = { showInstanceMenu(it.instance) }
        )
        val rv = findViewById<RecyclerView>(R.id.rv_instances)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.btn_add_instance).setOnClickListener { showInstanceDialog(null) }

        lifecycleScope.launch {
            App.db.instanceDao().watchByProjectWithStatsAsFlow(projectId).collect { list ->
                latestRows = list
                adapter.submit(list)
                findViewById<TextView>(R.id.tv_empty_instances).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                refreshHeader(list)
            }
        }

        lifecycleScope.launch {
            project = App.repo.getProject(projectId)
            supportActionBar?.title = project?.name ?: getString(R.string.title_project_detail)
            refreshHeader(latestRows)
        }
    }

    private fun refreshHeader(rows: List<InstanceStatusRow>) {
        val p = project ?: return
        val pendingTests = rows.sumOf { it.pendingTests }
        val pendingFaults = rows.sumOf { it.pendingFaults }
        findViewById<TextView>(R.id.tv_project_info).text = buildString {
            appendLine("项目：${p.name}")
            if (p.code.isNotBlank()) appendLine("工程编号：${p.code}")
            if (p.remark.isNotBlank()) appendLine("备注：${p.remark}")
            append("共 ${rows.size} 台柜子 · 待测 $pendingTests · 待处理故障 $pendingFaults")
        }
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_project_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_edit_project -> { project?.let { editProject(it) }; true }
        R.id.action_delete_project -> { deleteProject(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun editProject(p: Project) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_input_multiline, null)
        val prompt = dlgView.findViewById<TextView>(R.id.tv_prompt)
        val input = dlgView.findViewById<EditText>(R.id.et_input)
        input.minLines = 3
        prompt.text = getString(R.string.project_name) + "\n" + getString(R.string.project_code) + "\n" + getString(R.string.project_remark)
        input.setText("${p.name}\n${p.code}\n${p.remark}")
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_project)
            .setView(dlgView)
            .setPositiveButton(R.string.save) { _, _ ->
                val lines = input.text?.toString()?.lines().orEmpty()
                val name = lines.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    App.repo.saveProject(
                        p.copy(
                            name = name,
                            code = lines.getOrNull(1)?.trim().orEmpty(),
                            remark = lines.drop(2).joinToString("\n").trim()
                        )
                    )
                    this@ProjectDetailActivity.project = App.repo.getProject(projectId)
                    supportActionBar?.title = this@ProjectDetailActivity.project?.name
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteProject() {
        val p = project ?: return
        lifecycleScope.launch {
            val cabinets = App.db.instanceDao().byProjectOnce(p.id).size
            AlertDialog.Builder(this@ProjectDetailActivity)
                .setTitle(R.string.delete)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(getString(R.string.warn_del_project, p.name, cabinets))
                .setPositiveButton(R.string.confirm) { _, _ ->
                    lifecycleScope.launch {
                        App.repo.deleteProject(p.id)
                        finish()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---------- 柜子实例 ----------

    private fun showInstanceMenu(inst: com.fieldlog.powerdebug.data.db.CabinetInstance) {
        AlertDialog.Builder(this)
            .setTitle(inst.name)
            .setItems(
                arrayOf(
                    getString(R.string.menu_start_test),
                    getString(R.string.menu_manage_planned),
                    getString(R.string.menu_log_new_here),
                    getString(R.string.delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(TestChecklistActivity.intent(this, inst.id))
                    1 -> startActivity(PlannedManageActivity.intent(this, inst.id))
                    2 -> startActivity(
                        Intent(this, LogEditActivity::class.java)
                            .putExtra(LogEditActivity.KEY_INSTANCE_ID, inst.id)
                            .putExtra(LogEditActivity.KEY_PROJECT_ID, projectId)
                    )

                    3 -> confirmDeleteInstance(inst)
                }
            }
            .show()
    }

    private fun confirmDeleteInstance(inst: com.fieldlog.powerdebug.data.db.CabinetInstance) {
        lifecycleScope.launch {
            val logs = App.db.debugLogDao().countLogsOf(inst.id)
            AlertDialog.Builder(this@ProjectDetailActivity)
                .setTitle(R.string.delete)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(getString(R.string.warn_del_instance, inst.name, logs))
                .setPositiveButton(R.string.confirm) { _, _ ->
                    lifecycleScope.launch { App.repo.deleteInstance(inst.id) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showInstanceDialog(existing: com.fieldlog.powerdebug.data.db.CabinetInstance?) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_instance_edit, null)
        val etName = dlgView.findViewById<EditText>(R.id.etInstName)
        val etCode = dlgView.findViewById<EditText>(R.id.etInstCode)
        val etLocation = dlgView.findViewById<EditText>(R.id.etInstLocation)
        val etInstaller = dlgView.findViewById<EditText>(R.id.etInstaller)
        val spType = dlgView.findViewById<android.widget.Spinner>(R.id.spType)

        lifecycleScope.launch {
            val types = App.repo.allTypes()
            if (types.isEmpty()) {
                Toast.makeText(this@ProjectDetailActivity, "请先在「柜子类型」页创建类型模板", Toast.LENGTH_LONG).show()
                return@launch
            }
            spType.adapter = android.widget.ArrayAdapter(
                this@ProjectDetailActivity,
                android.R.layout.simple_spinner_item,
                types.map { it.name }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            existing?.let {
                etName.setText(it.name); etCode.setText(it.deviceCode)
                etLocation.setText(it.location); etInstaller.setText(it.installer)
                val idx = types.indexOfFirst { t -> t.id == it.typeId }
                if (idx >= 0) spType.setSelection(idx)
            }

            AlertDialog.Builder(this@ProjectDetailActivity)
                .setTitle(if (existing == null) R.string.add_instance else R.string.edit)
                .setView(dlgView)
                .setPositiveButton(R.string.save) { _, _ ->
                    val name = etName.text?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) {
                        Toast.makeText(this@ProjectDetailActivity, R.string.name_required, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val type = types.getOrNull(spType.selectedItemPosition) ?: return@setPositiveButton
                    lifecycleScope.launch {
                        App.repo.saveInstance(
                            com.fieldlog.powerdebug.data.db.CabinetInstance(
                                id = existing?.id.orEmpty(),
                                projectId = projectId,
                                typeId = type.id,
                                name = name,
                                deviceCode = etCode.text?.toString()?.trim().orEmpty(),
                                location = etLocation.text?.toString()?.trim().orEmpty(),
                                installer = etInstaller.text?.toString()?.trim().orEmpty(),
                                createdAt = existing?.createdAt ?: System.currentTimeMillis()
                            )
                        )
                        Toast.makeText(
                            this@ProjectDetailActivity,
                            R.string.planned_seeded_hint,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---------- 适配器 ----------

    private inner class InstanceAdapter(
        private val onClick: (InstanceStatusRow) -> Unit,
        private val onLongClick: (InstanceStatusRow) -> Unit
    ) : RecyclerView.Adapter<InstanceAdapter.VH>() {

        private val data = mutableListOf<InstanceStatusRow>()

        fun submit(list: List<InstanceStatusRow>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        inner class VH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSimpleCardBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val row = data[pos]
            val item = row.instance
            h.ib.tvName.text = item.name
            val base = buildString {
                append(typeNames[item.typeId].orEmpty())
                if (item.deviceCode.isNotBlank()) append(" · 编号:${item.deviceCode}")
                if (item.location.isNotBlank()) append(" · ${item.location}")
                if (item.installer.isNotBlank()) append(" · 安装:${item.installer}")
            }
            val statusText = "  待测 ${row.pendingTests} · 待处理故障 ${row.pendingFaults}"
            val ssb = SpannableStringBuilder(base).append(statusText)
            if (row.pendingTests > 0 || row.pendingFaults > 0) {
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#B8860B")),
                    base.length, ssb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (row.pendingFaults > 0) {
                val start = base.length + "  待测 ${row.pendingTests} · ".length
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#D32F2F")),
                    start, ssb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            h.ib.tvSub.text = ssb
            h.ib.root.setOnClickListener { onClick(row) }
            h.ib.root.setOnLongClickListener { onLongClick(row); true }
        }
    }
}
