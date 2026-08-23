package com.fieldlog.powerdebug.util

import android.content.Context
import com.fieldlog.powerdebug.App
import com.fieldlog.powerdebug.core.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV同步编排：按账号分文件存快照（backup_账号.json），
 * 上传=整库快照覆盖云端自己那份；下载=智能合并进本机，绝不删除本地数据。
 */
object WebDavSync {

    fun client(ctx: Context): WebDavClient {
        val cfg = SyncStore.config(ctx)
            ?: throw IllegalStateException("请先在「数据工具」配置并登录WebDAV")
        return WebDavClient(cfg.url, cfg.user, cfg.pass)
    }

    private fun fileNameOf(user: String) = "backup_${user}.json"

    /** 生成快照并上传到当前账号的备份文件。返回文件名。 */
    suspend fun uploadSnapshot(ctx: Context): String = withContext(Dispatchers.IO) {
        val user = SyncStore.currentUser(ctx)
            ?: throw IllegalStateException("未登录测试账号")
        val json = App.repo.backupJson()
        val name = fileNameOf(user)
        client(ctx).upload(name, json.toByteArray(Charsets.UTF_8))
        name
    }

    /** 下载当前账号的远端快照原文（供预览/合并）。 */
    suspend fun fetchRemote(ctx: Context): String = withContext(Dispatchers.IO) {
        val user = SyncStore.currentUser(ctx)
            ?: throw IllegalStateException("未登录测试账号")
        val bytes = client(ctx).download(fileNameOf(user))
        String(bytes, Charsets.UTF_8)
    }
}
