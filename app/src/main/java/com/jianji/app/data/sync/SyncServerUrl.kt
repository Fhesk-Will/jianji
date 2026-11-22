package com.jianji.app.data.sync

import java.net.URI

/** 云同步服务器地址校验与规范化；正式地址只允许 HTTPS。 */
object SyncServerUrl {

    fun normalize(raw: String): Result<String> = runCatching {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "地址不能为空" }

        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "仅支持 http/https" }

        val host = uri.host
        require(!host.isNullOrBlank()) { "缺少主机名" }
        require(uri.userInfo == null) { "地址不能包含用户名或密码" }
        require(uri.query == null) { "地址不能包含查询参数" }
        require(uri.fragment == null) { "地址不能包含片段标识" }

        if (scheme == "http") {
            require(isLoopbackOrPrivate(host)) { "公网 HTTP 不安全，请改用 HTTPS" }
        }

        val path = uri.rawPath.orEmpty().removeSuffix("/")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val normalizedHost = if (host.contains(':')) "[$host]" else host
        "$scheme://$normalizedHost$port$path"
    }

    /** HTTP 只作为本机或内网调试例外；公网自部署入口必须走 HTTPS。 */
    private fun isLoopbackOrPrivate(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower == "::1" || lower.startsWith("127.")) return true
        if (lower.startsWith("10.") || lower.startsWith("192.168.") || lower.startsWith("169.254.")) return true
        if (lower.startsWith("172.")) {
            val second = lower.substringAfter('.').substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}
