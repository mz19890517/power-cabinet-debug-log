package com.fieldlog.powerdebug.ui.device

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.ProjectListItem
import com.fieldlog.powerdebug.data.db.TypeListItem
import com.fieldlog.powerdebug.databinding.FragmentDeviceBinding
import com.fieldlog.powerdebug.databinding.ItemSimpleCardBinding
import kotlinx.coroutines.launch

class DeviceFragment : Fragment() {

    private var _b: FragmentDeviceBinding? = null
    private val b get() = _b!!

    private lateinit var projectAdapter: ProjectAdapter
    private lateinit var typeAdapter: TypeAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentDeviceBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        projectAdapter = ProjectAdapter(
            onClick = { startActivity(ProjectDetailActivity.intent(requireContext(), it.project.id)) },
            onLongClick = { showProjectMenu(it) }
        )
        b.rvProjects.layoutManager = LinearLayoutManager(requireContext())
        b.rvProjects.adapter = projectAdapter

        typeAdapter = TypeAdapter(
            onClick = { startActivity(TypeDetailActivity.intent(requireContext(), it.type.id)) },
            onLongClick = { showTypeMenu(it) }
        )
        b.rvTypes.layoutManager = LinearLayoutManager(requireContext())
        b.rvTypes.adapter = typeAdapter

        App.repo.watchProjectItems().observe(viewLifecycleOwner) {
            projectAdapter.submit(it)
            b.tvEmptyProjects.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }
        App.repo.watchTypeItems().observe(viewLifecycleOwner) {
            typeAdapter.submit(it)
            b.tvEmptyTypes.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }

        b.btnAddProject.setOnClickListener { editProjectDialog(null) }
        b.btnAddType.setOnClickListener { editTypeDialog(null) }

        b.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val showProjects = tab.position == 0
                b.pageProjects.visibility = if (showProjects) View.VISIBLE else View.GONE
                b.pageTypes.visibility = if (showProjects) View.GONE else View.VISIBLE
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ---------- 项目 ----------

    private fun showProjectMenu(item: ProjectListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.project.name)
            .setItems(arrayOf(getString(R.string.edit_project), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> editProjectDialog(item.project)
                    1 -> confirmDeleteProject(item)
                }
            }
            .show()
    }

    private fun editProjectDialog(existing: com.fieldlog.powerdebug.data.db.Project?) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_input_multiline, null)
        val prompt = dlgView.findViewById<android.widget.TextView>(R.id.tv_prompt)
        val input = dlgView.findViewById<android.widget.EditText>(R.id.et_input)
        prompt.text = getString(R.string.project_name) + "\n" + getString(R.string.project_code) + "\n" + getString(R.string.project_remark)
        existing?.let {
            input.setText("${it.name}\n${it.code}\n${it.remark}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) R.string.new_project else R.string.edit_project)
            .setView(dlgView)
            .setPositiveButton(R.string.save) { _, _ ->
                val lines = input.text?.toString()?.lines().orEmpty()
                val name = lines.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), R.string.name_required, android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val code = lines.getOrNull(1)?.trim().orEmpty()
                val remark = lines.drop(2).joinToString("\n").trim()
                viewLifecycleOwner.lifecycleScope.launch {
                    App.repo.saveProject(
                        com.fieldlog.powerdebug.data.db.Project(
                            id = existing?.id ?: 0L, name = name,
                            code = code, remark = remark,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteProject(item: ProjectListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.warn_del_project, item.project.name, item.cabinetCount))
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { App.repo.deleteProject(item.project.id) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 类型 ----------

    private fun showTypeMenu(item: TypeListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.type.name)
            .setItems(arrayOf(getString(R.string.edit_type), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> editTypeDialog(item.type)
                    1 -> confirmDeleteType(item)
                }
            }
            .show()
    }

    private fun editTypeDialog(existing: com.fieldlog.powerdebug.data.db.CabinetType?) {
        val dlgView = layoutInflater.inflate(R.layout.dialog_input_multiline, null)
        val prompt = dlgView.findViewById<android.widget.TextView>(R.id.tv_prompt)
        val input = dlgView.findViewById<android.widget.EditText>(R.id.et_input)
        input.minLines = 2
        prompt.text = getString(R.string.type_name) + "\n" + getString(R.string.type_remark_hint)
        existing?.let { input.setText("${it.name}\n${it.remark}") }
        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) R.string.new_type else R.string.edit_type)
            .setView(dlgView)
            .setPositiveButton(R.string.save) { _, _ ->
                val lines = input.text?.toString()?.lines().orEmpty()
                val name = lines.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), R.string.name_required, android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val remark = lines.drop(1).joinToString("\n").trim()
                viewLifecycleOwner.lifecycleScope.launch {
                    App.repo.saveType(
                        com.fieldlog.powerdebug.data.db.CabinetType(
                            id = existing?.id ?: 0L, name = name, remark = remark,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteType(item: TypeListItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.warn_del_type, item.type.name, item.instanceCount))
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { App.repo.deleteType(item.type.id) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

// ---------- 适配器 ----------

private class ProjectAdapter(
    private val onClick: (ProjectListItem) -> Unit,
    private val onLongClick: (ProjectListItem) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    private val data = mutableListOf<ProjectListItem>()

    fun submit(list: List<ProjectListItem>) {
        data.clear(); data.addAll(list); notifyDataSetChanged()
    }

    class VH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSimpleCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = data[pos]
        h.ib.tvName.text = it.project.name
        h.ib.tvSub.text = buildString {
            append(getString2(h, R.string.cabinets_fmt, it.cabinetCount, it.logCount))
            if (it.project.code.isNotBlank()) append(" · ${it.project.code}")
            if (it.project.remark.isNotBlank()) append(" · ${it.project.remark}")
        }
        h.ib.root.setOnClickListener { onClick(it) }
        h.ib.root.setOnLongClickListener { onLongClick(it); true }
    }

    private fun getString2(h: VH, res: Int, a: Int, bb: Int): String =
        h.ib.root.context.getString(res, a, bb)
}

private class TypeAdapter(
    private val onClick: (TypeListItem) -> Unit,
    private val onLongClick: (TypeListItem) -> Unit
) : RecyclerView.Adapter<TypeAdapter.VH>() {

    private val data = mutableListOf<TypeListItem>()

    fun submit(list: List<TypeListItem>) {
        data.clear(); data.addAll(list); notifyDataSetChanged()
    }

    class VH(val ib: ItemSimpleCardBinding) : RecyclerView.ViewHolder(ib.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSimpleCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = data[pos]
        val ctx = h.ib.root.context
        h.ib.tvName.text = it.type.name
        h.ib.tvSub.text = ctx.getString(R.string.type_stat_fmt, it.itemCount, it.instanceCount)
            .let { s -> if (it.type.remark.isBlank()) s else "$s · ${it.type.remark}" }
        h.ib.root.setOnClickListener { onClick(it) }
        h.ib.root.setOnLongClickListener { onLongClick(it); true }
    }
}
