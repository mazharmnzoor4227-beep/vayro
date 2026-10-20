package com.vayro.app.mcp

import android.content.Context
import com.vayro.app.security.PermissionPolicyEngine
import com.vayro.app.workspace.SafeFileOps
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class McpPermissionPolicy {
    ALLOW_ALWAYS,
    REQUIRE_PROMPT,
    DENY
}

data class McpPermissionDecision(
    val allowed: Boolean,
    val requiresPrompt: Boolean,
    val reason: String = ""
)

/**
 * Manages global and project-level execution permissions for MCP servers and tools.
 */
class McpPermissionManager(
    baseDir: File,
    private val policyEngine: PermissionPolicyEngine? = null
) {
    constructor(context: Context, policyEngine: PermissionPolicyEngine? = null) : this(
        File(context.filesDir, "mcp"),
        policyEngine
    )

    private val permissionFile = File(baseDir.apply { mkdirs() }, "mcp_permissions.json")

    // Global: serverId -> policy
    private val globalServerPolicies = ConcurrentHashMap<String, McpPermissionPolicy>()

    // Global: serverId:toolName -> policy
    private val globalToolPolicies = ConcurrentHashMap<String, McpPermissionPolicy>()

    // Project: projectId -> serverId -> policy
    private val projectServerPolicies = ConcurrentHashMap<String, ConcurrentHashMap<String, McpPermissionPolicy>>()

    // Project: projectId -> serverId:toolName -> policy
    private val projectToolPolicies = ConcurrentHashMap<String, ConcurrentHashMap<String, McpPermissionPolicy>>()

    private val lock = Any()

    init {
        loadPersisted()
    }

    private val oneTimeApprovals = ConcurrentHashMap<String, Unit>()

    fun evaluate(
        server: McpServerInfo,
        toolName: String? = null,
        projectId: String? = null
    ): McpPermissionDecision {
        val decision = evaluatePolicy(server, toolName, projectId)
        if (!decision.allowed && decision.requiresPrompt && toolName != null &&
            consumeOneTimeApproval(server.id, toolName, projectId)
        ) {
            return decision.copy(
                allowed = true,
                requiresPrompt = false,
                reason = "One-time approval granted by the user"
            )
        }
        return decision
    }

    fun grantOneTimeApproval(serverId: String, toolName: String, projectId: String? = null) {
        oneTimeApprovals[approvalKey(serverId, toolName, projectId)] = Unit
    }

    private fun consumeOneTimeApproval(serverId: String, toolName: String, projectId: String?): Boolean =
        oneTimeApprovals.remove(approvalKey(serverId, toolName, projectId)) != null

    private fun approvalKey(serverId: String, toolName: String, projectId: String?): String =
        "${projectId.orEmpty()}|$serverId::$toolName"

    private fun evaluatePolicy(
        server: McpServerInfo,
        toolName: String? = null,
        projectId: String? = null
    ): McpPermissionDecision {
        // 1. Emergency kill switch check
        if (policyEngine?.isEmergencyKillSwitchEngaged() == true) {
            return McpPermissionDecision(
                allowed = false,
                requiresPrompt = false,
                reason = "Emergency kill switch is ACTIVE. All MCP execution blocked."
            )
        }

        // 2. Disabled check
        if (!server.enabled) {
            return McpPermissionDecision(
                allowed = false,
                requiresPrompt = false,
                reason = "MCP server '${server.name}' (${server.id}) is disabled."
            )
        }

        // 3. Project-specific tool policy
        val toolKey = if (toolName != null) "${server.id}:$toolName" else null
        if (projectId != null && toolKey != null) {
            val projToolPolicy = projectToolPolicies[projectId]?.get(toolKey)
            if (projToolPolicy != null) return toDecision(projToolPolicy, "project tool policy")
        }

        // 4. Project-specific server policy
        if (projectId != null) {
            val projServerPolicy = projectServerPolicies[projectId]?.get(server.id)
            if (projServerPolicy != null) return toDecision(projServerPolicy, "project server policy")
        }

        // 5. Global tool policy
        if (toolKey != null) {
            val globalToolPolicy = globalToolPolicies[toolKey]
            if (globalToolPolicy != null) return toDecision(globalToolPolicy, "global tool policy")
        }

        // 6. Global server policy
        val globalServerPolicy = globalServerPolicies[server.id]
        if (globalServerPolicy != null) {
            return toDecision(globalServerPolicy, "global server policy")
        }

        // 7. Built-in defaults: built-in servers default to REQUIRE_PROMPT.
        return if (server.isBuiltIn) {
            McpPermissionDecision(allowed = false, requiresPrompt = true, reason = "Built-in server requires user confirmation on first use")
        } else {
            McpPermissionDecision(allowed = false, requiresPrompt = true, reason = "Custom server requires user confirmation")
        }
    }

    fun setGlobalServerPolicy(serverId: String, policy: McpPermissionPolicy) {
        globalServerPolicies[serverId] = policy
        persist()
    }

    fun setGlobalToolPolicy(serverId: String, toolName: String, policy: McpPermissionPolicy) {
        globalToolPolicies["$serverId:$toolName"] = policy
        persist()
    }

    fun setProjectServerPolicy(projectId: String, serverId: String, policy: McpPermissionPolicy) {
        val map = projectServerPolicies.computeIfAbsent(projectId) { ConcurrentHashMap() }
        map[serverId] = policy
        persist()
    }

    fun setProjectToolPolicy(projectId: String, serverId: String, toolName: String, policy: McpPermissionPolicy) {
        val map = projectToolPolicies.computeIfAbsent(projectId) { ConcurrentHashMap() }
        map["$serverId:$toolName"] = policy
        persist()
    }


    private fun toDecision(policy: McpPermissionPolicy, source: String): McpPermissionDecision {
        return when (policy) {
            McpPermissionPolicy.ALLOW_ALWAYS -> McpPermissionDecision(allowed = true, requiresPrompt = false, reason = "Allowed by $source")
            McpPermissionPolicy.REQUIRE_PROMPT -> McpPermissionDecision(allowed = false, requiresPrompt = true, reason = "Confirmation required by $source")
            McpPermissionPolicy.DENY -> McpPermissionDecision(allowed = false, requiresPrompt = false, reason = "Denied by $source")
        }
    }

    private fun loadPersisted() {
        synchronized(lock) {
            if (!permissionFile.exists()) return
            runCatching {
                val raw = permissionFile.readText()
                if (raw.isNotBlank()) {
                    val root = JSONObject(raw)
                    val globals = root.optJSONObject("globalServers")
                    globals?.keys()?.forEach { sId ->
                        val p = runCatching { McpPermissionPolicy.valueOf(globals.getString(sId)) }.getOrNull()
                        if (p != null) globalServerPolicies[sId] = p
                    }

                    val globalTools = root.optJSONObject("globalTools")
                    globalTools?.keys()?.forEach { tKey ->
                        val p = runCatching { McpPermissionPolicy.valueOf(globalTools.getString(tKey)) }.getOrNull()
                        if (p != null) globalToolPolicies[tKey] = p
                    }

                    val projects = root.optJSONObject("projectServers")
                    projects?.keys()?.forEach { pId ->
                        val pObj = projects.getJSONObject(pId)
                        val map = projectServerPolicies.computeIfAbsent(pId) { ConcurrentHashMap() }
                        pObj.keys().forEach { sId ->
                            val p = runCatching { McpPermissionPolicy.valueOf(pObj.getString(sId)) }.getOrNull()
                            if (p != null) map[sId] = p
                        }
                    }

                    // Per-tool project policies: previously never persisted,
                    // so a per-tool DENY was silently lost on restart.
                    val projectTools = root.optJSONObject("projectTools")
                    projectTools?.keys()?.forEach { pId ->
                        val pObj = projectTools.getJSONObject(pId)
                        val map = projectToolPolicies.computeIfAbsent(pId) { ConcurrentHashMap() }
                        pObj.keys().forEach { tKey ->
                            val p = runCatching { McpPermissionPolicy.valueOf(pObj.getString(tKey)) }.getOrNull()
                            if (p != null) map[tKey] = p
                        }
                    }
                }
            }
        }
    }

    private fun persist() {
        synchronized(lock) {
            runCatching {
                val root = JSONObject()

                val globals = JSONObject()
                globalServerPolicies.forEach { (k, v) -> globals.put(k, v.name) }
                root.put("globalServers", globals)

                val globalTools = JSONObject()
                globalToolPolicies.forEach { (k, v) -> globalTools.put(k, v.name) }
                root.put("globalTools", globalTools)

                val projects = JSONObject()
                projectServerPolicies.forEach { (pId, map) ->
                    val pObj = JSONObject()
                    map.forEach { (k, v) -> pObj.put(k, v.name) }
                    projects.put(pId, pObj)
                }
                root.put("projectServers", projects)

                val projectTools = JSONObject()
                projectToolPolicies.forEach { (pId, map) ->
                    val pObj = JSONObject()
                    map.forEach { (k, v) -> pObj.put(k, v.name) }
                    projectTools.put(pId, pObj)
                }
                root.put("projectTools", projectTools)

                SafeFileOps.atomicWriteText(permissionFile, root.toString())
            }
        }
    }
}
