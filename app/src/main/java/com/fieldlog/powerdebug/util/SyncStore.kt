package com.fieldlog.powerdebug.util

import android.content.Context

/**
 * 同步配置与登录会话存取。
 * 说明：按需求"低敏感信息不做高端验证"，WebDAV账密以明文存于应用私有目录，
 * 不参与系统备份以外的任何传输；超级密码用于离线直接注册测试员。
 */
object SyncStore {
    const val SUPER_PASSWORD = "mz9890517"

    private const val FILE = "sync_prefs"
    private const val K_URL = "webdav_url"
    private const val K_USER = "webdav_user"
    private const val K_PASS = "webdav_pass"
    private const val K_CURRENT = "current_user"
    private const val K_AUTO = "auto_upload"

    data class Config(val url: String, val user: String, val pass: String)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 已配置的WebDAV连接；未配置返回null */
    fun config(ctx: Context): Config? {
        val p = prefs(ctx)
        val url = p.getString(K_URL, "").orEmpty().trim()
        val user = p.getString(K_USER, "").orEmpty().trim()
        if (url.isEmpty() || user.isEmpty()) return null
        return Config(url, user, p.getString(K_PASS, "").orEmpty())
    }

    fun saveConfig(ctx: Context, url: String, user: String, pass: String) {
        prefs(ctx).edit()
            .putString(K_URL, url.trim())
            .putString(K_USER, user.trim())
            .putString(K_PASS, pass)
            .apply()
    }

    fun clearConfig(ctx: Context) {
        prefs(ctx).edit().remove(K_URL).remove(K_USER).remove(K_PASS).apply()
    }

    /** 当前登录测试员账号（即WebDAV用户名）；未登录为null */
    fun currentUser(ctx: Context): String? =
        prefs(ctx).getString(K_CURRENT, "").orEmpty().ifBlank { null }

    fun setCurrentUser(ctx: Context, user: String?) {
        prefs(ctx).edit().putString(K_CURRENT, user.orEmpty()).apply()
    }

    /** 保存日志后是否自动上传快照 */
    fun autoUpload(ctx: Context): Boolean = prefs(ctx).getBoolean(K_AUTO, false)

    fun setAutoUpload(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(K_AUTO, value).apply()
    }
}
