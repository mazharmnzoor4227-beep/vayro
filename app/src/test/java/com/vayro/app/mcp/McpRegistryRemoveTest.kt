package com.vayro.app.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * QA Medium: removing an MCP server used to leave its stored credentials
 * behind (McpCoordinator called plain removeServer). Removal must purge
 * the server's secrets too.
 */
class McpRegistryRemoveTest {

    private lateinit var dir: File
    private lateinit var registry: McpRegistry
    private lateinit var credentials: McpCredentialStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "mcp_remove_test_" + System.nanoTime())
        registry = McpRegistry(File(dir, "registry"))
        credentials = McpCredentialStore(File(dir, "creds"), InMemoryCredentialBackend())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun removeServerAndCredentialsPurgesSecrets() {
        registry.installServer(
            McpServerInfo(
                id = "srv-1",
                name = "Secretful",
                command = "node",
                secretEnvNames = listOf("API_TOKEN"),
                credentialName = "api_key",
            )
        )
        registry.installServer(McpServerInfo(id = "srv-2", name = "Other", command = "node"))
        credentials.setCredential("srv-1", "API_TOKEN", "token-secret")
        credentials.setCredential("srv-1", "api_key", "key-secret")
        credentials.setCredential("srv-2", "API_TOKEN", "other-secret")

        assertTrue(registry.removeServerAndCredentials("srv-1", credentials))

        assertNull(registry.getServer("srv-1"))
        assertNull(credentials.getCredential("srv-1", "API_TOKEN"))
        assertNull(credentials.getCredential("srv-1", "api_key"))
        // Another server's credentials are untouched.
        assertTrue(credentials.hasCredential("srv-2", "API_TOKEN"))
    }

    @Test
    fun removeServerAndCredentialsPurgesUnlistedCredentialNames() {
        registry.installServer(
            McpServerInfo(
                id = "srv-1",
                name = "Secretful",
                command = "node",
                secretEnvNames = listOf("API_TOKEN"),
            )
        )
        credentials.setCredential("srv-1", "API_TOKEN", "token-secret")
        credentials.setCredential("srv-1", "SOME_UNLISTED_NAME", "lingering-secret")

        assertTrue(registry.removeServerAndCredentials("srv-1", credentials))

        assertNull(registry.getServer("srv-1"))
        assertNull(credentials.getCredential("srv-1", "API_TOKEN"))
        assertNull(credentials.getCredential("srv-1", "SOME_UNLISTED_NAME"))
    }

    @Test
    fun removeServerAndCredentialsOnUnknownIdReturnsFalse() {
        assertFalse(registry.removeServerAndCredentials("nope", credentials))
    }

    @Test
    fun sameTemplateForTwoProjectsDoesNotOverwrite() {
        val a = registry.installFromTemplate("filesystem", projectId = "proj-a")
        val b = registry.installFromTemplate("filesystem", projectId = "proj-b")

        assertTrue(a.id != b.id)
        assertEquals("proj-a", registry.getServer(a.id)?.projectId)
        assertEquals("proj-b", registry.getServer(b.id)?.projectId)
    }

    @Test
    fun globalTemplateInstallKeepsStableId() {
        val first = registry.installFromTemplate("filesystem")
        val second = registry.installFromTemplate("filesystem")

        assertEquals(first.id, second.id)
    }

    @Test
    fun installFromTemplateRejectsSecretNamesInEnvOverrides() {
        try {
            registry.installFromTemplate("github", mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "shhh"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("GITHUB_PERSONAL_ACCESS_TOKEN"))
        }
        assertNull(registry.getServer("github"))
    }

    @Test
    fun installFromTemplateAcceptsNonSecretEnvOverrides() {
        val installed = registry.installFromTemplate("github", mapOf("GH_HOST" to "ghe.example.com"))
        assertEquals("ghe.example.com", installed.env["GH_HOST"])
        assertTrue(installed.secretEnvNames.contains("GITHUB_PERSONAL_ACCESS_TOKEN"))
    }

    @Test
    fun spawnMarkersPersistAcrossReloads() {
        assertFalse(registry.hasSpawnedBefore("custom-x"))
        registry.markSpawned("custom-x")
        assertTrue(registry.hasSpawnedBefore("custom-x"))
        val reloaded = McpRegistry(File(dir, "registry"))
        assertTrue(reloaded.hasSpawnedBefore("custom-x"))
        assertFalse(reloaded.hasSpawnedBefore("other"))
    }
}
