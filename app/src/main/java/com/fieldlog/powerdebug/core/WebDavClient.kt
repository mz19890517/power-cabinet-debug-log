package com.fieldlog.powerdebug.core

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 极简WebDAV客户端：仅用JDK标准库实现 PROPFIND(验证连通)/PUT(上传)/GET(下载)。
 * Basic认证；超时10s。不解析响应体XML（本应用无需列目录）。
 */
class WebDavClient(
    baseUrlRaw: String,
    private val user: String,
    private val pass: String
) {

    class DavException(message: String, val code: Int = -1) : Exception(message)

    /** 规范化：确保以 / 结尾，作为远端工作目录 */
    private val baseUrl = baseUrlRaw.trim().trimEnd('/') + "/"

    /** 逐段URL编码，支持中文路径/文件名 */
    private fun urlFor(fileName: String): String {
        val encoded = fileName.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        return baseUrl + encoded
    }

    private fun conn(fileName: String, method: String): HttpURLConnection {
        val c = URL(urlFor(fileName)).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        val auth = "$user:$pass"
        c.setRequestProperty(
            "Authorization",
            "Basic " + Base64.encodeToString(auth.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        )
        return c
    }

    /**
     * 登录验证：对工作目录发 PROPFIND Depth:0。
     * 2xx 或 207 即视为账号密码正确。
     */
    fun verify() {
        val c = conn("", "PROPFIND")
        try {
            c.setRequestProperty("Depth", "0")
            val code = c.responseCode
            if (code == 401) throw DavException("账号或密码错误", code)
            if (!(code in 200..299 || code == 207)) {
                throw DavException("服务器返回 HTTP $code", code)
            }
        } catch (e: DavException) {
            throw e
        } catch (e: Exception) {
            throw DavException("无法连接服务器：${e.message ?: "网络异常"}")
        } finally {
            c.disconnect()
        }
    }

    /** 上传文件（覆盖写）。 */
    fun upload(fileName: String, data: ByteArray) {
        val c = conn(fileName, "PUT")
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(data.size)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(data) }
            val code = c.responseCode
            if (code !in 200..299) throw DavException("上传失败 HTTP $code", code)
        } catch (e: DavException) {
            throw e
        } catch (e: Exception) {
            throw DavException("上传失败：${e.message ?: "网络异常"}")
        } finally {
            c.disconnect()
        }
    }

    /** 下载文件内容。 */
    fun download(fileName: String): ByteArray {
        val c = conn(fileName, "GET")
        try {
            val code = c.responseCode
            if (code == 404) throw DavException("云端还没有备份文件", code)
            if (code !in 200..299) throw DavException("下载失败 HTTP $code", code)
            return c.inputStream.use { it.readBytes() }
        } catch (e: DavException) {
            throw e
        } catch (e: Exception) {
            throw DavException("下载失败：${e.message ?: "网络异常"}")
        } finally {
            c.disconnect()
        }
    }
}
