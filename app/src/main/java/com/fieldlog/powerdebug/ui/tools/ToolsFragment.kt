package com.fieldlog.powerdebug.ui.tools

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.core.XlsxWriter
import com.fieldlog.powerdebug.data.Repository
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.databinding.FragmentToolsBinding
import com.fieldlog.powerdebug.util.DT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToolsFragment : Fragment() {

    private var _b: FragmentToolsBinding? = null
    private val b get() = _b!!

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri -> uri?.let { doExport(it) } }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(JSON_MIME)
    ) { uri -> uri?.let { doBackup(it) } }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { doRestore(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentToolsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnExport.setOnClickListener {
            exportLauncher.launch("电源柜调试日志_${DT.fileStamp()}.xlsx")
        }
        b.btnBackup.setOnClickListener {
            backupLauncher.launch("电源柜调试备份_${DT.fileStamp()}.json")
        }
        b.btnRestore.setOnClickListener {
            restoreLauncher.launch(arrayOf(JSON_MIME, "text/*", "application/octet-stream"))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun refreshStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val s = App.repo.stats()
            b.statProjects.text = s.projects.toString()
            b.statTypes.text = s.types.toString()
            b.statLogs.text = s.logs.toString()
            b.statPending.text = s.pendingFaults.toString()
        }
    }

    // ---------- Excel 导出 ----------

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    private fun doExport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (logs, faults) = App.repo.collectExport()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        XlsxWriter.write(out, buildSheets(logs, faults))
                    } ?: throw IllegalStateException("无法打开输出流")
                }
                toast(getString(R.string.export_ok, uri.lastPathSegment ?: ""))
            } catch (e: Exception) {
                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun buildSheets(
        logs: List<com.fieldlog.powerdebug.data.db.LogListItem>,
        faults: List<com.fieldlog.powerdebug.data.db.FaultExportRow>
    ): List<XlsxWriter.SheetDef> {
        val ctx = requireContext()
        val logRows = logs.mapIndexed { i, it ->
            listOf(
                (i + 1).toString(),
                it.projectName,
                it.typeName,
                it.instanceName,
                it.deviceCode,
                it.log.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) },
                it.log.testContent,
                it.log.tester,
                it.log.remark,
                if (it.installer.isBlank()) "" else "${it.installer}",
                it.pendingCount.toString(),
                it.resolvedCount.toString(),
                DT.full(it.log.createdAt),
                DT.full(it.log.updatedAt)
            )
        }

        val faultRows = faults.mapIndexed { i, f ->
            listOf(
                (i + 1).toString(),
                f.projectName,
                f.instanceName,
                f.deviceCode,
                f.fault.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) },
                f.fault.symptom,
                f.fault.solution,
                DT.full(f.fault.occurredAt),
                if (f.fault.status == FaultRecord.STATUS_RESOLVED) DT.full(f.fault.resolvedAt) else "",
                ctx.getString(
                    if (f.fault.status == FaultRecord.STATUS_RESOLVED) R.string.fault_status_resolved
                    else R.string.fault_status_pending
                ),
                DT.full(f.fault.occurredAt).ifEmpty { "-" }
            )
        }

        return listOf(
            XlsxWriter.SheetDef(
                name = "调试日志",
                headers = listOf(
                    "序号", "项目", "柜子类型", "实例名称", "设备编号", "回路",
                    "测试内容", "测试人员", "备注", "安装人员",
                    "待处理故障数", "已解决故障数", "记录时间", "更新时间"
                ),
                rows = logRows,
                wrapCols = setOf(6, 8)
            ),
            XlsxWriter.SheetDef(
                name = "故障记录",
                headers = listOf(
                    "序号", "项目", "柜子实例", "设备编号", "故障回路",
                    "问题现象", "解决方法", "发生时间", "解决完成时间", "状态", "关联日志时间"
                ),
                rows = faultRows,
                wrapCols = setOf(5, 6),
                landscape = true
            )
        )
    }

    // ---------- JSON 备份 / 恢复 ----------

    private fun doBackup(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val json = App.repo.backupJson()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法打开输出流")
                }
                toast(getString(R.string.backup_ok))
            } catch (e: Exception) {
                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun doRestore(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IllegalStateException("无法读取文件")
                }
                // 预解析统计，供确认弹窗展示
                val counts = withContext(Dispatchers.Default) { previewBackup(text) }
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.restore_confirm_title)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(
                        getString(
                            R.string.restore_confirm_msg,
                            counts[0], counts[1], counts[2], counts[3], counts[4]
                        )
                    )
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                App.repo.restoreJson(text)
                                toast(getString(R.string.restore_ok))
                                refreshStats()
                            } catch (e: Exception) {
                                toast(getString(R.string.op_failed, e.message ?: e.javaClass.simpleName))
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                toast(getString(R.string.restore_bad_file))
            }
        }
    }

    /** 返回 [项目数, 类型数, 柜子数, 日志数, 故障数] */
    private fun previewBackup(text: String): IntArray {
        val root = org.json.JSONObject(text)
        if (root.optString("app") != Repository.BACKUP_APP_TAG) throw IllegalArgumentException("bad tag")
        fun count(key: String): Int = root.optJSONArray(key)?.length() ?: 0
        return intArrayOf(
            count("projects"), count("cabinetTypes"), count("instances"),
            count("logs"), count("faults")
        )
    }

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val JSON_MIME = "application/json"
    }
}
