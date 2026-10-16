package com.vayro.app.mcp

import android.content.Context
import com.vayro.app.workspace.SafeFileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class McpAuditEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val serverId: String,
    val toolName: String,
    val argumentsJson: String,
    val success: Boolean,
    val resultPreview: String = "",
    val durationMs: Long = 0L,
    val projectId: String? = null,
    val permissionDecision: String = "ALLOWED",
    val error: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("serverId", serverId)
        put("toolName", toolName)
        put("argumentsJson", argumentsJson)
        put("success", success)
        put("resultPreview", resultPreview)
        put("durationMs", durationMs)
        projectId?.let { put("projectId", it) }
        put("permissionDecision", permissionDecision)
        error?.let { put("error", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): McpAuditEntry {
            return McpAuditEntry(
                id = json.optString("id", UUID.randomUUID().toString()),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                serverId = json.getString("serverId"),
                toolName = json.getString("toolName"),
                argumentsJson = json.optString("argumentsJson", "{}"),
                success = json.optBoolean("success", true),
                resultPreview = json.optString("resultPreview", ""),
                durationMs = json.optLong("durationMs", 0L),
                projectId = json.optString("projectId").takeIf { it.isNotBlank() },
                permissionDecision = json.optString("permissionDecision", "ALLOWED"),
                error = json.optString("error").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * Crash-safe logger for Model Context Protocol (MCP) tool executions and access decisions.
 *
 * Tool arguments often carry credentials, tokens, or personal data. Before an
 * entry is stored, argument values under sensitive-looking keys are replaced
 * with [REDACTED] and the result preview is capped, so mcp_audit.json never
 * holds raw secrets.
 */
class McpAuditLogger(
    private val storageDir: File? = null,
    private val maxEntries: Int = 1000
) {
    constructor(context: Context) : this(File(context.filesDir, "mcp"))

    private val auditFile: File? = storageDir?.let { File(it, "mcp_audit.json") }
    private val memoryLog = mutableListOf<McpAuditEntry>()
    private val lock = Any()

    init {
        loadPersisted()
    }

    fun log(entry: McpAuditEntry) {
        val redacted = entry.copy(
            argumentsJson = redactArguments(entry.argumentsJson),
            resultPreview = entry.resultPreview.take(RESULT_PREVIEW_MAX_CHARS),
        )
        synchronized(lock) {
            memoryLog.add(0, redacted)
            while (memoryLog.size > maxEntries) {
                memoryLog.removeAt(memoryLog.lastIndex)
            }
            persist()
        }
    }

    fun getEntries(limit: Int = 100): List<McpAuditEntry> {
        synchronized(lock) {
            return memoryLog.take(limit.coerceAtLeast(1))
        }
    }

    fun getEntriesForServer(serverId: String, limit: Int = 100): List<McpAuditEntry> {
        synchronized(lock) {
            return memoryLog.filter { it.serverId == serverId }.take(limit.coerceAtLeast(1))
        }
    }

    fun filter(
        query: String = "",
        serverId: String? = null,
        successOnly: Boolean? = null,
        limit: Int = 100
    ): List<McpAuditEntry> {
        synchronized(lock) {
            val q = query.trim().lowercase(java.util.Locale.ROOT)
            return memoryLog.asSequence()
                .filter { entry ->
                    if (serverId != null && entry.serverId != serverId) return@filter false
                    if (successOnly != null && entry.success != successOnly) return@filter false
                    if (q.isNotEmpty()) {
                        val matchServer = entry.serverId.lowercase(java.util.Locale.ROOT).contains(q)
                        val matchTool = entry.toolName.lowercase(java.util.Locale.ROOT).contains(q)
                        val matchArgs = entry.argumentsJson.lowercase(java.util.Locale.ROOT).contains(q)
                        val matchResult = entry.resultPreview.lowercase(java.util.Locale.ROOT).contains(q)
                        val matchErr = entry.error?.lowercase(java.util.Locale.ROOT)?.contains(q) == true
                        if (!matchServer && !matchTool && !matchArgs && !matchResult && !matchErr) {
                            return@filter false
                        }
                    }
                    true
                }
                .take(limit.coerceAtLeast(1))
                .toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            memoryLog.clear()
            auditFile?.let { if (it.exists()) it.delete() }
        }
    }

    private fun loadPersisted() {
        synchronized(lock) {
            val file = auditFile ?: return
            if (!file.exists()) return
            runCatching {
                val raw = file.readText()
                if (raw.isNotBlank()) {
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        memoryLog.add(McpAuditEntry.fromJson(obj))
                    }
                }
            }
        }
    }

    private fun persist() {
        val file = auditFile ?: return
        runCatching {
            val array = JSONArray()
            for (entry in memoryLog) {
                array.put(entry.toJson())
            }
            SafeFileOps.atomicWriteText(file, array.toString())
        }
    }

    companion object {
        private const val RESULT_PREVIEW_MAX_CHARS = 500
        private val SENSITIVE_KEY =
            Regex("password|passwd|pwd|token|secret|api[_-]?key|auth|bearer|credential|private[_-]?key|session[_-]?id", RegexOption.IGNORE_CASE)

        fun redactArguments(argumentsJson: String): String {
            val root = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return argumentsJson
            redactJson(root)
            return root.toString()
        }

        private fun redactJson(obj: JSONObject) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = obj.opt(key)) {
                    is JSONObject -> redactJson(value)
                    is JSONArray -> {
                        for (i in 0 until value.length()) {
                            (value.opt(i) as? JSONObject)?.let { redactJson(it) }
                        }
                    }
                    else -> if (SENSITIVE_KEY.containsMatchIn(key)) obj.put(key, "[REDACTED]")
                }
            }
        }
    }
}
