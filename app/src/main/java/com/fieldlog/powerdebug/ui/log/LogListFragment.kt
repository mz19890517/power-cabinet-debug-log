package com.fieldlog.powerdebug.ui.log

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.LogDeleteMode
import com.fieldlog.powerdebug.data.db.CabinetInstance
import com.fieldlog.powerdebug.data.db.CabinetType
import com.fieldlog.powerdebug.data.db.LogListItem
import com.fieldlog.powerdebug.data.db.Project
import com.fieldlog.powerdebug.databinding.FragmentLogListBinding
import com.fieldlog.powerdebug.databinding.ItemLogBinding
import com.fieldlog.powerdebug.util.DT
import kotlinx.coroutines.launch

class LogListFragment : Fragment() {

    private var _b: FragmentLogListBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: LogAdapter

    private var projects: List<Project> = emptyList()
    private var types: List<CabinetType> = emptyList()
    private var instances: List<CabinetInstance> = emptyList()

    private var selProjectId = ""
    private var selTypeId = ""
    private var selInstanceId = ""
    private var selStatus = -1

    private val handler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { reload() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentLogListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = LogAdapter(
            onClick = { startActivity(logIntent(it.log.id)) },
            onLongClick = { confirmDelete(it) }
        )
        b.rvLogs.layoutManager = LinearLayoutManager(requireContext())
        b.rvLogs.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            App.repo.watchProjects().collect {
                projects = it
                refreshProjectSpinner()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            App.repo.watchTypes().collect {
                types = it
                refreshTypeSpinner()
            }
        }

        b.spStatus.adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_small,
            arrayOf(
                getString(R.string.filter_all_status),
                getString(R.string.filter_pending),
                getString(R.string.filter_resolved)
            )
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spStatus.onItemSelectedListener = selListener { pos ->
            selStatus = intArrayOf(-1, 0, 1)[pos]
            reload()
        }

        b.etCircuitFilter.addTextChangedListener(debounceWatcher)
        b.etTextSearch.addTextChangedListener(debounceWatcher)

        b.fabNewLog.setOnClickListener {
            val intent = Intent(requireContext(), LogEditActivity::class.java)
            if (selInstanceId.isNotEmpty()) intent.putExtra(LogEditActivity.KEY_INSTANCE_ID, selInstanceId)
            startActivity(intent)
        }

        viewLifecycleOwner.lifecycleScope.launch { reloadInstances() }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ---------- 筛选联动 ----------

    private fun refreshProjectSpinner() {
        val labels = mutableListOf(getString(R.string.filter_all_projects))
        projects.forEach { labels.add(it.name) }
        b.spProject.bind(labels) { pos ->
            val newId = projects.getOrNull(pos - 1)?.id ?: ""
            if (newId != selProjectId) {
                selProjectId = newId
                selInstanceId = ""
                viewLifecycleOwner.lifecycleScope.launch { reloadInstances() }
            }
        }
        selectSpinner(b.spProject, projects.indexOfFirst { it.id == selProjectId } + 1)
    }

    private fun refreshTypeSpinner() {
        val labels = mutableListOf(getString(R.string.filter_all_types))
        types.forEach { labels.add(it.name) }
        b.spType.bind(labels) { pos ->
            val newId = types.getOrNull(pos - 1)?.id ?: ""
            if (newId != selTypeId) {
                selTypeId = newId
                selInstanceId = ""
                viewLifecycleOwner.lifecycleScope.launch { reloadInstances() }
            }
        }
        selectSpinner(b.spType, types.indexOfFirst { it.id == selTypeId } + 1)
    }

    private suspend fun reloadInstances() {
        instances = App.db.instanceDao().byProjectAndTypeOnce(selProjectId, selTypeId)
        val labels = mutableListOf(getString(R.string.filter_all_instances))
        instances.forEach { labels.add(it.name) }
        b.spInstance.bind(labels) { pos ->
            val newId = instances.getOrNull(pos - 1)?.id ?: ""
            if (newId != selInstanceId) {
                selInstanceId = newId
                reload()
            }
        }
        selectSpinner(b.spInstance, instances.indexOfFirst { it.id == selInstanceId } + 1)
        reload()
    }

    // ---------- 查询 ----------

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = try {
                App.repo.searchLogs(
                    projectId = selProjectId,
                    typeId = selTypeId,
                    instanceId = selInstanceId,
                    status = selStatus,
                    circuit = b.etCircuitFilter.text?.toString()?.trim().orEmpty(),
                    q = b.etTextSearch.text?.toString()?.trim().orEmpty()
                )
            } catch (e: Exception) {
                emptyList<LogListItem>()
            }
            adapter.submit(list)
            b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDelete(item: LogListItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val linked = App.repo.linkedPlannedOfLog(item.log.id)
            if (linked.isEmpty()) {
                com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                    context = requireContext(),
                    title = R.string.delete,
                    message = "删除「${item.instanceName} · ${item.log.circuit.ifEmpty { getString(R.string.whole_cabinet) }}」这条日志？\n其下故障记录将一并删除。",
                    typeName = "日志"
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        App.repo.deleteLog(item.log.id, LogDeleteMode.RESTORE_PLANNED)
                        reload()
                    }
                }
            } else {
                // 该日志完成了预选待测项：让用户选择重测还是连项删除
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.delete)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(
                        "删除「${item.instanceName} · ${item.log.circuit.ifEmpty { getString(R.string.whole_cabinet) }}」这条日志？\n" +
                            "它完成了 ${linked.size} 项预选待测项目。\n其下故障记录将一并删除。"
                    )
                    .setPositiveButton(R.string.del_log_retest) { _, _ ->
                        com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                            context = requireContext(),
                            title = R.string.delete,
                            message = "确认删除并恢复预选待测项？",
                            typeName = "日志"
                        ) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                App.repo.deleteLog(item.log.id, LogDeleteMode.RESTORE_PLANNED)
                                reload()
                            }
                        }
                    }
                    .setNeutralButton(R.string.del_log_purge) { _, _ ->
                        com.fieldlog.powerdebug.util.DeleteSafeguard.confirmDelete(
                            context = requireContext(),
                            title = R.string.delete,
                            message = "确认删除并连项删除预选待测项？",
                            typeName = "日志"
                        ) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                App.repo.deleteLog(item.log.id, LogDeleteMode.PURGE_PLANNED)
                                reload()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun logIntent(logId: String) =
        Intent(requireContext(), LogEditActivity::class.java)
            .putExtra(LogEditActivity.KEY_LOG_ID, logId)

    // ---------- Spinner 工具 ----------

    private fun Spinner.bind(items: List<String>, onSel: (Int) -> Unit) {
        tag = true // 绑定与静默恢复期间忽略回调
        adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_small, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (tag == true) return
                onSel(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** 静默设置选中位置（避免触发联动） */
    private fun selectSpinner(sp: Spinner, pos: Int) {
        sp.tag = true
        if (pos >= 0 && sp.selectedItemPosition != pos) sp.setSelection(pos, false)
        else sp.tag = false
        sp.post { sp.tag = false }
    }

    private fun selListener(onSel: (Int) -> Unit): AdapterView.OnItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSel(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

    private val debounceWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun afterTextChanged(s: Editable?) {
            handler.removeCallbacks(searchRunnable)
            handler.postDelayed(searchRunnable, 350)
        }
    }
}

class LogAdapter(
    private val onClick: (LogListItem) -> Unit,
    private val onLongClick: (LogListItem) -> Unit
) : RecyclerView.Adapter<LogAdapter.VH>() {

    private val data = mutableListOf<LogListItem>()

    fun submit(list: List<LogListItem>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val ib: ItemLogBinding) : RecyclerView.ViewHolder(ib.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = data[pos]
        val ctx = h.ib.root.context
        val circuitTxt = item.log.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) }
        h.ib.tvTitle.text = "${item.instanceName} · $circuitTxt"
        h.ib.tvDate.text = DT.full(item.log.createdAt) +
            if (item.log.updatedAt > item.log.createdAt) ctx.getString(R.string.edited_marker) else ""

        if (item.pendingCount > 0) {
            h.ib.badgePending.visibility = View.VISIBLE
            h.ib.badgePending.text = "待处理 ×${item.pendingCount}"
        } else h.ib.badgePending.visibility = View.GONE
        if (item.resolvedCount > 0) {
            h.ib.badgeResolved.visibility = View.VISIBLE
            h.ib.badgeResolved.text = "已解决 ×${item.resolvedCount}"
        } else h.ib.badgeResolved.visibility = View.GONE

        h.ib.tvContent.text = item.log.testContent
        val tester = item.log.tester.takeIf { it.isNotBlank() }?.let { " · 测试:$it" }.orEmpty()
        val author = item.log.createdBy.takeIf { it.isNotBlank() && it != item.log.tester }
            ?.let { " · 记录:$it" }.orEmpty()
        h.ib.tvFooter.text = "${item.projectName} · ${item.typeName}$tester$author"

        h.ib.root.setOnClickListener { onClick(item) }
        h.ib.root.setOnLongClickListener { onLongClick(item); true }
    }
}
