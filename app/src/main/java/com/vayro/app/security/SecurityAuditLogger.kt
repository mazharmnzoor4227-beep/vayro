package com.vayro.app.security

import android.content.Context
import com.vayro.app.workspace.SafeFileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class AuditDecision {
    ALLOWED,
    DENIED,
    BLOCKED_DANGEROUS,
    BYPASSED_SAFE,
    REQUIRES_CONFIRMATION
}

data class SecurityAuditEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val category: PermissionCategory,
    val action: String,
    val target: String,
    val decision: AuditDecision,
    val reason: String,
    val sessionId: String? = null,
    val projectId: String? = null,
    val level: PermissionLevel? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("category", category.name)
        put("action", action)
        put("target", target)
        put("decision", decision.name)
        put("reason", reason)
        putOpt("sessionId", sessionId)
        putOpt("projectId", projectId)
        putOpt("level", level?.name)
    }

    companion object {
        fun fromJson(json: JSONObject): SecurityAuditEntry {
            val cat = runCatching { PermissionCategory.valueOf(json.optString("category", "FILE_READ")) }
                .getOrDefault(PermissionCategory.FILE_READ)
            val dec = runCatching { AuditDecision.valueOf(json.optString("decision", "DENIED")) }
                .getOrDefault(AuditDecision.DENIED)
            val lvl = json.optString("level").takeIf { it.isNotBlank() }?.let {
                runCatching { PermissionLevel.valueOf(it) }.getOrNull()
            }
            return SecurityAuditEntry(
                id = json.optString("id", UUID.randomUUID().toString()),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                category = cat,
                action = json.optString("action", "unknown"),
                target = json.optString("target", ""),
                decision = dec,
                reason = json.optString("reason", ""),
                sessionId = json.optString("sessionId").takeIf { it.isNotBlank() },
                projectId = json.optString("projectId").takeIf { it.isNotBlank() },
                level = lvl
            )
        }
    }
}

/**
 * Crash-safe security audit trail logger for Vayro.
 * Logs every security evaluation, policy bypass, rejection, and dangerous command block.
 */
class SecurityAuditLogger(
    private val storageDir: File? = null,
    private val maxEntries: Int = 1000
) {
    constructor(context: Context) : this(File(context.filesDir, "security"))

    private val auditFile: File? = storageDir?.let { File(it, "security_audit.json") }
    private val memoryLog = mutableListOf<SecurityAuditEntry>()
    private val lock = Any()

    init {
        loadPersistedLogs()
    }

    private fun loadPersistedLogs() {
        synchronized(lock) {
            val file = auditFile ?: return
            if (!file.exists()) return
            runCatching {
                val raw = file.readText()
                if (raw.isNotBlank()) {
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        memoryLog.add(SecurityAuditEntry.fromJson(obj))
                    }
                }
            }
        }
    }

    fun log(entry: SecurityAuditEntry) {
        synchronized(lock) {
            memoryLog.add(0, entry)
            while (memoryLog.size > maxEntries) {
                memoryLog.removeAt(memoryLog.lastIndex)
            }
            persist()
        }
    }

    fun getEntries(limit: Int = 100): List<SecurityAuditEntry> {
        synchronized(lock) {
            return memoryLog.take(limit.coerceAtLeast(1))
        }
    }

    fun getEntriesForProject(projectId: String, limit: Int = 100): List<SecurityAuditEntry> {
        synchronized(lock) {
            return memoryLog.filter { it.projectId == projectId }.take(limit.coerceAtLeast(1))
        }
    }

    fun filter(
        query: String = "",
        category: PermissionCategory? = null,
        decision: AuditDecision? = null,
        limit: Int = 100
    ): List<SecurityAuditEntry> {
        synchronized(lock) {
            val q = query.trim().lowercase(java.util.Locale.ROOT)
            return memoryLog.asSequence()
                .filter { entry ->
                    if (category != null && entry.category != category) return@filter false
                    if (decision != null && entry.decision != decision) return@filter false
                    if (q.isNotEmpty()) {
                        val matchAction = entry.action.lowercase(java.util.Locale.ROOT).contains(q)
                        val matchTarget = entry.target.lowercase(java.util.Locale.ROOT).contains(q)
                        val matchReason = entry.reason.lowercase(java.util.Locale.ROOT).contains(q)
                        val matchCategory = entry.category.name.lowercase(java.util.Locale.ROOT).contains(q)
                        val matchDecision = entry.decision.name.lowercase(java.util.Locale.ROOT).contains(q)
                        if (!matchAction && !matchTarget && !matchReason && !matchCategory && !matchDecision) {
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

    private fun persist() {
        val file = auditFile ?: return
        runCatching {
            val array = JSONArray()
            // Persist the latest entries up to maxEntries
            for (entry in memoryLog) {
                array.put(entry.toJson())
            }
            SafeFileOps.atomicWriteText(file, array.toString())
        }
    }
}
