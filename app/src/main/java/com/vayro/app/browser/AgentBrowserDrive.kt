package com.vayro.app.browser

import java.io.File

const val AGENT_BROWSER_BRIDGE_PORT = 9223
const val AGENT_BROWSER_DISCOVERY_FILE = "pocket-browser-webview.json"
const val AGENT_BROWSER_ATTACH_FILE = "pocket-browser-attach.json"
const val AGENT_BROWSER_DISCOVERY_MAX_AGE_MS = 60_000L
const val AGENT_BROWSER_ATTACH_MAX_AGE_MS = 15 * 60_000L

data class DevToolsTarget(
    val id: String,
    val type: String,
    val title: String,
    val url: String,
    val webSocketDebuggerUrl: String,
)

data class WebViewTargetInfo(
    val devtoolsSocket: String,
    val webSocketDebuggerUrl: String,
    val tabUrl: String,
    val tabTitle: String,
    val discoveredAt: Long,
) {
    fun toJson(): String =
        "{\"devtoolsSocket\":" + jsonEscape(devtoolsSocket) +
            ",\"webSocketDebuggerUrl\":" + jsonEscape(webSocketDebuggerUrl) +
            ",\"tabUrl\":" + jsonEscape(tabUrl) +
            ",\"tabTitle\":" + jsonEscape(tabTitle) +
            ",\"discoveredAt\":" + discoveredAt + "}"

    companion object {
        fun fromJson(json: String): WebViewTargetInfo? {
            val socket = parseStringField(json, "devtoolsSocket")
            val wsUrl = parseStringField(json, "webSocketDebuggerUrl")
            if (socket.isBlank() || wsUrl.isBlank()) return null
            return WebViewTargetInfo(
                devtoolsSocket = socket,
                webSocketDebuggerUrl = wsUrl,
                tabUrl = parseStringField(json, "tabUrl"),
                tabTitle = parseStringField(json, "tabTitle"),
                discoveredAt = parseLongField(json, "discoveredAt"),
            )
        }
    }
}

data class AgentBrowserAttachState(
    val webSocketDebuggerUrl: String,
    val tabUrl: String,
    val tabTitle: String,
    val attachedAt: Long,
) {
    fun toJson(): String =
        "{\"mode\":\"attached\",\"webSocketDebuggerUrl\":" + jsonEscape(webSocketDebuggerUrl) +
            ",\"tabUrl\":" + jsonEscape(tabUrl) +
            ",\"tabTitle\":" + jsonEscape(tabTitle) +
            ",\"attachedAt\":" + attachedAt + "}"

    companion object {
        fun fromJson(json: String): AgentBrowserAttachState? {
            val wsUrl = parseStringField(json, "webSocketDebuggerUrl")
            if (wsUrl.isBlank()) return null
            return AgentBrowserAttachState(
                webSocketDebuggerUrl = wsUrl,
                tabUrl = parseStringField(json, "tabUrl"),
                tabTitle = parseStringField(json, "tabTitle"),
                attachedAt = parseLongField(json, "attachedAt"),
            )
        }
    }
}

fun devToolsSocketName(pid: Int): String = "webview_devtools_remote_$pid"

fun isFresh(timestampMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
    timestampMs > 0 && timestampMs <= nowMs && nowMs - timestampMs <= maxAgeMs

fun rewriteDevToolsWsUrl(wsUrl: String, port: Int = AGENT_BROWSER_BRIDGE_PORT): String =
    wsUrl.replace(Regex("^ws://127\\.0\\.0\\.1:\\d+"), "ws://127.0.0.1:$port")

fun parseDevToolsTargets(json: String): List<DevToolsTarget> =
    splitJsonObjects(json).map { obj ->
        DevToolsTarget(
            id = parseStringField(obj, "id"),
            type = parseStringField(obj, "type"),
            title = parseStringField(obj, "title"),
            url = parseStringField(obj, "url"),
            webSocketDebuggerUrl = parseStringField(obj, "webSocketDebuggerUrl"),
        )
    }.filter { it.id.isNotBlank() && it.webSocketDebuggerUrl.isNotBlank() }

fun selectWebViewPageTarget(targets: List<DevToolsTarget>, preferredUrl: String?): DevToolsTarget? {
    val pages = targets.filter { it.type == "page" }
    if (pages.isEmpty()) return null
    val visible = pages.filter { !it.url.startsWith("devtools://") }
    val candidates = visible.ifEmpty { pages }
    if (!preferredUrl.isNullOrBlank()) {
        candidates.find { it.url == preferredUrl }?.let { return it }
        val preferredHost = runCatching { java.net.URI(preferredUrl).host }.getOrNull()
        if (!preferredHost.isNullOrBlank()) {
            candidates.findLast {
                runCatching { java.net.URI(it.url).host }.getOrNull() == preferredHost
            }?.let { return it }
        }
    }
    val loaded = candidates.filter { it.url.isNotBlank() && it.url != "about:blank" }
    return (loaded.ifEmpty { candidates }).last()
}

fun readAgentBrowserAttachState(rootfsTmp: File, nowMs: Long = System.currentTimeMillis()): AgentBrowserAttachState? {
    val file = File(rootfsTmp, AGENT_BROWSER_ATTACH_FILE)
    if (!file.isFile) return null
    val raw = runCatching { file.readText() }.getOrNull() ?: return null
    val state = AgentBrowserAttachState.fromJson(raw) ?: return null
    if (!isFresh(state.attachedAt, nowMs, AGENT_BROWSER_ATTACH_MAX_AGE_MS)) {
        runCatching { file.delete() }
        return null
    }
    return state
}

fun clearAgentBrowserAttachState(rootfsTmp: File) {
    runCatching { File(rootfsTmp, AGENT_BROWSER_ATTACH_FILE).delete() }
}

fun refreshWebViewTargetFile(rootfsTmp: File, preferredUrl: String?): Boolean {
    return try {
        rootfsTmp.mkdirs()
        val socketName = devToolsSocketName(android.os.Process.myPid())
        val targets = WebViewDevToolsDiscovery.fetchTargets(socketName)
        val selected = selectWebViewPageTarget(targets, preferredUrl) ?: return false
        val info = WebViewTargetInfo(
            devtoolsSocket = socketName,
            webSocketDebuggerUrl = selected.webSocketDebuggerUrl,
            tabUrl = selected.url,
            tabTitle = selected.title,
            discoveredAt = System.currentTimeMillis(),
        )
        File(rootfsTmp, AGENT_BROWSER_DISCOVERY_FILE).writeText(info.toJson())
        true
    } catch (_: Exception) {
        false
    }
}

object WebViewDevToolsDiscovery {
    fun fetchTargets(socketName: String, timeoutMs: Int = 1500): List<DevToolsTarget> {
        val body = httpGet(socketName, "/json", timeoutMs) ?: return emptyList()
        return parseDevToolsTargets(body)
    }

    private fun httpGet(socketName: String, path: String, timeoutMs: Int): String? {
        return try {
            val socket = android.net.LocalSocket()
            socket.connect(
                android.net.LocalSocketAddress(socketName, android.net.LocalSocketAddress.Namespace.ABSTRACT),
                timeoutMs,
            )
            socket.soTimeout = timeoutMs
            val request = "GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            val bytes = socket.inputStream.readBytes()
            try {
                socket.close()
            } catch (_: Exception) {
            }
            val text = bytes.toString(Charsets.UTF_8)
            val headerEnd = text.indexOf("\r\n\r\n")
            if (headerEnd < 0) null else text.substring(headerEnd + 4).ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}

internal fun jsonEscape(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}

internal fun splitJsonObjects(arrayJson: String): List<String> {
    val trimmed = arrayJson.trim()
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
    val inner = trimmed.substring(1, trimmed.length - 1)
    val objects = mutableListOf<String>()
    var depth = 0
    var inString = false
    var escaped = false
    var start = -1
    for (i in inner.indices) {
        val c = inner[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(inner.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
    }
    return objects
}

internal fun parseStringField(obj: String, field: String): String {
    val key = "\"$field\""
    var idx = obj.indexOf(key)
    while (idx >= 0) {
        var p = idx + key.length
        while (p < obj.length && obj[p].isWhitespace()) p++
        if (p < obj.length && obj[p] == ':') {
            p++
            while (p < obj.length && obj[p].isWhitespace()) p++
            if (p < obj.length && obj[p] == '"') {
                return readJsonString(obj, p)
            }
            return ""
        }
        idx = obj.indexOf(key, idx + 1)
    }
    return ""
}

internal fun parseLongField(obj: String, field: String): Long {
    val key = "\"$field\""
    val idx = obj.indexOf(key)
    if (idx < 0) return 0L
    var p = idx + key.length
    while (p < obj.length && (obj[p].isWhitespace() || obj[p] == ':')) p++
    val start = p
    while (p < obj.length && (obj[p].isDigit() || obj[p] == '-')) p++
    return if (p > start) obj.substring(start, p).toLongOrNull() ?: 0L else 0L
}

internal fun readJsonString(obj: String, quoteAt: Int): String {
    val sb = StringBuilder()
    var p = quoteAt + 1
    var escaped = false
    while (p < obj.length) {
        val c = obj[p]
        if (escaped) {
            when (c) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'u' -> {
                    val hex = obj.substring(p + 1, (p + 5).coerceAtMost(obj.length))
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    p += 4
                }
                else -> sb.append(c)
            }
            escaped = false
        } else if (c == '\\') {
            escaped = true
        } else if (c == '"') {
            break
        } else {
            sb.append(c)
        }
        p++
    }
    return sb.toString()
}
