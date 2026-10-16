package com.vayro.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Fixed-key provider for tests: stands in for the Android Keystore key. */
private class FixedKeyProvider(private val key: SecretKey) : McpDataKeyProvider {
    override fun getKey(): SecretKey = key
    override fun clearKey() {}
}

private fun randomAesKey(): SecretKey =
    KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()

/**
 * QA agent-4 security finding: the MCP credential data key must not live in a
 * sidecar file next to the ciphertext. New installs use Android Keystore;
 * existing installs migrate once: entries are re-encrypted under the new key
 * and the sidecar file is deleted.
 */
class McpKeyMigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `legacy sidecar store migrates to a new key and the key file is deleted`() {
        val dir = temp.newFolder()
        val storageFile = File(dir, "mcp_credentials.json")
        val keyFile = File(dir, "mcp_cred.key")

        // Seed a legacy store the old way (sidecar key file).
        val legacy = AesGcmFileCredentialBackend(storageFile, keyFile)
        legacy.put("srv::token", "secret-token-value")
        legacy.put("srv::api_key", "api-key-value")
        assertTrue(keyFile.exists())

        // Migrate to a fresh key (what AndroidKeyStoreKeyProvider.getKey does
        // after creating the Keystore key).
        val newKey = randomAesKey()
        migrateCredentialStoreKey(storageFile, keyFile, newKey)

        assertFalse("sidecar key file must be deleted after migration", keyFile.exists())

        val migrated = AesGcmFileCredentialBackend(storageFile, FixedKeyProvider(newKey))
        assertEquals("secret-token-value", migrated.get("srv::token"))
        assertEquals("api-key-value", migrated.get("srv::api_key"))
    }

    @Test
    fun `migration is a no-op when no legacy key file exists`() {
        val dir = temp.newFolder()
        val storageFile = File(dir, "mcp_credentials.json")
        val newKey = randomAesKey()

        val fresh = AesGcmFileCredentialBackend(storageFile, FixedKeyProvider(newKey))
        fresh.put("srv::token", "v")
        migrateCredentialStoreKey(storageFile, File(dir, "mcp_cred.key"), randomAesKey())

        assertEquals("v", fresh.get("srv::token"))
    }

    @Test
    fun `clear removes the data key through the provider`() {
        val dir = temp.newFolder()
        val storageFile = File(dir, "mcp_credentials.json")
        val keyFile = File(dir, "mcp_cred.key")

        val backend = AesGcmFileCredentialBackend(storageFile, keyFile)
        backend.put("srv::token", "v")
        backend.clear()

        assertFalse(keyFile.exists())
        assertFalse(storageFile.exists())
        assertNull(backend.get("srv::token"))
    }
}
