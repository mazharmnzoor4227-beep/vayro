package com.vayro.app.mcp

import com.vayro.app.security.PermissionPolicyEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class FakeRemoteOps(
    var tools: List<McpToolDefinition> = listOf(McpToolDefinition("remote_tool")),
    var outcome: McpToolCallOutcome = McpToolCallOutcome(false, "remote-ok")
) : McpRemoteOperations {
    var listCalls = 0
    var callCalls = 0
    override fun listTools(): List<McpToolDefinition> {
        listCalls++
        return tools
    }
    override fun callTool(toolName: String, arguments: JSONObject): McpToolCallOutcome {
        callCalls++
        return outcome
    }
    override fun ping(): Boolean = true
}

class McpToolRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var registry: McpRegistry
    private lateinit var permissionManager: McpPermissionManager
    private lateinit var auditLogger: McpAuditLogger
    private lateinit var credentialStore: McpCredentialStore

    private var nowMs: Long = 1_000_000L
    private var sessionFactoryCalls = 0
    private val liveFakes = mutableListOf<FakeMcpProcess>()
    private var remoteOps = FakeRemoteOps()

    private fun stdioSessionFactory(server: McpServerInfo): McpServerSession {
        sessionFactoryCalls++
        val fake = FakeMcpProcess(mcpHandshakeResponders())
        liveFakes.add(fake)
        return McpServerSession(
            server,
            ScriptedMcpProcessFactory(mutableListOf(fake)),
            ioTimeoutMs = 2_000,
            restartBackoffMs = 0
        )
    }

    private fun toolRegistry(): McpToolRegistry = McpToolRegistry(
        registry = registry,
        permissionManager = permissionManager,
        auditLogger = auditLogger,
        credentialStore = credentialStore,
        sessionFactory = ::stdioSessionFactory,
        remoteClientFactory = { _, _ -> remoteOps },
        clock = { nowMs }
    )

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("toolreg_test")
        val securityDir = tempFolder.newFolder("toolreg_security")
        registry = McpRegistry(baseDir)
        permissionManager = McpPermissionManager(baseDir, PermissionPolicyEngine(securityDir))
        auditLogger = McpAuditLogger(baseDir)
        credentialStore = McpCredentialStore(baseDir, InMemoryCredentialBackend())
        nowMs = 1_000_000L
        sessionFactoryCalls = 0
        liveFakes.clear()
        remoteOps = FakeRemoteOps()

        registry.installServer(testStdioServer("s1").copy(isBuiltIn = true))
        registry.installServer(
            McpServerInfo(
                id = "r1",
                name = "Remote",
                command = "",
                transport = McpTransportType.STREAMABLE_HTTP,
                remoteUrl = "https://mcp.example.com/rpc",
                isBuiltIn = true
            )
        )
        permissionManager.setGlobalServerPolicy("s1", McpPermissionPolicy.ALLOW_ALWAYS)
        permissionManager.setGlobalServerPolicy("r1", McpPermissionPolicy.ALLOW_ALWAYS)
    }

    private fun toolsListRequests(): Int =
        liveFakes.sumOf { fake -> fake.written.count { JSONObject(it).optString("method") == "tools/list" } }

    @Test
    fun `discover caches tools within ttl`() {
        val tr = toolRegistry()
        val first = tr.discover("s1")
        assertEquals(listOf("echo"), first.map { it.name })
        val second = tr.discover("s1")
        assertEquals(first.map { it.name }, second.map { it.name })
        assertEquals(1, sessionFactoryCalls)
        assertEquals(1, toolsListRequests())
    }

    @Test
    fun `discover refreshes after ttl expiry`() {
        val tr = McpToolRegistry(
            registry, permissionManager, auditLogger, credentialStore,
            sessionFactory = ::stdioSessionFactory,
            remoteClientFactory = { _, _ -> remoteOps },
            discoveryTtlMs = 60_000,
            clock = { nowMs }
        )
        tr.discover("s1")
        assertEquals(1, toolsListRequests())
        nowMs += 61_000
        tr.discover("s1")
        assertEquals(2, toolsListRequests())
    }

    @Test
    fun `invalidate forces rediscovery`() {
        val tr = toolRegistry()
        tr.discover("s1")
        tr.invalidate("s1")
        tr.discover("s1")
        assertEquals(2, toolsListRequests())
    }

    @Test
    fun `stopServer stops the session and drops cached discovery`() {
        val tr = toolRegistry()
        tr.discover("s1")
        assertEquals(1, sessionFactoryCalls)
        val fake = liveFakes.single()
        assertTrue(fake.alive)
        tr.stopServer("s1")
        assertFalse(fake.alive)
        assertTrue(fake.gracefulCalls >= 1)
        // Rediscovery must start a fresh session, not reuse the stopped one.
        tr.discover("s1")
        assertEquals(2, sessionFactoryCalls)
        assertEquals(2, toolsListRequests())
    }

    @Test
    fun `callTool on allowed server returns outcome and audits allowed`() {
        val tr = toolRegistry()
        val outcome = tr.callTool("s1", "echo", JSONObject().put("text", "hi"))
        assertFalse(outcome.isError)
        assertEquals("echo-ok", outcome.text)
        val entry = auditLogger.getEntriesForServer("s1").first()
        assertEquals("echo", entry.toolName)
        assertEquals("ALLOWED", entry.permissionDecision)
        assertTrue(entry.success)
    }

    @Test
    fun `denied tool never reaches the transport and audits denied`() {
        permissionManager.setGlobalServerPolicy("s1", McpPermissionPolicy.DENY)
        val tr = toolRegistry()
        try {
            tr.callTool("s1", "echo")
            fail("expected McpDeniedException")
        } catch (e: McpDeniedException) {
            assertTrue(e.decision.reason.isNotBlank())
        }
        assertEquals(0, sessionFactoryCalls)
        val entry = auditLogger.getEntriesForServer("s1").first()
        assertEquals("DENIED", entry.permissionDecision)
        assertFalse(entry.success)
    }

    @Test
    fun `prompt policy surfaces prompt-required without calling transport`() {
        permissionManager.setGlobalServerPolicy("s1", McpPermissionPolicy.REQUIRE_PROMPT)
        val tr = toolRegistry()
        try {
            tr.callTool("s1", "echo")
            fail("expected McpPromptRequiredException")
        } catch (e: McpPromptRequiredException) {
            assertTrue(e.decision.requiresPrompt)
        }
        assertEquals(0, sessionFactoryCalls)
        assertEquals("PROMPT_REQUIRED", auditLogger.getEntriesForServer("s1").first().permissionDecision)
    }

    @Test
    fun `disabled server is denied`() {
        registry.toggleServer("s1", false)
        val tr = toolRegistry()
        try {
            tr.callTool("s1", "echo")
            fail("expected McpDeniedException")
        } catch (e: McpDeniedException) { /* expected */ }
        assertEquals(0, sessionFactoryCalls)
    }

    @Test
    fun `discover on denied server throws without transport`() {
        permissionManager.setGlobalServerPolicy("s1", McpPermissionPolicy.DENY)
        try {
            toolRegistry().discover("s1")
            fail("expected McpDeniedException")
        } catch (e: McpDeniedException) { /* expected */ }
        assertEquals(0, sessionFactoryCalls)
    }

    @Test
    fun `remote server dispatches to remote operations`() {
        val tr = toolRegistry()
        val tools = tr.discover("r1")
        assertEquals(listOf("remote_tool"), tools.map { it.name })
        assertEquals(1, remoteOps.listCalls)
        val outcome = tr.callTool("r1", "remote_tool")
        assertEquals("remote-ok", outcome.text)
        assertEquals(1, remoteOps.callCalls)
        assertEquals(0, sessionFactoryCalls)
    }

    @Test
    fun `resolveEnv merges stored secrets`() {
        registry.installServer(
            testStdioServer("s2").copy(
                isBuiltIn = true,
                env = mapOf("PLAIN" to "1"),
                secretEnvNames = listOf("API_KEY")
            )
        )
        credentialStore.setCredential("s2", "API_KEY", "sekret!")
        val env = toolRegistry().resolveEnv(registry.getServer("s2")!!)
        assertEquals("1", env["PLAIN"])
        assertEquals("sekret!", env["API_KEY"])
    }

    @Test
    fun `resolveEnv drops plaintext env shadowed by secret names`() {
        registry.installServer(
            testStdioServer("s4").copy(
                isBuiltIn = true,
                env = mapOf("PLAIN" to "1", "API_KEY" to "plaintext-leak"),
                secretEnvNames = listOf("API_KEY")
            )
        )
        credentialStore.setCredential("s4", "API_KEY", "sekret!")
        val env = toolRegistry().resolveEnv(registry.getServer("s4")!!)
        assertEquals("1", env["PLAIN"])
        assertEquals("sekret!", env["API_KEY"])
    }

    @Test
    fun `resolveEnv fails honestly on missing secret`() {
        registry.installServer(
            testStdioServer("s3").copy(isBuiltIn = true, secretEnvNames = listOf("MISSING_KEY"))
        )
        try {
            toolRegistry().resolveEnv(registry.getServer("s3")!!)
            fail("expected McpConfigurationException")
        } catch (e: McpConfigurationException) {
            assertTrue(e.message!!.contains("MISSING_KEY"))
        }
    }

    @Test
    fun `missing secret throws the typed state exception`() {
        // v3.2.40 (Scope C2): the coordinator keys the "Needs an API
        // key" card state off this exact type — it must carry the
        // missing names and stay a configuration exception.
        registry.installServer(
            testStdioServer("s5").copy(isBuiltIn = true, secretEnvNames = listOf("BRAVE_API_KEY"))
        )
        try {
            toolRegistry().resolveEnv(registry.getServer("s5")!!)
            fail("expected McpMissingCredentialsException")
        } catch (e: McpMissingCredentialsException) {
            assertEquals("s5", e.serverId)
            assertEquals(listOf("BRAVE_API_KEY"), e.missing)
        }
    }

    @Test
    fun `missingCredentials reflects the credential store`() {
        registry.installServer(
            testStdioServer("s6").copy(isBuiltIn = true, secretEnvNames = listOf("A_KEY", "B_KEY"))
        )
        val server = registry.getServer("s6")!!
        assertEquals(listOf("A_KEY", "B_KEY"), toolRegistry().missingCredentials(server))
        credentialStore.setCredential("s6", "A_KEY", "saved")
        assertEquals(listOf("B_KEY"), toolRegistry().missingCredentials(server))
        credentialStore.setCredential("s6", "B_KEY", "saved")
        assertEquals(emptyList<String>(), toolRegistry().missingCredentials(server))
    }

    @Test
    fun `unknown server fails honestly`() {
        try {
            toolRegistry().discover("nope")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nope"))
        }
    }

    @Test
    fun `transport failure is audited as failed`() {
        remoteOps = FakeRemoteOps(outcome = McpToolCallOutcome(true, "server blew up"))
        // Force the remote path to throw instead: swap in a throwing ops.
        val throwing = object : McpRemoteOperations {
            override fun listTools(): List<McpToolDefinition> = emptyList()
            override fun callTool(toolName: String, arguments: JSONObject): McpToolCallOutcome {
                throw McpTransportException("connection reset")
            }
            override fun ping(): Boolean = false
        }
        val tr = McpToolRegistry(
            registry, permissionManager, auditLogger, credentialStore,
            sessionFactory = ::stdioSessionFactory,
            remoteClientFactory = { _, _ -> throwing },
            clock = { nowMs }
        )
        try {
            tr.callTool("r1", "remote_tool")
            fail("expected McpTransportException")
        } catch (e: McpTransportException) { /* expected */ }
        val entry = auditLogger.getEntriesForServer("r1").first()
        assertFalse(entry.success)
        assertEquals("ALLOWED", entry.permissionDecision)
        assertTrue(entry.error!!.contains("connection reset"))
    }

    @Test
    fun `first custom server spawn is audited with warning`() {
        registry.installServer(testStdioServer("custom1").copy(isBuiltIn = false))
        toolRegistry().discover("custom1")
        val spawns = auditLogger.getEntriesForServer("custom1")
            .filter { it.toolName == "FIRST_CUSTOM_SERVER_SPAWN" }
        assertEquals(1, spawns.size)
        assertTrue(spawns[0].success)
        assertEquals("WARNING_FIRST_SPAWN", spawns[0].permissionDecision)
        assertTrue(spawns[0].error!!.contains("no sandbox"))
        assertTrue(registry.hasSpawnedBefore("custom1"))
    }

    @Test
    fun `custom server spawn warns only once`() {
        registry.installServer(testStdioServer("custom2").copy(isBuiltIn = false))
        toolRegistry().discover("custom2")
        toolRegistry().discover("custom2", forceRefresh = true)
        val warnings = auditLogger.getEntriesForServer("custom2")
            .filter { it.toolName == "FIRST_CUSTOM_SERVER_SPAWN" }
        assertEquals(1, warnings.size)
        val respawns = auditLogger.getEntriesForServer("custom2")
            .filter { it.toolName == "SERVER_SPAWN" }
        assertEquals(1, respawns.size)
    }

    @Test
    fun `built-in server spawn is audited without warning`() {
        toolRegistry().discover("s1")
        val spawns = auditLogger.getEntriesForServer("s1")
            .filter { it.toolName == "SERVER_SPAWN" }
        assertEquals(1, spawns.size)
        assertEquals("SPAWNED", spawns[0].permissionDecision)
        assertEquals(null, spawns[0].error)
        assertTrue(spawns[0].resultPreview.contains("fake-cmd"))
    }
}
