package com.fieldlog.powerdebug.core

import android.util.Base64
import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import org.xmlpull.v1.XmlPullParser

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

    /**
     * 连接诊断：返回完整排查报告文本（URL/HTTP状态/关键响应头/响应体片段/结论提示），
     * 用于登录弹窗内直接展示，便于远程定位失败原因。
     */
    fun diagnose(): String {
        val sb = StringBuilder()
        val c = try { conn("", "PROPFIND") } catch (e: Exception) {
            return "【连接诊断】\n地址: $baseUrl\n❌ 无法建立连接：${e.message}\n" +
                "提示：检查地址拼写/端口；手机与服务器是否在同一网络；http还是https。"
        }
        try {
            c.setRequestProperty("Depth", "0")
            val code = c.responseCode
            sb.appendLine("【连接诊断】")
            sb.appendLine("地址: ${urlFor("")}")
            sb.appendLine("方式: PROPFIND (Depth:0)")
            sb.appendLine("结果: HTTP $code ${c.responseMessage.orEmpty().trim()}")
            for (h in listOf("WWW-Authenticate", "Server", "Allow", "Content-Type")) {
                c.getHeaderField(h)?.let { sb.appendLine("$h: ${it.take(120)}") }
            }
            val body = try {
                c.errorStream ?: c.inputStream
            } catch (_: Exception) { null }
            val snippet = body?.bufferedReader()?.readText()?.replace(Regex("\\s+"), " ")?.take(150)
            if (!snippet.isNullOrBlank()) sb.appendLine("响应体: $snippet")

            when {
                code in 200..299 || code == 207 -> sb.appendLine("✅ 验证通过：账号密码有效，可正常同步")
                code == 401 -> {
                    sb.appendLine("❌ 认证失败（401）：服务器拒绝了这对账号密码")
                    sb.appendLine("· 坚果云：必须用「应用密码」，在网页端→账户信息→安全选项生成，不是登录密码")
                    sb.appendLine("· 群晖/威联通NAS：确认该用户已开启WebDAV/WebDAV Server应用权限")
                    sb.appendLine("· 检查密码大小写、末尾空格")
                }

                code == 403 -> sb.appendLine("❌ 禁止访问（403）：目录不存在或该账号无此目录权限")
                code == 404 -> sb.appendLine("❌ 路径不存在（404）：检查URL路径，常见为 https://主机/dav/ 或 /remote.php/dav/")
                code == 405 -> sb.appendLine("❌ 该地址不支持PROPFIND（405）：可能不是WebDAV服务端口")
                else -> sb.appendLine("❌ 异常状态码，请把以上完整内容发给开发者")
            }
            return sb.toString()
        } catch (e: Exception) {
            sb.appendLine("【连接诊断】")
            sb.appendLine("地址: $baseUrl")
            sb.appendLine("❌ 请求异常：${e.message}")
            sb.appendLine("提示：超时多为IP/端口不通或防火墙拦截；若用https而服务端只有http（或反之）也会失败")
            return sb.toString()
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

    /**
     * 列出工作目录下全部 backup_*.json 文件名（已URL解码）。
     * PROPFIND Depth:1，仅解析 <href> 节点，不依赖完整多状态语义。
     */
    fun listBackups(): List<String> {
        val c = conn("", "PROPFIND")
        try {
            c.setRequestProperty("Depth", "1")
            val code = c.responseCode
            if (!(code in 200..299 || code == 207)) {
                throw DavException("列出云端文件失败 HTTP $code", code)
            }
            val input = c.inputStream ?: return emptyList()
            val out = mutableListOf<String>()
            val p = Xml.newPullParser()
            p.setInput(input.bufferedReader(Charsets.UTF_8))
            var capture = false
            var sb = StringBuilder()
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG ->
                        if (p.name.equals("href", ignoreCase = true)) {
                            capture = true; sb = StringBuilder()
                        }

                    XmlPullParser.TEXT -> if (capture) sb.append(p.text)

                    XmlPullParser.END_TAG ->
                        if (p.name.equals("href", ignoreCase = true)) {
                            capture = false
                            val raw = sb.toString().trim()
                            if (raw.isNotEmpty()) {
                                val name = try {
                                    URLDecoder.decode(raw.substringAfterLast('/'), "UTF-8")
                                } catch (_: Exception) {
                                    raw.substringAfterLast('/')
                                }
                                if (name.startsWith("backup_") && name.endsWith(".json")) out += name
                            }
                        }
                }
                p.next()
            }
            return out.distinct()
        } catch (e: DavException) {
            throw e
        } catch (e: Exception) {
            throw DavException("列出云端文件失败：${e.message ?: "网络异常"}")
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
