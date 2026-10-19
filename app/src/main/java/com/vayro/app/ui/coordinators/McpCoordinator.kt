package com.vayro.app.ui.coordinators

import com.vayro.app.mcp.McpAuditLogger
import com.vayro.app.mcp.McpCredentialStore
import com.vayro.app.mcp.McpDeniedException
import com.vayro.app.mcp.McpMissingCredentialsException
import com.vayro.app.mcp.McpPermissionManager
import com.vayro.app.mcp.McpPromptRequiredException
import com.vayro.app.mcp.McpRegistry
import com.vayro.app.mcp.McpServerInfo
import com.vayro.app.mcp.McpToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** How the user approved a single MCP tool run from the UI. */
enum class McpToolApproval {
    ONE_TIME,
    ALWAYS_ALLOW
}

/**
 * MCP (Model Context Protocol) domain coordinator (Milestone 1).
 *
 * Explicit owner of the MCP server state machine: install/toggle/remove,
 * tool discovery, tool execution and the audit log. Discovery and calls go
 * through [McpToolRegistry] (correct NDJSON wire protocol, permission
 * gating, audit logging, caching, remote support, credential resolution);
 * the registry owns permission evaluation and audit entries, so this
 * coordinator only maps outcomes into UI state and the
 * [com.vayro.app.runtime.McpToolCallResult] shape the callers use.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class McpCoordinator(
    private val host: CoordinatorHost,
    private val mcpRegistry: McpRegistry,
    private val mcpAuditLogger: McpAuditLogger,
    private val mcpPermissionManager: McpPermissionManager,
    private val toolRegistry: McpToolRegistry,
    private val credentialStore: McpCredentialStore,
) {
    fun refreshMcpServers(projectId: String? = host.state().activeProject?.id) {
        val servers = mcpRegistry.listServers(projectId)
        val catalog = mcpRegistry.catalogTemplates
        // v3.2.40 (Scope C2): the cards' needs-key state comes from the
        // credential store's ACTUAL contents, recomputed on every
        // refresh — never boilerplate.
        val missing = servers.associate { it.id to toolRegistry.missingCredentials(it) }
            .filterValues { it.isNotEmpty() }
        host.updateState { it.copy(mcpServers = servers, mcpCatalog = catalog, mcpServersMissingKeys = missing) }
    }

    /**
     * v3.2.40 (Scope C2): saves keys for an ALREADY-installed server
     * (its card's Add action) into the encrypted credential store,
     * then refreshes so the needs-key state recomputes from the store.
     */
    fun saveMcpServerSecrets(serverId: String, secrets: Map<String, String>) {
        secrets.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                credentialStore.setCredential(serverId, name, value)
            }
        }
        host.updateState { it.copy(mcpServerErrors = it.mcpServerErrors - serverId) }
        refreshMcpServers()
    }

    /**
     * Installs a custom MCP server. [secrets] are stored in the credential
     * store (encrypted), never in the server config: [McpServerInfo] only
     * carries their names in `secretEnvNames`. The server is registered
     * first so a failure there never leaves orphaned secrets behind.
     */
    fun installMcpServer(server: McpServerInfo, secrets: Map<String, String> = emptyMap()) {
        mcpRegistry.installServer(server)
        secrets.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                credentialStore.setCredential(server.id, name, value)
            }
        }
        refreshMcpServers()
    }

    fun installMcpTemplate(templateId: String, envOverrides: Map<String, String> = emptyMap(), projectId: String? = host.state().activeProject?.id) {
        runCatching {
            mcpRegistry.installFromTemplate(templateId, envOverrides, projectId)
            refreshMcpServers(projectId)
        }.onFailure { err ->
            host.updateState { it.copy(toastMessage = "Failed to install MCP template: ${err.message}") }
        }
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean) {
        mcpRegistry.toggleServer(serverId, enabled)
        if (!enabled) {
            // A disabled server's tools are not usable — stop claiming them.
            toolRegistry.stopServer(serverId)
            host.updateState {
                it.copy(
                    mcpToolCounts = it.mcpToolCounts - serverId,
                    mcpServerErrors = it.mcpServerErrors - serverId
                )
            }
        }
        refreshMcpServers()
    }

    fun removeMcpServer(serverId: String) {
        toolRegistry.stopServer(serverId)
        mcpRegistry.removeServerAndCredentials(serverId, credentialStore)
        host.updateState {
            it.copy(
                mcpToolCounts = it.mcpToolCounts - serverId,
                mcpServerErrors = it.mcpServerErrors - serverId
            )
        }
        refreshMcpServers()
    }

    fun updateMcpConfig(server: McpServerInfo) {
        mcpRegistry.updateServer(server)
        refreshMcpServers()
    }

    /** "Tools ready on N of M servers" — M = enabled installed servers. */
    private fun toolsReadySummary(servers: List<McpServerInfo>, counts: Map<String, Int>): String {
        val enabled = servers.filter { it.enabled }
        val ready = enabled.count { (counts[it.id] ?: 0) > 0 }
        return "Tools ready on $ready of ${enabled.size} servers."
    }

    /**
     * v3.2.40 (Scope C2): discovery is PER SERVER — busy state is the
     * set of in-flight server ids, so one server's discovery never
     * blocks or masks another's. Outcomes: missing credentials land
     * in the server's needs-key STATE (never the failure banner,
     * never the error slot); any other failure's reason lives on
     * that server's card only, with a neutral summary in the banner.
     */
    fun discoverMcpTools(server: McpServerInfo) {
        host.scope.launch {
            // v3.2.38 (Part Q): the busy message tells the truth about a
            // first start — the package downloads before the handshake.
            host.updateState { it.copy(mcpBusy = true, mcpDiscoveringServerIds = it.mcpDiscoveringServerIds + server.id, mcpSelectedServerId = server.id, mcpMessage = "Starting ${server.name}... a first start downloads its package, so this can take a minute.") }
            // v3.2.40 (Scope C): mirror the session's live start stage
            // into the busy banner — "Downloading packages…" while the
            // guest pulls the package, so the wait reads as progress.
            val stageJob = host.scope.launch {
                while (true) {
                    delay(700)
                    val label = toolRegistry.startupStageFor(server.id) ?: continue
                    host.updateState {
                        if (it.mcpBusy && it.mcpSelectedServerId == server.id) {
                            it.copy(mcpMessage = label)
                        } else {
                            it
                        }
                    }
                }
            }
            // Registry discovery does blocking process I/O: keep it off Main.
            val res = withContext(Dispatchers.IO) {
                runCatching { toolRegistry.discover(server.id, host.state().activeProject?.id) }
            }
            stageJob.cancel()
            val failure = res.exceptionOrNull()
            when {
                failure is McpMissingCredentialsException -> host.updateState {
                    val discovering = it.mcpDiscoveringServerIds - server.id
                    it.copy(
                        mcpBusy = discovering.isNotEmpty(),
                        mcpDiscoveringServerIds = discovering,
                        mcpServersMissingKeys = it.mcpServersMissingKeys + (server.id to failure.missing),
                        mcpServerErrors = it.mcpServerErrors - server.id,
                        mcpMessage = "${server.name} needs an API key — add it on its card. ${toolsReadySummary(it.mcpServers, it.mcpToolCounts)}"
                    )
                }
                failure != null -> host.updateState {
                    val detail = failure.message?.takeIf { it.isNotBlank() } ?: "unknown error (no detail reported)"
                    val discovering = it.mcpDiscoveringServerIds - server.id
                    it.copy(
                        mcpBusy = discovering.isNotEmpty(),
                        mcpDiscoveringServerIds = discovering,
                        mcpMessage = "${server.name} could not start — the reason is on its card. ${toolsReadySummary(it.mcpServers, it.mcpToolCounts)}",
                        mcpServerErrors = it.mcpServerErrors + (server.id to detail)
                    )
                }
                else -> {
                    val tools = res.getOrThrow().map { tool ->
                        com.vayro.app.runtime.McpToolDefinition(
                            name = tool.name,
                            description = tool.description,
                            inputSchemaJson = tool.inputSchema.toString(),
                            serverId = server.id,
                        )
                    }
                    host.updateState {
                        val discovering = it.mcpDiscoveringServerIds - server.id
                        val counts = it.mcpToolCounts + (server.id to tools.size)
                        it.copy(
                            mcpBusy = discovering.isNotEmpty(),
                            mcpDiscoveringServerIds = discovering,
                            mcpTools = tools,
                            mcpMessage = "Found ${tools.size} tools on ${server.name}. ${toolsReadySummary(it.mcpServers, counts)}",
                            mcpToolCounts = counts,
                            mcpServerErrors = it.mcpServerErrors - server.id,
                            mcpServersMissingKeys = it.mcpServersMissingKeys - server.id
                        )
                    }
                }
            }
        }
    }

    suspend fun executeMcpTool(
        server: McpServerInfo,
        toolName: String,
        arguments: JSONObject,
        projectId: String? = host.state().activeProject?.id
    ): com.vayro.app.runtime.McpToolCallResult {
        // Permission evaluation + audit logging are owned by the registry;
        // this coordinator only maps the outcome into the UI-facing shape.
        val startedAt = System.currentTimeMillis()
        val duration = { System.currentTimeMillis() - startedAt }
        return try {
            val outcome = withContext(Dispatchers.IO) {
                toolRegistry.callTool(server.id, toolName, arguments, projectId)
            }
            loadMcpAuditLogs()
            com.vayro.app.runtime.McpToolCallResult(
                success = !outcome.isError,
                content = outcome.text,
                error = if (outcome.isError) outcome.text else null,
                durationMs = duration(),
            )
        } catch (e: McpDeniedException) {
            loadMcpAuditLogs()
            com.vayro.app.runtime.McpToolCallResult(
                success = false,
                content = "",
                error = "Permission check failed: ${e.decision.reason}",
                durationMs = duration(),
            )
        } catch (e: McpPromptRequiredException) {
            loadMcpAuditLogs()
            com.vayro.app.runtime.McpToolCallResult(
                success = false,
                content = "",
                error = "MCP tool requires confirmation: ${e.decision.reason}",
                durationMs = duration(),
            )
        } catch (e: Exception) {
            loadMcpAuditLogs()
            com.vayro.app.runtime.McpToolCallResult(
                success = false,
                content = "",
                error = e.message ?: "MCP tool call failed",
                durationMs = duration(),
            )
        }
    }

    fun setMcpProjectPermission(projectId: String, serverId: String, policy: com.vayro.app.mcp.McpPermissionPolicy) {
        mcpPermissionManager.setProjectServerPolicy(projectId, serverId, policy)
    }

    fun setMcpGlobalPermission(serverId: String, policy: com.vayro.app.mcp.McpPermissionPolicy) {
        mcpPermissionManager.setGlobalServerPolicy(serverId, policy)
    }

    fun mcpToolDecision(
        serverId: String,
        toolName: String,
        projectId: String? = host.state().activeProject?.id
    ): com.vayro.app.mcp.McpPermissionDecision {
        val server = mcpRegistry.getServer(serverId)
            ?: return com.vayro.app.mcp.McpPermissionDecision(
                allowed = false,
                requiresPrompt = false,
                reason = "MCP server '$serverId' is not installed"
            )
        return mcpPermissionManager.evaluate(server, toolName, projectId)
    }

    fun grantOneTimeMcpToolApproval(
        serverId: String,
        toolName: String,
        projectId: String? = host.state().activeProject?.id
    ) {
        mcpPermissionManager.grantOneTimeApproval(serverId, toolName, projectId)
    }

    fun allowMcpToolAlways(
        serverId: String,
        toolName: String,
        projectId: String? = host.state().activeProject?.id
    ) {
        if (projectId != null) {
            mcpPermissionManager.setProjectToolPolicy(
                projectId, serverId, toolName,
                com.vayro.app.mcp.McpPermissionPolicy.ALLOW_ALWAYS
            )
        } else {
            mcpPermissionManager.setGlobalToolPolicy(
                serverId, toolName,
                com.vayro.app.mcp.McpPermissionPolicy.ALLOW_ALWAYS
            )
        }
    }

    fun loadMcpAuditLogs(limit: Int = 100) {
        val logs = mcpAuditLogger.getEntries(limit)
        host.updateState { it.copy(mcpAuditLogs = logs) }
    }

    fun clearMcpAuditLogs() {
        mcpAuditLogger.clear()
        host.updateState { it.copy(mcpAuditLogs = emptyList()) }
    }
}
