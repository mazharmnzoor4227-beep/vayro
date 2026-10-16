package com.vayro.app.mcp

import com.vayro.app.security.PermissionPolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpPlatformTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var registry: McpRegistry
    private lateinit var permissionManager: McpPermissionManager
    private lateinit var auditLogger: McpAuditLogger
    private lateinit var policyEngine: PermissionPolicyEngine

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("mcp_test_dir")
        val securityDir = tempFolder.newFolder("security_dir")
        policyEngine = PermissionPolicyEngine(securityDir)
        registry = McpRegistry(baseDir)
        permissionManager = McpPermissionManager(baseDir, policyEngine)
        auditLogger = McpAuditLogger(baseDir)
    }

    @Test
    fun `mcp registry has catalog templates and supports template installation`() {
        val templates = registry.catalogTemplates
        assertTrue("Catalog must contain curated templates", templates.size >= 8)
        val sqliteTemplate = templates.find { it.id == "sqlite" }
        assertNotNull(sqliteTemplate)
        assertEquals("Database", sqliteTemplate?.category)

        // Install from template
        val installedServer = registry.installFromTemplate("sqlite")
        assertEquals("sqlite", installedServer.id)
        assertTrue(installedServer.enabled)
        assertTrue(installedServer.isBuiltIn)

        // Retrieve from registry
        val retrieved = registry.getServer("sqlite")
        assertNotNull(retrieved)
        assertEquals("npx -y @modelcontextprotocol/server-sqlite", retrieved?.command)

        // Reload from disk to verify persistence
        val reloadedRegistry = McpRegistry(baseDir)
        val reloadedServer = reloadedRegistry.getServer("sqlite")
        assertNotNull("Server must persist across reloads", reloadedServer)
        assertEquals(installedServer.name, reloadedServer?.name)
    }

    @Test
    fun `custom mcp server lifecycle works end to end`() {
        val custom = McpServerInfo(
            id = "custom-node-server",
            name = "Custom Node MCP",
            description = "Custom testing tool runner",
            command = "node",
            args = listOf("server.js", "--port", "3000"),
            env = mapOf("API_KEY" to "secret123"),
            enabled = true,
            isBuiltIn = false
        )

        registry.installServer(custom)
        val server = registry.getServer("custom-node-server")
        assertNotNull(server)
        assertEquals("secret123", server?.env?.get("API_KEY"))

        // Toggle disabled
        registry.toggleServer("custom-node-server", false)
        assertFalse(registry.getServer("custom-node-server")?.enabled ?: true)

        // Remove
        val removed = registry.removeServer("custom-node-server")
        assertTrue(removed)
        assertNull(registry.getServer("custom-node-server"))
    }

    @Test
    fun `search catalog returns matching results`() {
        val searchGithub = registry.searchCatalog("github")
        assertTrue(searchGithub.any { it.id == "github" })

        val searchDatabase = registry.searchCatalog("database")
        assertTrue(searchDatabase.any { it.id == "sqlite" || it.id == "postgres" })
    }

    @Test
    fun `search catalog ranks exact and prefix name matches first`() {
        val results = registry.searchCatalog("github")
        assertTrue(results.isNotEmpty())
        val firstNameMatch = results.indexOfFirst { it.name.lowercase().contains("github") }
        val firstDescriptionOnlyMatch = results.indexOfFirst {
            !it.name.lowercase().contains("github") && it.description.lowercase().contains("github")
        }
        if (firstDescriptionOnlyMatch >= 0) {
            assertTrue(firstNameMatch < firstDescriptionOnlyMatch)
        }
    }

    @Test
    fun `mcp permission manager enforces project level and global boundaries`() {
        val server = McpServerInfo(
            id = "test-server",
            name = "Test Server",
            command = "test-cmd",
            enabled = true,
            isBuiltIn = false
        )

        // 1. Custom server defaults to REQUIRE_PROMPT
        val defaultDecision = permissionManager.evaluate(server, "test_tool")
        assertFalse("Custom server requires prompt by default", defaultDecision.allowed)
        assertTrue(defaultDecision.requiresPrompt)

        // 2. Global server policy grant ALLOW_ALWAYS
        permissionManager.setGlobalServerPolicy("test-server", McpPermissionPolicy.ALLOW_ALWAYS)
        val globalDecision = permissionManager.evaluate(server, "test_tool")
        assertTrue("Global policy should allow always", globalDecision.allowed)
        assertFalse(globalDecision.requiresPrompt)

        // 3. Project override: project-1 DENIES test-server
        permissionManager.setProjectServerPolicy("project-1", "test-server", McpPermissionPolicy.DENY)
        val project1Decision = permissionManager.evaluate(server, "test_tool", projectId = "project-1")
        assertFalse("Project-1 override must deny", project1Decision.allowed)
        assertFalse(project1Decision.requiresPrompt)

        // Project-2 has no override -> inherits global ALLOW_ALWAYS
        val project2Decision = permissionManager.evaluate(server, "test_tool", projectId = "project-2")
        assertTrue("Project-2 should inherit global allow", project2Decision.allowed)

        // 4. Emergency kill switch blocks everything
        policyEngine.engageEmergencyKillSwitch("Security lockdown")
        val killDecision = permissionManager.evaluate(server, "test_tool")
        assertFalse(killDecision.allowed)
        assertTrue(killDecision.reason.contains("Emergency kill switch is ACTIVE"))
    }

    @Test
    fun `mcp audit logger records, filters and clears execution events`() {
        auditLogger.clear()

        auditLogger.log(
            McpAuditEntry(
                serverId = "sqlite",
                toolName = "query",
                argumentsJson = "{\"sql\":\"SELECT * FROM users\"}",
                success = true,
                resultPreview = "[{\"id\":1}]",
                durationMs = 45L
            )
        )
        auditLogger.log(
            McpAuditEntry(
                serverId = "github",
                toolName = "create_issue",
                argumentsJson = "{\"title\":\"Bug report\"}",
                success = false,
                error = "Bad credentials",
                durationMs = 120L
            )
        )

        val all = auditLogger.getEntries(10)
        assertEquals(2, all.size)

        // Filter by server
        val sqliteLogs = auditLogger.filter(serverId = "sqlite")
        assertEquals(1, sqliteLogs.size)
        assertEquals("query", sqliteLogs.first().toolName)

        // Filter by query
        val searchBug = auditLogger.filter(query = "Bug report")
        assertEquals(1, searchBug.size)

        val searchError = auditLogger.filter(successOnly = false)
        assertEquals(1, searchError.size)
        assertEquals("github", searchError.first().serverId)

        // Clear
        auditLogger.clear()
        assertTrue(auditLogger.getEntries().isEmpty())
    }
}
