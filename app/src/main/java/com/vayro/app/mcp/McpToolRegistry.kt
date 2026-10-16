package com.vayro.app.mcp

import org.json.JSONObject

/** Thrown when a tool call is denied by the permission policy (never reaches the server). */
class McpDeniedException(val decision: McpPermissionDecision) :
    Exception("MCP tool denied: ${decision.reason}")

/** Thrown when a tool call requires explicit user confirmation first. */
class McpPromptRequiredException(val decision: McpPermissionDecision) :
    Exception("MCP tool requires confirmation: ${decision.reason}")

/** Cached discovery result for one server. */
data class McpDiscoveredTools(
    val serverId: String,
    val tools: List<McpToolDefinition>,
    val discoveredAt: Long
)

/**
 * Coordinates MCP tool discovery and invocation across stdio and remote servers.
 *
 * - Discovery (tools/list) results are cached with a TTL to avoid repeated handshakes.
 * - Every call is evaluated by [McpPermissionManager] first: DENY and disabled
 *   servers never reach the transport; PROMPT surfaces as [McpPromptRequiredException].
 * - Every attempt is recorded in [McpAuditLogger] with its permission decision.
 * - Stdio sessions are started lazily with secrets resolved from [McpCredentialStore].
 */
class McpToolRegistry(
    private val registry: McpRegistry,
    private val permissionManager: McpPermissionManager,
    private val auditLogger: McpAuditLogger,
    private val credentialStore: McpCredentialStore,
    private val sessionFactory: (McpServerInfo) -> McpServerSession = { McpServerSession(it) },
    private val remoteClientFactory: (McpServerInfo, McpRemoteConfig) -> McpRemoteOperations =
        { server, config -> McpRemoteClient(server, config, credentialStore) },
    private val discoveryTtlMs: Long = 5 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val sessions = mutableMapOf<String, McpServerSession>()
    private val discoveryCache = mutableMapOf<String, McpDiscoveredTools>()
    private val lock = Any()

    /**
     * Resolves the process environment for a stdio server: static config first,
     * then secrets from the credential store. A declared-but-missing secret fails
     * honestly instead of starting a misconfigured server.
     */
    fun resolveEnv(server: McpServerInfo): Map<String, String> {
        val secretNames = server.secretEnvNames.toSet()
        val resolved = server.env.filterKeys { it !in secretNames }.toMutableMap()
        val missing = mutableListOf<String>()
        for (name in server.secretEnvNames) {
            val value = credentialStore.getCredential(server.id, name)
            if (value == null) missing.add(name) else resolved[name] = value
        }
        if (missing.isNotEmpty()) {
            throw McpConfigurationException(
                "MCP server '${server.id}' is missing required credentials: ${missing.joinToString(", ")}"
            )
        }
        return resolved
    }

    private fun requireServer(serverId: String): McpServerInfo =
        registry.getServer(serverId)
            ?: throw IllegalArgumentException("MCP server '$serverId' is not installed")

    private fun sessionFor(server: McpServerInfo): McpServerSession {
        synchronized(lock) {
            return sessions.getOrPut(server.id) { sessionFactory(server) }
        }
    }

    private fun auditServerSpawn(server: McpServerInfo, projectId: String?) {
        val firstCustomSpawn = !server.isBuiltIn && !registry.hasSpawnedBefore(server.id)
        if (firstCustomSpawn) registry.markSpawned(server.id)
        auditLogger.log(
            McpAuditEntry(
                serverId = server.id,
                toolName = if (firstCustomSpawn) "FIRST_CUSTOM_SERVER_SPAWN" else "SERVER_SPAWN",
                argumentsJson = "{}",
                success = true,
                resultPreview = "Spawned ${if (server.isBuiltIn) "built-in" else "custom"} server process: ${server.command}",
                permissionDecision = if (firstCustomSpawn) "WARNING_FIRST_SPAWN" else "SPAWNED",
                error = if (firstCustomSpawn) "First spawn of custom MCP server '${server.name}'. It runs as this app with the app's full privileges; no sandbox is applied. Review its command before use." else null,
                projectId = projectId
            )
        )
    }

    /**
     * Discovers tools via tools/list, honoring the discovery cache TTL.
     * @throws McpDeniedException when the server is disabled or denied by policy.
     */
    fun discover(serverId: String, projectId: String? = null, forceRefresh: Boolean = false): List<McpToolDefinition> {
        val server = requireServer(serverId)
        val decision = permissionManager.evaluate(server, projectId = projectId)
        if (!decision.allowed && !decision.requiresPrompt) {
            throw McpDeniedException(decision)
        }
        synchronized(lock) {
            val cached = discoveryCache[serverId]
            if (!forceRefresh && cached != null && clock() - cached.discoveredAt < discoveryTtlMs) {
                return cached.tools
            }
        }
        val tools = if (server.isRemote) {
            val config = server.remoteConfig()
                ?: throw McpConfigurationException("Remote MCP server '${server.id}' has no URL configured")
            remoteClientFactory(server, config).listTools()
        } else {
            val session = sessionFor(server)
            val wasRunning = session.state == McpSessionState.RUNNING
            if (!session.ensureRunning(resolveEnv(server))) {
                throw McpTransportException(
                    "MCP server '${server.id}' could not be started: ${session.diagnostics().takeLast(3).joinToString("; ")}"
                )
            }
            if (!wasRunning) auditServerSpawn(server, projectId)
            session.listTools()
        }
        synchronized(lock) {
            discoveryCache[serverId] = McpDiscoveredTools(serverId, tools, clock())
        }
        return tools
    }

    /**
     * Invokes one tool through the permission gate and audit log.
     * @throws McpDeniedException on DENY / disabled, [McpPromptRequiredException] on PROMPT.
     */
    fun callTool(
        serverId: String,
        toolName: String,
        arguments: JSONObject = JSONObject(),
        projectId: String? = null
    ): McpToolCallOutcome {
        val server = requireServer(serverId)
        val argsJson = arguments.toString()
        val startedAt = clock()
        val decision = permissionManager.evaluate(server, toolName, projectId)
        if (!decision.allowed && !decision.requiresPrompt) {
            auditLogger.log(
                McpAuditEntry(
                    serverId = serverId,
                    toolName = toolName,
                    argumentsJson = argsJson,
                    success = false,
                    permissionDecision = "DENIED",
                    error = decision.reason,
                    durationMs = clock() - startedAt,
                    projectId = projectId
                )
            )
            throw McpDeniedException(decision)
        }
        if (!decision.allowed && decision.requiresPrompt) {
            auditLogger.log(
                McpAuditEntry(
                    serverId = serverId,
                    toolName = toolName,
                    argumentsJson = argsJson,
                    success = false,
                    permissionDecision = "PROMPT_REQUIRED",
                    error = decision.reason,
                    durationMs = clock() - startedAt,
                    projectId = projectId
                )
            )
            throw McpPromptRequiredException(decision)
        }
        val outcome = try {
            if (server.isRemote) {
                val config = server.remoteConfig()
                    ?: throw McpConfigurationException("Remote MCP server '${server.id}' has no URL configured")
                remoteClientFactory(server, config).callTool(toolName, arguments)
            } else {
                val session = sessionFor(server)
                val wasRunning = session.state == McpSessionState.RUNNING
                if (!session.ensureRunning(resolveEnv(server))) {
                    throw McpTransportException("MCP server '${server.id}' could not be started")
                }
                if (!wasRunning) auditServerSpawn(server, projectId)
                session.callTool(toolName, arguments)
            }
        } catch (t: Exception) {
            auditLogger.log(
                McpAuditEntry(
                    serverId = serverId,
                    toolName = toolName,
                    argumentsJson = argsJson,
                    success = false,
                    permissionDecision = "ALLOWED",
                    error = "${t.javaClass.simpleName}: ${t.message}",
                    durationMs = clock() - startedAt,
                    projectId = projectId
                )
            )
            throw t
        }
        auditLogger.log(
            McpAuditEntry(
                serverId = serverId,
                toolName = toolName,
                argumentsJson = argsJson,
                success = !outcome.isError,
                resultPreview = outcome.text.take(500),
                permissionDecision = "ALLOWED",
                durationMs = clock() - startedAt,
                projectId = projectId
            )
        )
        return outcome
    }

    /** Drops cached discovery for a server (e.g. after update). */
    fun invalidate(serverId: String) {
        synchronized(lock) { discoveryCache.remove(serverId) }
    }

    /** Stops one server's stdio session and drops its cached discovery. */
    fun stopServer(serverId: String) {
        synchronized(lock) {
            sessions.remove(serverId)?.let { runCatching { it.stop() } }
            discoveryCache.remove(serverId)
        }
    }

    /** Stops all managed stdio sessions and clears the discovery cache. */
    fun shutdown() {
        synchronized(lock) {
            sessions.values.forEach { runCatching { it.stop() } }
            sessions.clear()
            discoveryCache.clear()
        }
    }
}
