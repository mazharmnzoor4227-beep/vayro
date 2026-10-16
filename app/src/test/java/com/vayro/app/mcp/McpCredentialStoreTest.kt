package com.vayro.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpCredentialStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("cred_test")
    }

    @Test
    fun `in-memory backend round trips credentials`() {
        val store = McpCredentialStore(baseDir, InMemoryCredentialBackend())
        store.setCredential("srv1", "API_KEY", "secret-123")
        assertEquals("secret-123", store.getCredential("srv1", "API_KEY"))
        assertTrue(store.hasCredential("srv1", "API_KEY"))
        store.removeCredential("srv1", "API_KEY")
        assertFalse(store.hasCredential("srv1", "API_KEY"))
        assertNull(store.getCredential("srv1", "API_KEY"))
    }

    @Test
    fun `credentials are scoped per server`() {
        val store = McpCredentialStore(baseDir, InMemoryCredentialBackend())
        store.setCredential("srv1", "TOKEN", "token-one")
        store.setCredential("srv2", "TOKEN", "token-two")
        assertEquals("token-one", store.getCredential("srv1", "TOKEN"))
        assertEquals("token-two", store.getCredential("srv2", "TOKEN"))
    }

    @Test
    fun `blank credential name or value is rejected`() {
        val store = McpCredentialStore(baseDir, InMemoryCredentialBackend())
        try {
            store.setCredential("srv1", "", "x")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
        try {
            store.setCredential("srv1", "K", "  ")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `removeServerCredentials purges only that server`() {
        val store = McpCredentialStore(baseDir, InMemoryCredentialBackend())
        store.setCredential("srv1", "A", "1")
        store.setCredential("srv1", "B", "2")
        store.setCredential("srv2", "A", "3")
        store.removeServerCredentials("srv1", listOf("A", "B"))
        assertNull(store.getCredential("srv1", "A"))
        assertNull(store.getCredential("srv1", "B"))
        assertEquals("3", store.getCredential("srv2", "A"))
    }

    @Test
    fun `removeAllServerCredentials purges unlisted names too`() {
        val store = McpCredentialStore(baseDir, InMemoryCredentialBackend())
        store.setCredential("srv1", "A", "1")
        store.setCredential("srv1", "UNDOCUMENTED_NAME", "2")
        store.setCredential("srv2", "A", "3")
        store.removeAllServerCredentials("srv1")
        assertNull(store.getCredential("srv1", "A"))
        assertNull(store.getCredential("srv1", "UNDOCUMENTED_NAME"))
        assertEquals("3", store.getCredential("srv2", "A"))
    }

    @Test
    fun `removeAllServerCredentials leaves project-scoped sibling installs alone`() {
        val store = McpCredentialStore(baseDir, InMemoryCredentialBackend())
        store.setCredential("filesystem", "TOKEN", "global-secret")
        store.setCredential("filesystem::projA", "TOKEN", "project-secret")
        store.removeAllServerCredentials("filesystem")
        assertNull(store.getCredential("filesystem", "TOKEN"))
        assertEquals("project-secret", store.getCredential("filesystem::projA", "TOKEN"))
        store.removeAllServerCredentials("filesystem::projA")
        assertNull(store.getCredential("filesystem::projA", "TOKEN"))
    }

    @Test
    fun `removeAllServerCredentials purges every key on the encrypted file backend`() {
        val backendDir = File(baseDir, "purge_aes")
        val store = McpCredentialStore(backendDir, AesGcmFileCredentialBackend(File(backendDir, "creds.json")))
        store.setCredential("srv1", "A", "1")
        store.setCredential("srv1", "B", "2")
        store.setCredential("srv2", "A", "3")
        store.removeAllServerCredentials("srv1")
        assertNull(store.getCredential("srv1", "A"))
        assertNull(store.getCredential("srv1", "B"))
        assertEquals("3", store.getCredential("srv2", "A"))
    }

    @Test
    fun `aes-gcm backend round trips and survives recreation`() {
        val backendDir = File(baseDir, "aes")
        val store = McpCredentialStore(backendDir, AesGcmFileCredentialBackend(File(backendDir, "creds.json")))
        store.setCredential("srv1", "API_KEY", "super-secret-value")
        assertEquals("super-secret-value", store.getCredential("srv1", "API_KEY"))

        // A new backend over the same files must decrypt what the old one wrote.
        val store2 = McpCredentialStore(backendDir, AesGcmFileCredentialBackend(File(backendDir, "creds.json")))
        assertEquals("super-secret-value", store2.getCredential("srv1", "API_KEY"))
    }

    @Test
    fun `aes-gcm backend never writes secrets in plaintext`() {
        val backendDir = File(baseDir, "aes2")
        val storageFile = File(backendDir, "creds.json")
        val store = McpCredentialStore(backendDir, AesGcmFileCredentialBackend(storageFile))
        val secret = "plaintext-must-never-appear-9f8e7d"
        store.setCredential("srv1", "KEY", secret)

        assertTrue("storage file must exist", storageFile.exists())
        val raw = storageFile.readText()
        assertFalse("secret leaked into storage file", raw.contains(secret))
        // The credential key ("srv1::KEY") is a non-secret identifier by design and may
        // appear as a JSON key; only the secret VALUE must never be in plaintext.
        assertTrue("entry identifier present", raw.contains("srv1::KEY"))
        // Key file must exist and hold a 32-byte key, not the secret.
        val keyFile = File(backendDir, "mcp_cred.key")
        assertTrue(keyFile.exists())
        assertEquals(32, keyFile.readBytes().size)
    }

    @Test
    fun `default backend is the encrypted file backend`() {
        val backendDir = File(baseDir, "default")
        // Android Keystore does not exist on the JVM; the test hook stands in.
        McpCredentialStore.defaultKeyProviderForTests =
            SidecarFileKeyProvider(File(backendDir, "mcp_cred.key"))
        try {
            val store = McpCredentialStore(backendDir)
            store.setCredential("srv9", "TOKEN", "hidden-value-123")
            assertEquals("hidden-value-123", store.getCredential("srv9", "TOKEN"))
            val storageFile = File(backendDir, "mcp_credentials.json")
            assertTrue(storageFile.exists())
            assertFalse(storageFile.readText().contains("hidden-value-123"))
        } finally {
            McpCredentialStore.defaultKeyProviderForTests = null
        }
    }

    @Test
    fun `credentialKey format is stable`() {
        val store = McpCredentialStore(baseDir, InMemoryCredentialBackend())
        assertEquals("srv::NAME", store.credentialKey("srv", "NAME"))
    }

    @Test
    fun `clear deletes the key file as well as the store`() {
        val backendDir = File(baseDir, "clear_test")
        McpCredentialStore.defaultKeyProviderForTests =
            SidecarFileKeyProvider(File(backendDir, "mcp_cred.key"))
        try {
            val store = McpCredentialStore(backendDir)
            store.setCredential("srv1", "TOKEN", "hidden-value-123")
            val keyFile = File(backendDir, "mcp_cred.key")
            assertTrue(keyFile.exists())

            store.clear()

            assertFalse(File(backendDir, "mcp_credentials.json").exists())
            assertFalse("key file must not linger after clear", keyFile.exists())
        } finally {
            McpCredentialStore.defaultKeyProviderForTests = null
        }
    }

    @Test
    fun `keystore provider clearKey removes the legacy sidecar key file`() {
        val backendDir = File(baseDir, "keystore_clear_test").apply { mkdirs() }
        val legacyKey = File(backendDir, "mcp_cred.key")
        legacyKey.writeBytes(ByteArray(32) { it.toByte() })
        val provider = AndroidKeyStoreKeyProvider(
            alias = "test_mcp_credential_key",
            legacyKeyFile = legacyKey,
            storageFile = File(backendDir, "mcp_credentials.json"),
        )

        provider.clearKey()

        assertFalse("legacy sidecar key must not linger after clear", legacyKey.exists())
    }
}
