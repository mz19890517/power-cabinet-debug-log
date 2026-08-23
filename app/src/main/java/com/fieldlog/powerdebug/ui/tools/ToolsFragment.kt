package com.fieldlog.powerdebug.ui.tools

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog.Builder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.core.WebDavClient
import com.fieldlog.powerdebug.core.XlsxWriter
import com.fieldlog.powerdebug.data.Repository
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.data.db.TesterAccount
import com.fieldlog.powerdebug.databinding.FragmentToolsBinding
import com.fieldlog.powerdebug.util.DT
import com.fieldlog.powerdebug.util.SyncStore
import com.fieldlog.powerdebug.util.WebDavSync
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

        // ---- 账号与同步 ----
        b.btnLogin.setOnClickListener { showLoginDialog() }
        b.btnSwitchUser.setOnClickListener { showSwitchUserDialog() }
        b.btnLogout.setOnClickListener {
            SyncStore.setCurrentUser(requireContext(), null)
            refreshAccountUI()
            Toast.makeText(requireContext(), R.string.sync_logged_out, Toast.LENGTH_SHORT).show()
        }
        b.swAutoUpload.isChecked = SyncStore.autoUpload(requireContext())
        b.swAutoUpload.setOnCheckedChangeListener { _, checked ->
            SyncStore.setAutoUpload(requireContext(), checked)
        }
        b.btnSyncNow.setOnClickListener {
            val ctx = requireContext()
            if (SyncStore.currentUser(ctx) == null) {
                toast("请先登录测试账号"); return@setOnClickListener
            }
            toast("正在双向同步…")
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    toast(WebDavSync.syncAll(ctx))
                    refreshStats()
                } catch (e: Exception) {
                    toast(e.message ?: "同步失败")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        refreshAccountUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ---------- 账号状态展示 ----------

    private fun refreshAccountUI() {
        val ctx = requireContext()
        val user = SyncStore.currentUser(ctx)
        b.tvCurrentUser.text =
            if (user == null) "当前测试员：${getString(R.string.not_logged_in)}（日志不记录归属账号）"
            else "当前测试员：$user"
        val cfg = SyncStore.config(ctx)
        b.tvWebdavStatus.text =
            if (cfg == null) "WebDAV：未配置（仅本地身份标记）"
            else "WebDAV：${cfg.url}"
    }

    /**
     * 登录/添加测试员。密码=超级口令 → 离线直接注册；否则走WebDAV验证。
     * 弹窗内提供「测试连接」：显示完整诊断报告且不关闭弹窗；
     * 登录失败时同样把诊断报告写入弹窗，便于把失败原因发给开发者。
     */
    private fun showLoginDialog(existingUsername: String = "") {
        val ctx = requireContext()
        val dlgView = layoutInflater.inflate(R.layout.dialog_login, null)
        val etServer = dlgView.findViewById<EditText>(R.id.etServer)
        val etUser = dlgView.findViewById<EditText>(R.id.etUsername)
        val etPass = dlgView.findViewById<EditText>(R.id.etPassword)
        val btnTest = dlgView.findViewById<View>(R.id.btnTestConn)
        val tvDiag = dlgView.findViewById<TextView>(R.id.tvDiagResult)

        SyncStore.config(ctx)?.let {
            etServer.setText(it.url); etUser.setText(it.user)
        } ?: run { if (existingUsername.isNotEmpty()) etUser.setText(existingUsername) }

        fun currentClient(): WebDavClient? {
            val user = etUser.text?.toString()?.trim().orEmpty()
            val pass = etPass.text?.toString() ?: ""
            val url = etServer.text?.toString()?.trim().orEmpty()
            if (user.isEmpty() || url.isEmpty()) return null
            return WebDavClient(url, user, pass)
        }

        btnTest.setOnClickListener {
            val cl = currentClient()
            if (cl == null) {
                tvDiag.visibility = View.VISIBLE
                tvDiag.text = "请先填写服务器地址和账号"
                return@setOnClickListener
            }
            tvDiag.visibility = View.VISIBLE
            tvDiag.text = "正在测试连接…"
            viewLifecycleOwner.lifecycleScope.launch {
                val report = withContext(Dispatchers.IO) { cl.diagnose() }
                tvDiag.text = report
            }
        }

        val dlg = Builder(ctx)
            .setTitle(R.string.sync_login)
            .setView(dlgView)
            .setPositiveButton(R.string.confirm, null) // 点击行为手动接管，失败时不关闭
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun tryLoginOrShowDiag() {
            val username = etUser.text?.toString()?.trim().orEmpty()
            val pass = etPass.text?.toString()?.trim() ?: ""
            val isSuper = pass == SyncStore.SUPER_PASSWORD

            fun showTip(msg: String) {
                tvDiag.visibility = View.VISIBLE
                tvDiag.text = msg
            }

            // 超级口令优先判定（trim后精确匹配），无需服务器地址
            if (isSuper) {
                if (username.isEmpty()) {
                    showTip("✅ 已识别超级口令。\n请再在「账号」栏填写要注册的测试员姓名（如：张三），然后点「确认」，即在本机注册该测试员身份（离线可用，不上传）。\n注：超级口令的作用就是免服务器注册测试员，没有单独的管理界面。")
                    etUser.requestFocus()
                    return
                }
                lifecycleScope.launch {
                    App.repo.registerTester(username, TesterAccount.SOURCE_SUPER)
                    SyncStore.setCurrentUser(ctx, username)
                    refreshAccountUI()
                    toast(getString(R.string.sync_login_super, username))
                    dlg.dismiss()
                }
                return
            }

            if (username.isEmpty()) {
                showTip("请先在「账号」栏填写测试员姓名。\n· 有WebDAV：再填服务器地址和密码后确认登录\n· 无服务器：密码栏输入超级口令即可离线注册")
                return
            }

            val url = etServer.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                showTip("请填写服务器地址，或使用超级口令（密码长度应为${SyncStore.SUPER_PASSWORD.length}位；当前收到${pass.length}位）。")
                return
            }
            toast(R.string.sync_verifying)
            lifecycleScope.launch {
                val cl = WebDavClient(url, username, pass)
                val ok = try {
                    withContext(Dispatchers.IO) { cl.verify(); true }
                } catch (_: Exception) { false }
                if (ok) {
                    SyncStore.saveConfig(ctx, url, username, pass)
                    App.repo.registerTester(username, TesterAccount.SOURCE_WEBDAV)
                    SyncStore.setCurrentUser(ctx, username)
                    refreshAccountUI()
                    toast(getString(R.string.sync_login_ok, username))
                    dlg.dismiss()
                } else {
                    // 失败：完整诊断留在弹窗内，可复制发回
                    tvDiag.visibility = View.VISIBLE
                    tvDiag.text = withContext(Dispatchers.IO) { cl.diagnose() }
                        .plus("\n(发送的密码长度 ${pass.length} 位)")
                }
            }
        }

        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { tryLoginOrShowDiag() }
    }

    /** 从本机已沉淀的测试员中切换归属身份 */
    private fun showSwitchUserDialog() {
        lifecycleScope.launch {
            val accounts = App.repo.testerAccounts()
            if (accounts.isEmpty()) {
                toast("暂无已注册测试员，请先登录")
                return@launch
            }
            val names = accounts.map { it.username }.toTypedArray()
            val cur = SyncStore.currentUser(requireContext())
            val checked = names.indexOf(cur)
            Builder(requireContext())
                .setTitle(R.string.sync_switch)
                .setSingleChoiceItems(names, checked) { dlg, which ->
                    SyncStore.setCurrentUser(requireContext(), names[which])
                    refreshAccountUI()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---------- Excel 导出 ----------

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    private fun refreshStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val s = App.repo.stats()
            b.statProjects.text = s.projects.toString()
            b.statTypes.text = s.types.toString()
            b.statLogs.text = s.logs.toString()
            b.statPending.text = s.pendingFaults.toString()
        }
    }

    private fun toast(resId: Int) =
        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show()

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
                it.log.createdBy,
                it.log.updatedBy,
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
                    "测试内容", "测试人员", "备注", "安装人员", "创建账号", "修改账号",
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
                AlertDialog.Builder(requireContext())
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
                toast(R.string.restore_bad_file)
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
