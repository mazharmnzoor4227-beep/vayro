package com.vayro.app.mcp

import android.content.Context
import com.vayro.app.workspace.SafeFileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry and lifecycle manager for Model Context Protocol (MCP) servers.
 * Handles persistence, discovery, catalog templates, enable/disable toggling, and removal.
 */
class McpRegistry(
    baseDir: File
) {
    constructor(context: Context) : this(File(context.filesDir, "mcp"))

    private val root = baseDir.apply { mkdirs() }
    private val configFile = File(root, "mcp_servers.json")
    private val servers = ConcurrentHashMap<String, McpServerInfo>()
    private val lock = Any()
    private val spawnMarkerFile = File(root, "mcp_spawn_markers.json")
    private val spawnedServerIds = mutableSetOf<String>()

    init {
        loadPersisted()
        loadSpawnMarkers()
    }

    fun hasSpawnedBefore(serverId: String): Boolean = synchronized(lock) {
        spawnedServerIds.contains(serverId)
    }

    fun markSpawned(serverId: String) = synchronized(lock) {
        if (spawnedServerIds.add(serverId)) {
            runCatching {
                SafeFileOps.atomicWriteText(spawnMarkerFile, JSONArray(spawnedServerIds.toList()).toString())
            }
        }
    }

    private fun loadSpawnMarkers() {
        synchronized(lock) {
            runCatching {
                val text = spawnMarkerFile.takeIf { it.exists() }?.readText().orEmpty()
                if (text.isNotBlank()) {
                    val array = JSONArray(text)
                    for (i in 0 until array.length()) spawnedServerIds.add(array.getString(i))
                }
            }
        }
    }

    /**
     * Curated list of discoverable MCP servers available for installation.
     */
    val catalogTemplates: List<McpCatalogTemplate> = McpBuiltInCatalogTemplates

    fun listServers(projectId: String? = null): List<McpServerInfo> {
        return servers.values
            .filter { it.projectId == null || it.projectId == projectId }
            .sortedBy { it.name.lowercase() }
    }

    fun getServer(id: String): McpServerInfo? = servers[id]

    fun installServer(server: McpServerInfo): McpServerInfo {
        servers[server.id] = server
        persist()
        return server
    }

    fun installFromTemplate(
        templateId: String,
        envOverrides: Map<String, String> = emptyMap(),
        projectId: String? = null
    ): McpServerInfo {
        val template = catalogTemplates.find { it.id == templateId }
            ?: throw IllegalArgumentException("Template with ID '$templateId' not found.")
        val secretOverrides = envOverrides.keys.intersect(template.requiredEnv.toSet())
        require(secretOverrides.isEmpty()) {
            "Refusing to persist secret env in plaintext server config: ${secretOverrides.joinToString()}"
        }

        // A template installed for a project gets a project-scoped id, so
        // installing it for project B no longer overwrites project A's entry.
        val serverId = if (projectId.isNullOrBlank()) template.id else "${template.id}::$projectId"
        val server = McpServerInfo(
            id = serverId,
            name = template.name,
            description = template.description,
            command = template.defaultCommand,
            args = template.defaultArgs,
            env = envOverrides,
            enabled = true,
            isBuiltIn = true,
            projectId = projectId,
            // Template-declared secrets resolve from McpCredentialStore at start time,
            // never from the persisted registry JSON.
            secretEnvNames = template.requiredEnv
        )
        return installServer(server)
    }

    fun updateServer(server: McpServerInfo): Boolean {
        if (!servers.containsKey(server.id)) return false
        servers[server.id] = server
        persist()
        return true
    }

    fun toggleServer(id: String, enabled: Boolean): Boolean {
        val existing = servers[id] ?: return false
        servers[id] = existing.copy(enabled = enabled)
        persist()
        return true
    }

    fun removeServer(id: String): Boolean {
        val removed = servers.remove(id) != null
        if (removed) persist()
        return removed
    }

    /**
     * Versioned removal: drops the server from the registry AND purges its stored
     * secrets so credentials do not linger after uninstall.
     */
    fun removeServerAndCredentials(id: String, credentialStore: McpCredentialStore): Boolean {
        if (!servers.containsKey(id)) return false
        credentialStore.removeAllServerCredentials(id)
        return removeServer(id)
    }

    /**
     * Versioned update: records a new installed version. Downgrades are refused
     * unless [force] is true; the comparison is a best-effort numeric semver check.
     */
    fun updateServerVersion(id: String, newVersion: String, force: Boolean = false): Boolean {
        val existing = servers[id] ?: return false
        require(newVersion.isNotBlank()) { "Version cannot be blank" }
        if (!force && compareVersions(newVersion, existing.version) < 0) {
            return false
        }
        servers[id] = existing.copy(version = newVersion)
        persist()
        return true
    }

    companion object {
        /**
         * Best-effort numeric version comparison ("1.10.2" > "1.9.0").
         * Non-numeric segments compare lexicographically. Returns negative when a < b.
         */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split(".", "-")
            val pb = b.split(".", "-")
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val sa = pa.getOrElse(i) { "0" }
                val sb = pb.getOrElse(i) { "0" }
                val na = sa.toIntOrNull()
                val nb = sb.toIntOrNull()
                val cmp = if (na != null && nb != null) na.compareTo(nb) else sa.compareTo(sb)
                if (cmp != 0) return cmp
            }
            return 0
        }
    }

    fun searchCatalog(query: String): List<McpCatalogTemplate> {
        if (query.isBlank()) return catalogTemplates
        val q = query.lowercase().trim()
        return catalogTemplates.mapNotNull { template ->
            val name = template.name.lowercase()
            val rank = when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                template.description.lowercase().contains(q) -> 3
                template.category.lowercase().contains(q) -> 4
                template.id.lowercase().contains(q) -> 5
                else -> return@mapNotNull null
            }
            rank to template
        }.sortedBy { it.first }.map { it.second }
    }

    private fun loadPersisted() {
        synchronized(lock) {
            if (!configFile.exists()) return
            runCatching {
                val text = configFile.readText()
                if (text.isNotBlank()) {
                    val array = JSONArray(text)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val server = McpServerInfo.fromJson(obj)
                        servers[server.id] = server
                    }
                }
            }
        }
    }

    private fun persist() {
        synchronized(lock) {
            runCatching {
                val array = JSONArray()
                servers.values.forEach { array.put(it.toJson()) }
                SafeFileOps.atomicWriteText(configFile, array.toString())
            }
        }
    }
}
