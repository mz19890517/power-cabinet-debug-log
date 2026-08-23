package com.fieldlog.powerdebug.ui.log

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.CabinetInstance
import com.fieldlog.powerdebug.data.db.CandidateItem
import com.fieldlog.powerdebug.data.db.DebugLog
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.data.db.LogListItem
import com.fieldlog.powerdebug.data.db.Project
import com.fieldlog.powerdebug.databinding.ActivityLogEditBinding
import com.fieldlog.powerdebug.databinding.DialogFaultBinding
import com.fieldlog.powerdebug.databinding.ItemFaultDraftBinding
import com.fieldlog.powerdebug.util.DT
import kotlinx.coroutines.launch

class LogEditActivity : AppCompatActivity() {

    companion object {
        const val KEY_LOG_ID = "log_id"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_INSTANCE_ID = "instance_id"
    }

    private lateinit var b: ActivityLogEditBinding

    private var projects: List<Project> = emptyList()
    private var typeNames: Map<Long, String> = emptyMap()
    private var curInstances: List<CabinetInstance> = emptyList()

    private var selProjectId = 0L
    private var selInstanceId = 0L

    private var editing: LogListItem? = null
    private val drafts = mutableListOf<FaultRecord>()

    private var poolItems: List<CandidateItem> = emptyList()
    private val checkedPool = mutableSetOf<Long>()
    private lateinit var poolAdapter: PoolAdapter
    private lateinit var faultAdapter: FaultDraftAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogEditBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        poolAdapter = PoolAdapter(checkedPool)
        b.rvPool.layoutManager = LinearLayoutManager(this)
        b.rvPool.adapter = poolAdapter

        faultAdapter = FaultDraftAdapter(
            drafts,
            onClick = { pos -> showFaultDialog(pos) },
            onDelete = { pos ->
                if (pos >= 0 && pos < drafts.size) {
                    drafts.removeAt(pos)
                    faultAdapter.notifyItemRemoved(pos)
                }
            }
        )
        b.rvFaults.layoutManager = LinearLayoutManager(this)
        b.rvFaults.adapter = faultAdapter

        b.btnSelAll.setOnClickListener {
            checkedPool.addAll(poolItems.map { it.id })
            poolAdapter.notifyDataSetChanged()
        }
        b.btnSelClear.setOnClickListener {
            checkedPool.clear()
            poolAdapter.notifyDataSetChanged()
        }
        b.btnFill.setOnClickListener { fillContent() }
        b.btnAddFault.setOnClickListener { showFaultDialog(-1) }
        b.btnSave.setOnClickListener { save() }

        lifecycleScope.launch {
            projects = App.db.projectDao().allOnce()
            typeNames = App.repo.allTypes().associate { it.id to it.name }
            if (projects.isEmpty()) {
                Toast.makeText(this@LogEditActivity, "请先在「设备管理」中创建项目与柜子", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            bindProjectSpinner()
            loadEditingOrPrefill()
        }
    }

    // ---------- 项目/实例两级联动 ----------

    private fun bindProjectSpinner() {
        val labels = projects.map { it.name }
        b.spProject.tag = true
        b.spProject.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        b.spProject.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (b.spProject.tag == true) return
                    val newId = projects.getOrNull(pos)?.id ?: return
                    if (newId != selProjectId && editing == null) {
                        selProjectId = newId
                        selInstanceId = 0L
                        lifecycleScope.launch { reloadInstances() }
                    }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
    }

    private fun selectProjectSilently(projectId: Long) {
        val pos = projects.indexOfFirst { it.id == projectId }
        if (pos >= 0) {
            b.spProject.tag = true
            b.spProject.setSelection(pos, false)
            b.spProject.post { b.spProject.tag = false }
        }
    }

    private fun setupInstanceSpinner(labels: List<String>) {
        b.spInstance.tag = true
        b.spInstance.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        b.spInstance.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (b.spInstance.tag == true) return
                    val inst = curInstances.getOrNull(pos) ?: return
                    if (inst.id != selInstanceId) {
                        selInstanceId = inst.id
                        lifecycleScope.launch { reloadPoolAndCircuits() }
                    }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
    }

    private suspend fun reloadInstances() {
        curInstances = App.db.instanceDao().byProjectOnce(selProjectId)
        val labels = mutableListOf<String>()
        for (i in curInstances) labels.add("${i.name}〔${typeNames[i.typeId].orEmpty()}〕")
        if (curInstances.isEmpty()) labels.add("（该项目暂无柜子，请到设备管理添加）")

        setupInstanceSpinner(labels)
        val idx = curInstances.indexOfFirst { it.id == selInstanceId }
        if (idx >= 0) b.spInstance.setSelection(idx, false)
        b.spInstance.post { b.spInstance.tag = false }

        reloadPoolAndCircuits()
    }

    private suspend fun reloadPoolAndCircuits() {
        val typeId = currentTypeId()
        poolItems = if (typeId > 0) App.db.candidateItemDao().byTypeOnce(typeId) else emptyList()
        checkedPool.retainAll(poolItems.map { it.id }.toSet())
        poolAdapter.items = poolItems
        poolAdapter.notifyDataSetChanged()
        b.tvPoolEmpty.visibility = if (poolItems.isEmpty()) View.VISIBLE else View.GONE

        val circuits = App.repo.distinctCircuits(selProjectId, typeId)
        b.actCircuit.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, circuits)
        )
    }

    private suspend fun currentTypeId(): Long =
        App.repo.getInstance(selInstanceId)?.typeId ?: 0L

    // ---------- 装载编辑数据 / 预选 ----------

    private suspend fun loadEditingOrPrefill() {
        val logId = intent.getLongExtra(KEY_LOG_ID, -1L)
        if (logId > 0) {
            editing = App.repo.getLogDetail(logId)
            val d = editing
            if (d == null) {
                Toast.makeText(this, "日志不存在", Toast.LENGTH_SHORT).show()
                finish(); return
            }
            b.toolbar.title = getString(R.string.log_edit_title)
            selInstanceId = d.log.instanceId
            val inst = App.repo.getInstance(d.log.instanceId)
            selProjectId = inst?.projectId ?: 0L
            selectProjectSilently(selProjectId)

            curInstances = App.db.instanceDao().byProjectOnce(selProjectId)
            val labels = curInstances.map { "${it.name}〔${typeNames[it.typeId].orEmpty()}〕" }.toMutableList()
            if (labels.isEmpty()) labels.add(inst?.name.orEmpty())
            setupInstanceSpinner(labels)
            val iPos = curInstances.indexOfFirst { it.id == selInstanceId }
            if (iPos >= 0) b.spInstance.setSelection(iPos, false)
            b.spInstance.post { b.spInstance.tag = false }

            b.actCircuit.setText(d.log.circuit)
            b.etContent.setText(d.log.testContent)
            b.etTester.setText(d.log.tester)
            b.etRemark.setText(d.log.remark)
            drafts.addAll(App.repo.faultsOf(d.log.id))
            faultAdapter.notifyDataSetChanged()

            reloadPoolAndCircuits()
        } else {
            b.toolbar.title = getString(R.string.log_new_title)
            val pid = intent.getLongExtra(KEY_PROJECT_ID, 0L)
            val iid = intent.getLongExtra(KEY_INSTANCE_ID, 0L)
            selProjectId = if (pid > 0 && projects.any { it.id == pid }) pid
            else projects.firstOrNull()?.id ?: 0L
            selectProjectSilently(selProjectId)

            if (iid > 0) {
                val target = App.repo.getInstance(iid)
                if (target != null && target.projectId != selProjectId) {
                    selProjectId = target.projectId
                    selectProjectSilently(selProjectId)
                }
                selInstanceId = iid
            } else {
                selInstanceId =
                    App.db.instanceDao().byProjectOnce(selProjectId).firstOrNull()?.id ?: 0L
            }
            reloadInstances()
        }
    }

    // ---------- 候选池填充 ----------

    private fun fillContent() {
        val picked = poolItems.filter { it.id in checkedPool }.map { it.content }
        if (picked.isEmpty()) {
            Toast.makeText(this, "请先勾选测试项", Toast.LENGTH_SHORT).show()
            return
        }
        val existing = b.etContent.text?.toString().orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val existingSet = existing.toHashSet()
        val add = picked.filter { it.trim() !in existingSet }
        if (add.isEmpty()) {
            Toast.makeText(this, "所选测试项已在内容中", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = existing + add
        b.etContent.setText(lines.joinToString("\n"))
        b.etContent.setSelection(b.etContent.text?.length ?: 0)
    }

    // ---------- 故障弹窗 ----------

    private fun showFaultDialog(position: Int) {
        val editingFault = drafts.getOrNull(position)
        val dlg = DialogFaultBinding.inflate(layoutInflater)

        dlg.tvTitle.text =
            if (editingFault == null) getString(R.string.fault_title_add)
            else getString(R.string.fault_title_edit)

        var occurredAt = editingFault?.occurredAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
        var resolvedAt = editingFault?.resolvedAt ?: 0L
        var status = editingFault?.status ?: FaultRecord.STATUS_PENDING

        lifecycleScope.launch {
            val circuits = App.repo.distinctCircuits(selProjectId, currentTypeId())
            dlg.actFaultCircuit.setAdapter(
                ArrayAdapter(this@LogEditActivity, android.R.layout.simple_list_item_1, circuits)
            )
        }
        dlg.actFaultCircuit.setText(editingFault?.circuit ?: b.actCircuit.text?.toString().orEmpty(), false)
        dlg.etSymptom.setText(editingFault?.symptom.orEmpty())
        dlg.etSolution.setText(editingFault?.solution.orEmpty())

        fun refreshTimes() {
            dlg.btnOccurTime.text = DT.full(occurredAt).ifEmpty { getString(R.string.fault_occur_time) }
            dlg.btnResolveTime.text = DT.full(resolvedAt).ifEmpty { getString(R.string.fault_resolve_time) }
            dlg.swResolved.isChecked = status == FaultRecord.STATUS_RESOLVED
            dlg.btnResolveTime.isEnabled = status == FaultRecord.STATUS_RESOLVED
        }
        refreshTimes()

        dlg.btnOccurTime.setOnClickListener {
            DT.pick(this, occurredAt) { occurredAt = it; refreshTimes() }
        }
        dlg.btnResolveTime.setOnClickListener {
            DT.pick(this, resolvedAt) { resolvedAt = it; refreshTimes() }
        }
        dlg.swResolved.setOnCheckedChangeListener { _, isChecked ->
            status = if (isChecked) FaultRecord.STATUS_RESOLVED else FaultRecord.STATUS_PENDING
            if (isChecked && resolvedAt == 0L) resolvedAt = System.currentTimeMillis()
            refreshTimes()
        }

        AlertDialog.Builder(this)
            .setView(dlg.root)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val symptom = dlg.etSymptom.text?.toString()?.trim().orEmpty()
                if (symptom.isEmpty()) {
                    Toast.makeText(this, R.string.err_symptom_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val f = editingFault ?: FaultRecord()
                f.circuit = dlg.actFaultCircuit.text?.toString()?.trim().orEmpty()
                f.symptom = symptom
                f.solution = dlg.etSolution.text?.toString()?.trim().orEmpty()
                f.occurredAt = occurredAt
                f.resolvedAt = if (status == FaultRecord.STATUS_RESOLVED) resolvedAt else 0L
                f.status = status
                if (editingFault == null) {
                    drafts.add(f)
                    faultAdapter.notifyItemInserted(drafts.size - 1)
                } else {
                    faultAdapter.notifyItemChanged(position)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 保存 ----------

    private fun save() {
        if (selInstanceId <= 0) {
            Toast.makeText(this, R.string.err_pick_instance, Toast.LENGTH_SHORT).show()
            return
        }
        val content = b.etContent.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) {
            Toast.makeText(this, R.string.err_content_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val old = editing?.log
        val log = DebugLog(
            id = old?.id ?: 0L,
            instanceId = selInstanceId,
            circuit = b.actCircuit.text?.toString()?.trim().orEmpty(),
            testContent = content,
            tester = b.etTester.text?.toString()?.trim().orEmpty(),
            remark = b.etRemark.text?.toString()?.trim().orEmpty(),
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        lifecycleScope.launch {
            try {
                App.repo.saveLog(log, drafts.toList())
                Toast.makeText(this@LogEditActivity, R.string.saved_ok, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@LogEditActivity, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// ---------- 候选池适配器 ----------

private class PoolAdapter(private val checked: MutableSet<Long>) :
    RecyclerView.Adapter<PoolAdapter.VH>() {

    var items: List<CandidateItem> = emptyList()

    class VH(val cb: CheckBox) : RecyclerView.ViewHolder(cb)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val density = parent.context.resources.displayMetrics.density
        return VH(CheckBox(parent.context).apply {
            setPadding((12 * density).toInt(), (7 * density).toInt(), (4 * density).toInt(), (7 * density).toInt())
            textSize = 13f
        })
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.cb.setOnCheckedChangeListener(null)
        h.cb.text = item.content
        h.cb.isChecked = item.id in checked
        h.cb.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checked.add(item.id) else checked.remove(item.id)
        }
    }
}

// ---------- 故障草稿适配器 ----------

private class FaultDraftAdapter(
    private val items: List<FaultRecord>,
    private val onClick: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<FaultDraftAdapter.VH>() {

    class VH(val ib: ItemFaultDraftBinding) : RecyclerView.ViewHolder(ib.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFaultDraftBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val f = items[pos]
        val ctx = h.ib.root.context
        h.ib.tvCircuit.text = f.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) }
        val pending = f.status == FaultRecord.STATUS_PENDING
        h.ib.tvStatus.text = ctx.getString(
            if (pending) R.string.fault_status_pending else R.string.fault_status_resolved
        )
        h.ib.tvStatus.setTextColor(
            ctx.getColor(if (pending) R.color.pending_fg else R.color.resolved_fg)
        )
        h.ib.statusBar.setBackgroundColor(
            ctx.getColor(if (pending) R.color.pending_fg else R.color.resolved_fg)
        )
        h.ib.tvSymptom.text = f.symptom
        h.ib.tvTimes.text = buildString {
            append(DT.full(f.occurredAt))
            append(" 发生")
            if (!pending) {
                append(" · ")
                append(DT.full(f.resolvedAt))
                append(" 解决")
            }
        }
        h.ib.root.setOnClickListener { onClick(pos) }
        h.ib.btnDelFault.setOnClickListener { onDelete(pos) }
    }
}
