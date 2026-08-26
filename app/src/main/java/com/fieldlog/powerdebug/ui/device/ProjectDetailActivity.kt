package com.fieldlog.powerdebug.ui.device

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.core.ExportSheets
import com.fieldlog.powerdebug.core.XlsxWriter
import com.fieldlog.powerdebug.data.ExportFilter
import com.fieldlog.powerdebug.data.db.InstanceStatusRow
import com.fieldlog.powerdebug.ui.FilterDialogHelper
import com.fieldlog.powerdebug.data.db.Project
import com.fieldlog.powerdebug.databinding.ItemSimpleCardBinding
import com.fieldlog.powerdebug.databinding.ItemSimpleCardGridBinding
import com.fieldlog.powerdebug.ui.log.LogEditActivity
import com.fieldlog.powerdebug.ui.test.PlannedManageActivity
import com.fieldlog.powerdebug.ui.test.TestChecklistActivity
import com.fieldlog.powerdebug.util.DT
import com.fieldlog.powerdebug.util.SyncLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectDetailActivity : AppCompatActivity() {

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        private const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val PREF_NAME = "project_detail_prefs"
        private const val KEY_GRID_VIEW = "grid_view"
        private const val TYPE_LIST = 0
        private const val TYPE_GRID = 1

        fun intent(ctx: Context, projectId: String) =
            Intent(ctx, ProjectDetailActivity::class.java).putExtra(KEY_PROJECT_ID, projectId)
    }

    private var projectId = ""
    private lateinit var adapter: InstanceAdapter
    private var typeNames: Map<String, String> = emptyMap()
    private var project: Project? = null
    private var latestRows: List<InstanceStatusRow> = emptyList()
    private var isGridLayout = false

    /** 单柜日志导出（长按菜单入口） */
    private var exportInstanceId = ""
    private var currentInstanceFilter = ExportFilter()
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri -> uri?.let { doExportInstance(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_detail)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        projectId = intent.getStringExtra(KEY_PROJECT_ID).orEmpty()
        isGridLayout = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_GRID_VIEW, false)

        lifecycleScope.launch {
            typeNames = App.repo.allTypes().associate { it.id to it.name }
        }

        adapter = InstanceAdapter(
            onClick = { routeInstanceClick(it.instance) },
            onLongClick = { showInstanceMenu(it.instance) },
            onGridLongClick = { showShortNameDialog(it.instance) }
        )
        val rv = findViewById<RecyclerView>(R.id.rv_instances)
        rv.layoutManager = if (isGridLayout) GridLayoutManager(this, 3) else LinearLayoutManager(this)
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
        val failedTests = rows.sumOf { it.failedTests }
        val pendingFaults = rows.sumOf { it.pendingFaults }
        findViewById<TextView>(R.id.tv_project_info).text = buildString {
            appendLine("项目：${p.name}")
            if (p.code.isNotBlank()) appendLine("工程编号：${p.code}")
            if (p.remark.isNotBlank()) appendLine("备注：${p.remark}")
            append("共 ${rows.size} 台柜子 · 待测 $pendingTests · 未通过 $failedTests · 待处理故障 $pendingFaults")
        }
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_project_detail, menu)
        updateMenuIcon(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_toggle_view -> { toggleView(); true }
        R.id.action_edit_project -> { project?.let { editProject(it) }; true }
        R.id.action_delete_project -> { deleteProject(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun updateMenuIcon(menu: Menu) {
        val toggleItem = menu.findItem(R.id.action_toggle_view)
        toggleItem?.setIcon(if (isGridLayout) R.drawable.ic_list_view else R.drawable.ic_grid_view)
    }

    private fun toggleView() {
        isGridLayout = !isGridLayout
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_GRID_VIEW, isGridLayout).apply()
        val rv = findViewById<RecyclerView>(R.id.rv_instances)
        rv.layoutManager = if (isGridLayout) GridLayoutManager(this, 3) else LinearLayoutManager(this)
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun showShortNameDialog(inst: com.fieldlog.powerdebug.data.db.CabinetInstance) {
        val et = EditText(this).apply {
            setText(inst.shortName)
            hint = getString(R.string.short_name_hint)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.short_name_title)
            .setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = et.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    App.repo.saveInstance(inst.copy(shortName = newName, updatedAt = System.currentTimeMillis()))
                    if (newName.isNotEmpty()) {
                        Toast.makeText(this@ProjectDetailActivity, getString(R.string.short_name_saved, newName), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ProjectDetailActivity, R.string.short_name_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    /**
     * 单击柜子：已有启用的测试项目 → 直接开始调试；
     * 还没建过清单 → 提示并先跳「预选待测」。
     */
    private fun routeInstanceClick(inst: com.fieldlog.powerdebug.data.db.CabinetInstance) {
        lifecycleScope.launch {
            val hasItems = App.db.plannedItemDao().allOfInstanceOnce(inst.id).any { it.enabled }
            if (hasItems) {
                startActivity(TestChecklistActivity.intent(this@ProjectDetailActivity, inst.id))
            } else {
                Toast.makeText(this@ProjectDetailActivity, R.string.planned_first_hint, Toast.LENGTH_SHORT).show()
                startActivity(PlannedManageActivity.intent(this@ProjectDetailActivity, inst.id))
            }
        }
    }

    private fun showInstanceMenu(inst: com.fieldlog.powerdebug.data.db.CabinetInstance) {
        AlertDialog.Builder(this)
            .setTitle(inst.name)
            .setItems(
                arrayOf(
                    getString(R.string.menu_edit_instance),
                    getString(R.string.menu_manage_planned),
                    getString(R.string.menu_log_new_here),
                    getString(R.string.menu_export_instance),
                    getString(R.string.menu_pull_planned),
                    getString(R.string.delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> showInstanceDialog(inst)
                    1 -> startActivity(PlannedManageActivity.intent(this, inst.id))
                    2 -> startActivity(
                        Intent(this, LogEditActivity::class.java)
                            .putExtra(LogEditActivity.KEY_INSTANCE_ID, inst.id)
                            .putExtra(LogEditActivity.KEY_PROJECT_ID, projectId)
                    )
                    3 -> requestExportInstance(inst)
                    4 -> showPullSourceDialog(inst)
                    5 -> confirmDeleteInstance(inst)
                }
            }
            .show()
    }

    // ---------- 跨柜拉取预选待测 ----------

    /**
     * 「从别的柜子拉取」：弹出全库柜子列表（可按项目名/柜子名搜索过滤），
     * 选中来源柜后，用其启用的预选清单整体覆盖本柜清单。
     */
    private fun showPullSourceDialog(target: com.fieldlog.powerdebug.data.db.CabinetInstance) {
        lifecycleScope.launch {
            val sources = App.repo.allInstancesWithProject().filter { it.instance.id != target.id }
            if (sources.isEmpty()) {
                Toast.makeText(this@ProjectDetailActivity, R.string.pull_no_source, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dlgView = layoutInflater.inflate(R.layout.dialog_pull_source, null)
            val etSearch = dlgView.findViewById<EditText>(R.id.et_search)
            val rv = dlgView.findViewById<RecyclerView>(R.id.rv_sources)
            val tvEmpty = dlgView.findViewById<TextView>(R.id.tv_pull_empty)

            val srcAdapter = SourceAdapter(sources) { src ->
                confirmPull(target, src)
            }
            rv.layoutManager = LinearLayoutManager(this@ProjectDetailActivity)
            rv.adapter = srcAdapter

            AlertDialog.Builder(this@ProjectDetailActivity)
                .setTitle(getString(R.string.pull_title_fmt, target.name))
                .setView(dlgView)
                .setNegativeButton(R.string.cancel, null)
                .show()

            etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    srcAdapter.filter(s?.toString()?.trim().orEmpty())
                }
            })
        }
    }

    private fun confirmPull(
        target: com.fieldlog.powerdebug.data.db.CabinetInstance,
        src: com.fieldlog.powerdebug.data.db.InstanceRow
    ) {
        lifecycleScope.launch {
            try {
                val srcCount =
                    App.db.plannedItemDao().contentsOnce(src.instance.id).count { it.isNotBlank() }
                val tgtCount = App.db.plannedItemDao().allOfInstanceOnce(target.id).size
                AlertDialog.Builder(this@ProjectDetailActivity)
                    .setTitle(R.string.pull_confirm_title)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(
                        getString(
                            R.string.pull_confirm_msg,
                            "${src.projectName}·${src.instance.name}", srcCount, tgtCount
                        )
                    )
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val n = App.repo.pullPlannedFromCabinet(
                                    target.id, src.instance.id
                                )
                                Toast.makeText(
                                    this@ProjectDetailActivity,
                                    getString(R.string.pull_done_fmt, n),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                // 拉取失败必须可见且可追查：详情落SyncLog供「查看同步日志」回传
                                logPullError("执行覆盖", e)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                logPullError("读取清单", e)
            }
        }
    }

    /** 拉取链路异常统一上报：堆栈关键帧写入诊断日志，toast带异常类型 */
    private fun logPullError(stage: String, e: Exception) {
        SyncLog.append(
            this,
            "⚠ 跨柜拉取[$stage] ${e.javaClass.name}: ${e.message} ⏎ " +
                e.stackTraceToString().lineSequence().take(6).joinToString(" ⏎ ")
        )
        Toast.makeText(
            this, "拉取失败[${stage}]：${e.javaClass.simpleName}: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 来源柜适配器：项目名/柜子名双字段搜索过滤 */
    private inner class SourceAdapter(
        private val all: List<com.fieldlog.powerdebug.data.db.InstanceRow>,
        private val onClick: (com.fieldlog.powerdebug.data.db.InstanceRow) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.SVH>() {

        private val shown = mutableListOf<com.fieldlog.powerdebug.data.db.InstanceRow>()

        init { filter("") }

        fun filter(q: String) {
            shown.clear()
            val key = q.trim()
            shown += all.filter {
                key.isEmpty() ||
                    it.projectName.contains(key, ignoreCase = true) ||
                    it.instance.name.contains(key, ignoreCase = true) ||
                    it.instance.deviceCode.contains(key, ignoreCase = true)
            }
            notifyDataSetChanged()
        }

        inner class SVH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SVH(ItemSimpleCardBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: SVH, pos: Int) {
            val row = shown[pos]
            h.ib.tvName.text = row.instance.name
            h.ib.tvSub.text = buildString {
                append(row.projectName)
                typeNames[row.instance.typeId]?.let { append(" · ").append(it) }
                if (row.instance.deviceCode.isNotBlank()) append(" · 编号:").append(row.instance.deviceCode)
            }
            h.ib.root.setOnClickListener { onClick(row) }
        }
    }

    private fun requestExportInstance(inst: com.fieldlog.powerdebug.data.db.CabinetInstance) {
        exportInstanceId = inst.id
        FilterDialogHelper.show(this, lifecycleScope, currentInstanceFilter) { filter ->
            currentInstanceFilter = filter
            exportLauncher.launch("电源柜调试日志_${inst.name}_${DT.fileStamp()}.xlsx")
        }
    }

    private fun doExportInstance(uri: Uri) {
        lifecycleScope.launch {
            try {
                val (logs, faults) = App.repo.collectExportOf(instanceId = exportInstanceId, filter = currentInstanceFilter)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        XlsxWriter.write(out, ExportSheets.build(
                            this@ProjectDetailActivity, logs, faults,
                            logColumns = currentInstanceFilter.logColumns,
                            faultColumns = currentInstanceFilter.faultColumns
                        ))
                    } ?: throw IllegalStateException("无法打开输出流")
                }
                Toast.makeText(
                    this@ProjectDetailActivity,
                    getString(R.string.export_ok, uri.lastPathSegment ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ProjectDetailActivity,
                    getString(R.string.op_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
                                shortName = existing?.shortName.orEmpty(),
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
        private val onLongClick: (InstanceStatusRow) -> Unit,
        private val onGridLongClick: (InstanceStatusRow) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val data = mutableListOf<InstanceStatusRow>()

        fun submit(list: List<InstanceStatusRow>) {
            data.clear(); data.addAll(list); notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = if (isGridLayout) TYPE_GRID else TYPE_LIST

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_GRID) {
                val binding = ItemSimpleCardGridBinding.inflate(layoutInflater, parent, false)
                GridVH(binding)
            } else {
                val binding = ItemSimpleCardBinding.inflate(layoutInflater, parent, false)
                ListVH(binding)
            }
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = data[position]
            when (holder) {
                is ListVH -> bindListRow(holder, row)
                is GridVH -> bindGridRow(holder, row)
            }
        }

        private fun bindListRow(h: ListVH, row: InstanceStatusRow) {
            val item = row.instance
            h.ib.tvName.text = item.name
            val base = buildString {
                append(typeNames[item.typeId].orEmpty())
                if (item.deviceCode.isNotBlank()) append(" · 编号:${item.deviceCode}")
                if (item.location.isNotBlank()) append(" · ${item.location}")
                if (item.installer.isNotBlank()) append(" · 安装:${item.installer}")
            }
            val midPart = "  待测 ${row.pendingTests} · "
            val failPart = "未通过 ${row.failedTests}"
            val faultPart = " · 待处理故障 ${row.pendingFaults}"
            val ssb = SpannableStringBuilder(base).append(midPart).append(failPart).append(faultPart)
            if (row.pendingTests > 0) {
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#B8860B")),
                    base.length, base.length + midPart.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (row.failedTests > 0 || row.pendingFaults > 0) {
                val start = base.length + midPart.length
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

        private fun bindGridRow(h: GridVH, row: InstanceStatusRow) {
            val item = row.instance
            // 优先显示精简名
            val displayName = item.shortName.ifBlank { item.name }
            h.ib.tvGridName.text = displayName
            h.ib.tvGridName.contentDescription = item.name
            h.ib.tvGridPending.text = row.pendingTests.toString()
            h.ib.tvGridFaults.text = row.pendingFaults.toString()
            h.ib.root.setOnClickListener { onClick(row) }
            h.ib.root.setOnLongClickListener { onGridLongClick(row); true }
        }

        inner class ListVH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)
        inner class GridVH(val ib: ItemSimpleCardGridBinding) : RecyclerView.ViewHolder(ib.root)
    }
}
