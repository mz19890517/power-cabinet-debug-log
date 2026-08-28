package com.fieldlog.powerdebug.util

import android.content.Context
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.core.WebDavClient
import com.fieldlog.powerdebug.data.MergeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * WebDAV同步编排（团队互通模式）：
 * 所有手机配置同一个WebDAV目录；每台手机上传自己的快照 backup_<账号>_<本机标识>.json，
 * 同时把目录下其他人的快照全部智能合并进本机——记录自动流动，无需手动搬运。
 * 文件名含每台设备唯一的随机标识：同一账号多台手机同时使用也不会互相覆盖。
 * 快照以 gzip 压缩上传（v2.10起），读取按魔数自动识别新旧格式；
 * 合并规则：按UUID去重、同ID冲突保留updatedAt较新者；删除通过墓碑(deletedItems)传播。
 * 全过程写入 SyncLog，工具页可查看/复制，便于远程排查。
 */
object WebDavSync {

    fun client(ctx: Context): WebDavClient {
        val cfg = SyncStore.config(ctx)
            ?: throw IllegalStateException("请先在「数据工具」配置并登录WebDAV")
        return WebDavClient(cfg.url, cfg.user, cfg.pass)
    }

    private fun fileNameOf(ctx: Context, user: String) =
        "backup_${user}_${SyncStore.deviceTag(ctx)}.json"

    /** gzip压缩（JSON中文文本通常压到1/8~1/15） */
    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
        }.toByteArray()

    /** 按魔数识别gzip（1f 8b）并解压；旧版明文快照原样返回。备份/找回工具共用此识别入口 */
    fun decodeSnapshot(bytes: ByteArray): String {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return if (isGzip)
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        else
            bytes.toString(Charsets.UTF_8)
    }

    private fun kb(n: Int) =
        java.lang.String.format(java.util.Locale.CHINA, "%.1fKB", n / 1024.0)

    /** 生成快照并上传到本机对应的备份文件。 */
    suspend fun uploadSnapshot(ctx: Context): String = withContext(Dispatchers.IO) {
        val user = SyncStore.currentUser(ctx)
            ?: throw IllegalStateException("未登录测试账号")
        val json = App.repo.backupJson()
        val gz = gzip(json.toByteArray(Charsets.UTF_8))
        val name = fileNameOf(ctx, user)
        SyncLog.append(ctx, "上传快照 $name 原始${kb(json.length)}→压缩${kb(gz.size)}")
        client(ctx).upload(name, gz)
        SyncLog.append(ctx, "上传成功 $name")
        name
    }

    /** 下载指定账号的远端快照原文。 */
    suspend fun fetchRemote(ctx: Context, user: String): String = withContext(Dispatchers.IO) {
        decodeSnapshot(client(ctx).download(fileNameOf(ctx, user)))
    }

    /**
     * 完整双向同步：先推自己的快照，再列出目录内其他人的快照逐个合并。
     * @return 摘要文本（供toast展示），失败抛异常。
     */
    suspend fun syncAll(ctx: Context): String = withContext(Dispatchers.IO) {
        val me = SyncStore.currentUser(ctx)
            ?: throw IllegalStateException("未登录测试账号")
        val cl = client(ctx)
        val myTag = SyncStore.deviceTag(ctx)
        SyncLog.append(ctx, "═══ 开始双向同步 ═══ 账号=$me 本机标识=$myTag 目录=${SyncStore.config(ctx)?.url}")

        // 1) 推送自己的最新快照
        val myName = fileNameOf(ctx, me)
        try {
            val json = App.repo.backupJson()
            val gz = gzip(json.toByteArray(Charsets.UTF_8))
            cl.upload(myName, gz)
            SyncLog.append(ctx, "① 已上传我的快照 $myName 原始${kb(json.length)}→压缩${kb(gz.size)}")
        } catch (e: Exception) {
            SyncLog.append(ctx, "① ⚠ 上传失败：$myName ${e.message}")
            throw e
        }

        // 2) 拉取并合并目录内其他所有快照。
        // 只跳过自己本次上传的那份；旧版无标识文件照常合并。
        val files = try {
            cl.listBackups()
        } catch (e: Exception) {
            SyncLog.append(ctx, "② ⚠ 列目录失败：${e.message}")
            return@withContext "已上传 $myName（列目录失败，未合并他人数据）"
        }
        SyncLog.append(
            ctx,
            "② 云端共${files.size}个快照：" +
                (if (files.isEmpty()) "(空)" else files.joinToString(" , "))
        )

        var totNewLogs = 0; var totUpdLogs = 0
        var totNewFaults = 0; var totUpdFaults = 0
        var totOtherNew = 0
        var totTombs = 0
        val parts = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (f in files) {
            if (f == myName) continue
            try {
                val text = decodeSnapshot(cl.download(f))
                val r: MergeResult = App.repo.mergeJson(text)
                SyncLog.append(
                    ctx,
                    "③ 合并 $f → 项目+${r.newProjects}/改${r.updProjects} 类型+${r.newTypes}" +
                        " 柜子+${r.newInstances} 日志+${r.newLogs}/改${r.updLogs} 故障+${r.newFaults}" +
                        " 待测+${r.newPlanned} 调试员+${r.newDebuggers} 删除${r.appliedTombs}"
                )
                if (r.newLogs + r.updLogs + r.newFaults + r.updFaults +
                    r.newProjects + r.updProjects + r.newTypes + r.updTypes +
                    r.newInstances + r.updInstances + r.newCands +
                    r.newPlanned + r.updPlanned +
                    r.newDebuggers + r.updDebuggers + r.appliedTombs > 0
                ) {
                    val who = f.removePrefix("backup_").removeSuffix(".json")
                    parts.add(
                        "$who：日志+${r.newLogs}/改${r.updLogs} 故障+${r.newFaults}" +
                            (if (r.newPlanned + r.updPlanned > 0) " 待测+${r.newPlanned}/改${r.updPlanned}" else "") +
                            (if (r.newDebuggers + r.updDebuggers > 0) " 调试员+${r.newDebuggers}/改${r.updDebuggers}" else "") +
                            (if (r.appliedTombs > 0) " 同步删除${r.appliedTombs}条" else "")
                    )
                }
                totNewLogs += r.newLogs; totUpdLogs += r.updLogs
                totNewFaults += r.newFaults; totUpdFaults += r.updFaults
                totOtherNew += r.newProjects + r.newTypes + r.newInstances + r.newCands +
                    r.newPlanned + r.newDebuggers
                totTombs += r.appliedTombs
            } catch (e: Exception) {
                SyncLog.append(ctx, "③ ⚠ 合并失败 $f：${e.javaClass.simpleName}: ${e.message}")
                errors.add("${f.removePrefix("backup_").removeSuffix(".json")}(${e.message})")
            }
        }

        buildString {
            append("同步完成 ✓\n")
            append("已上传我的快照\n")
            if (parts.isEmpty()) {
                if (totNewLogs + totUpdLogs + totNewFaults + totUpdFaults + totOtherNew + totTombs == 0) {
                    // 区分两种"没动静"：目录里根本没有别人的快照 vs 有但内容都已在本机
                    val others = files.count { it != myName }
                    append(
                        if (others == 0)
                            "云端没有其他手机的快照文件。\n排查：①两台手机WebDAV地址必须完全一致（含末尾文件夹路径）②坚果云需用共享文件夹且成员加入同一文件夹③点「查看同步日志」把内容发给开发者"
                        else "其他人暂无新数据（有${others}份快照但内容都已在本机）"
                    )
                }
            } else append(parts.joinToString("\n"))
            if (totOtherNew > 0) append("\n其他新增条目 $totOtherNew 条")
            if (totTombs > 0) append("\n已按他人删除操作清理本机 $totTombs 条")
            if (errors.isNotEmpty()) append("\n⚠ 部分文件跳过：${errors.joinToString(" ")}")
        }.also { SyncLog.append(ctx, "═══ 同步结束 ═══ ${it.replace("\n", " | ")}") }
    }
}
