package com.fieldlog.powerdebug.util

import android.content.Context
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.core.WebDavClient
import com.fieldlog.powerdebug.data.MergeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV同步编排（团队互通模式）：
 * 所有手机配置同一个WebDAV目录；每台手机上传自己的快照 backup_<账号>_<本机标识>.json，
 * 同时把目录下其他人的快照全部智能合并进本机——记录自动流动，无需手动搬运。
 * 文件名含每台设备唯一的随机标识：同一账号多台手机同时使用也不会互相覆盖。
 * 合并规则：按UUID去重、同ID冲突保留updatedAt较新者、绝不删除本地数据。
 */
object WebDavSync {

    fun client(ctx: Context): WebDavClient {
        val cfg = SyncStore.config(ctx)
            ?: throw IllegalStateException("请先在「数据工具」配置并登录WebDAV")
        return WebDavClient(cfg.url, cfg.user, cfg.pass)
    }

    private fun fileNameOf(ctx: Context, user: String) =
        "backup_${user}_${SyncStore.deviceTag(ctx)}.json"

    /** 生成快照并上传到本机对应的备份文件。 */
    suspend fun uploadSnapshot(ctx: Context): String = withContext(Dispatchers.IO) {
        val user = SyncStore.currentUser(ctx)
            ?: throw IllegalStateException("未登录测试账号")
        val json = App.repo.backupJson()
        val name = fileNameOf(ctx, user)
        client(ctx).upload(name, json.toByteArray(Charsets.UTF_8))
        name
    }

    /** 下载指定账号的远端快照原文。 */
    suspend fun fetchRemote(ctx: Context, user: String): String = withContext(Dispatchers.IO) {
        val bytes = client(ctx).download(fileNameOf(ctx, user))
        String(bytes, Charsets.UTF_8)
    }

    /**
     * 完整双向同步：先推自己的快照，再列出目录内其他人的快照逐个合并。
     * @return 摘要文本（供toast展示），失败抛异常。
     */
    suspend fun syncAll(ctx: Context): String = withContext(Dispatchers.IO) {
        val me = SyncStore.currentUser(ctx)
            ?: throw IllegalStateException("未登录测试账号")
        val cl = client(ctx)

        // 1) 推送自己的最新快照
        val myName = fileNameOf(ctx, me)
        cl.upload(myName, App.repo.backupJson().toByteArray(Charsets.UTF_8))

        // 2) 拉取并合并目录内其他所有快照。
        // 只跳过自己本次上传的那份；旧版无标识文件（可能来自同账号的另一台手机）照常合并——
        // 合并语义保证无害：同id新者胜、绝不删除本地数据。
        val files = try {
            cl.listBackups()
        } catch (_: Exception) {
            return@withContext "已上传 $myName（列目录失败，未合并他人数据）"
        }

        var totNewLogs = 0; var totUpdLogs = 0
        var totNewFaults = 0; var totUpdFaults = 0
        var totOtherNew = 0
        val parts = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (f in files) {
            if (f == myName) continue
            try {
                val text = String(cl.download(f), Charsets.UTF_8)
                val r: MergeResult = App.repo.mergeJson(text)
                if (r.newLogs + r.updLogs + r.newFaults + r.updFaults +
                    r.newProjects + r.updProjects + r.newTypes + r.updTypes +
                    r.newInstances + r.updInstances + r.newCands +
                    r.newPlanned + r.updPlanned +
                    r.newDebuggers + r.updDebuggers > 0
                ) {
                    val who = f.removePrefix("backup_").removeSuffix(".json")
                    parts.add(
                        "$who：日志+${r.newLogs}/改${r.updLogs} 故障+${r.newFaults}" +
                            (if (r.newPlanned + r.updPlanned > 0) " 待测+${r.newPlanned}/改${r.updPlanned}" else "") +
                            (if (r.newDebuggers + r.updDebuggers > 0) " 调试员+${r.newDebuggers}/改${r.updDebuggers}" else "")
                    )
                }
                totNewLogs += r.newLogs; totUpdLogs += r.updLogs
                totNewFaults += r.newFaults; totUpdFaults += r.updFaults
                totOtherNew += r.newProjects + r.newTypes + r.newInstances + r.newCands +
                    r.newPlanned + r.newDebuggers
            } catch (e: Exception) {
                errors.add("${f.removePrefix("backup_").removeSuffix(".json")}(${e.message})")
            }
        }

        buildString {
            append("同步完成 ✓\n")
            append("已上传我的快照\n")
            if (parts.isEmpty()) {
                if (totNewLogs + totUpdLogs + totNewFaults + totUpdFaults + totOtherNew == 0) {
                    // 区分两种"没动静"：目录里根本没有别人的快照 vs 有但内容都已在本机
                    val others = files.count { it != myName }
                    append(
                        if (others == 0)
                            "目录里没有其他测试员的快照。\n请确认所有手机配置的是同一个共享文件夹地址（坚果云需用共享文件夹，各账号独立空间互不可见）。"
                        else "其他人暂无新数据"
                    )
                }
            } else append(parts.joinToString("\n"))
            if (totOtherNew > 0) append("\n其他新增条目 $totOtherNew 条")
            if (errors.isNotEmpty()) append("\n⚠ 部分文件跳过：${errors.joinToString(" ")}")
        }
    }
}
