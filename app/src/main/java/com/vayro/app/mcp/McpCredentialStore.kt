package com.vayro.app.mcp

import com.vayro.app.workspace.SafeFileOps
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Storage backend for MCP credentials. The encrypted backend is the production default;
 * the in-memory backend exists for unit tests.
 */
interface McpCredentialBackend {
    fun put(key: String, secret: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun removeAllForServer(serverId: String)
    fun clear()
}

/** Non-persistent backend for tests. Secrets live only in process memory. */
class InMemoryCredentialBackend : McpCredentialBackend {
    private val map = ConcurrentHashMap<String, String>()
    override fun put(key: String, secret: String) { map[key] = secret }
    override fun get(key: String): String? = map[key]
    override fun remove(key: String) { map.remove(key) }
    override fun removeAllForServer(serverId: String) {
        map.keys.removeIf { credentialKeyBelongsToServer(it, serverId) }
    }
    override fun clear() { map.clear() }
}

/**
 * True when a stored credential key was created for [serverId].
 * Keys have the shape `serverId::name`; a remainder containing `::`
 * belongs to a project-scoped child id (`template::project`), not to
 * [serverId] itself, so sibling installs are never purged together.
 */
internal fun credentialKeyBelongsToServer(key: String, serverId: String): Boolean {
    if (!key.startsWith("$serverId::")) return false
    return !key.removePrefix("$serverId::").contains("::")
}

/**
 * Supplies the AES data key a credential backend encrypts with.
 * Production uses Android Keystore; the sidecar-file provider is the legacy
 * path, kept for unit tests and for one-time migration.
 */
interface McpDataKeyProvider {
    fun getKey(): SecretKey
    fun clearKey()
}

/** Legacy provider: the data key lives in a sidecar file (owner-only POSIX perms). */
class SidecarFileKeyProvider(private val keyFile: File) : McpDataKeyProvider {
    private val lock = Any()

    override fun getKey(): SecretKey {
        synchronized(lock) {
            keyFile.parentFile?.mkdirs()
            if (keyFile.exists() && keyFile.length() == 32L) {
                return SecretKeySpec(keyFile.readBytes(), "AES")
            }
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256, SecureRandom())
            val key = keyGen.generateKey()
            keyFile.writeBytes(key.encoded)
            runCatching {
                Files.setPosixFilePermissions(
                    keyFile.toPath(),
                    PosixFilePermissions.fromString("rw-------")
                )
            }
            return key
        }
    }

    override fun clearKey() {
        if (keyFile.exists()) keyFile.delete()
    }
}

/**
 * Production provider: the data key is generated inside Android Keystore and
 * never leaves it. On first use, entries encrypted under a legacy sidecar
 * key are migrated to the Keystore key and the sidecar file is deleted.
 */
class AndroidKeyStoreKeyProvider(
    private val alias: String,
    private val legacyKeyFile: File,
    private val storageFile: File,
) : McpDataKeyProvider {

    override fun getKey(): SecretKey {
        val key = getOrCreateKeystoreKey()
        migrateCredentialStoreKey(storageFile, legacyKeyFile, key)
        return key
    }

    override fun clearKey() {
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(alias)
        }
        runCatching { legacyKeyFile.delete() }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).run {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}

internal fun aesGcmEncrypt(key: SecretKey, plain: String): Pair<String, String> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, SecureRandom())
    val b64 = java.util.Base64.getEncoder()
    return b64.encodeToString(cipher.iv) to
        b64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
}

internal fun aesGcmDecrypt(key: SecretKey, ivB64: String, valueB64: String): String {
    val b64 = java.util.Base64.getDecoder()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        key,
        GCMParameterSpec(128, b64.decode(ivB64))
    )
    return cipher.doFinal(b64.decode(valueB64)).toString(Charsets.UTF_8)
}

internal fun readCredentialEntries(storageFile: File): JSONObject {
    if (!storageFile.exists()) return JSONObject()
    return runCatching {
        val raw = storageFile.readText()
        if (raw.isBlank()) JSONObject() else JSONObject(raw).optJSONObject("entries") ?: JSONObject()
    }.getOrDefault(JSONObject())
}

internal fun writeCredentialEntries(storageFile: File, entries: JSONObject) {
    SafeFileOps.atomicWriteText(storageFile, JSONObject().put("entries", entries).toString())
}

/**
 * One-time migration: re-encrypts every entry under [newKey] when a legacy
 * sidecar key file exists, then deletes the sidecar key. Entries that fail
 * to decrypt are dropped rather than kept under the old key.
 */
internal fun migrateCredentialStoreKey(
    storageFile: File,
    legacyKeyFile: File,
    newKey: SecretKey,
) {
    if (!legacyKeyFile.exists() || legacyKeyFile.length() != 32L) return
    val legacyKey = SecretKeySpec(legacyKeyFile.readBytes(), "AES")
    val existing = readCredentialEntries(storageFile)
    val migrated = JSONObject()
    for (key in existing.keys()) {
        val entry = existing.optJSONObject(key) ?: continue
        val plain = runCatching {
            aesGcmDecrypt(legacyKey, entry.getString("iv"), entry.getString("value"))
        }.getOrNull() ?: continue
        val (iv, value) = aesGcmEncrypt(newKey, plain)
        migrated.put(key, JSONObject().put("iv", iv).put("value", value))
    }
    writeCredentialEntries(storageFile, migrated)
    legacyKeyFile.delete()
}

/**
 * AES-256/GCM file backend. The data key comes from a [McpDataKeyProvider]:
 * Android Keystore in production, a sidecar key file in unit tests.
 * Values are stored as {"entries": {key: {"iv": b64, "value": b64}}} and
 * never written in plaintext. Pure JVM apart from the key provider, so unit
 * tests can exercise the real encryption path.
 */
class AesGcmFileCredentialBackend(
    storageFile: File,
    private val keyProvider: McpDataKeyProvider,
) : McpCredentialBackend {

    constructor(
        storageFile: File,
        keyFile: File = File(storageFile.parentFile, "mcp_cred.key"),
    ) : this(storageFile, SidecarFileKeyProvider(keyFile))

    private val storage: File = storageFile.apply { parentFile?.mkdirs() }
    private val lock = Any()

    private val dataKey: SecretKey by lazy { keyProvider.getKey() }

    override fun put(key: String, secret: String) {
        synchronized(lock) {
            val entries = readCredentialEntries(storage)
            val (iv, value) = aesGcmEncrypt(dataKey, secret)
            entries.put(key, JSONObject().put("iv", iv).put("value", value))
            writeCredentialEntries(storage, entries)
        }
    }

    override fun get(key: String): String? {
        synchronized(lock) {
            val entry = readCredentialEntries(storage).optJSONObject(key) ?: return null
            return runCatching {
                aesGcmDecrypt(dataKey, entry.getString("iv"), entry.getString("value"))
            }.getOrNull()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            val entries = readCredentialEntries(storage)
            if (entries.has(key)) {
                entries.remove(key)
                writeCredentialEntries(storage, entries)
            }
        }
    }

    override fun removeAllForServer(serverId: String) {
        synchronized(lock) {
            val entries = readCredentialEntries(storage)
            val doomed = entries.keys().asSequence()
                .filter { credentialKeyBelongsToServer(it, serverId) }
                .toList()
            if (doomed.isEmpty()) return
            doomed.forEach { entries.remove(it) }
            writeCredentialEntries(storage, entries)
        }
    }

    override fun clear() {
        synchronized(lock) {
            if (storage.exists()) storage.delete()
            keyProvider.clearKey()
        }
    }
}

/**
 * Secure storage for MCP server secrets (API keys, tokens, connection strings).
 *
 * Secrets are keyed as "<serverId>::<name>" and NEVER written to mcp_servers.json —
 * that file keeps only non-secret configuration. [McpServerInfo.secretEnvNames]
 * declares which env vars resolve from this store at process-start time.
 */
class McpCredentialStore(
    baseDir: File,
    credentialBackend: McpCredentialBackend? = null
) {
    companion object {
        /**
         * Test hook: when set, the default backend uses this key provider
         * instead of Android Keystore (which does not exist on the JVM).
         * Production never sets this — the Keystore path is strict.
         */
        @Volatile
        internal var defaultKeyProviderForTests: McpDataKeyProvider? = null
    }

    private val storeDir: File = baseDir.apply { mkdirs() }
    private val backend: McpCredentialBackend =
        credentialBackend ?: AesGcmFileCredentialBackend(
            File(storeDir, "mcp_credentials.json"),
            defaultKeyProviderForTests ?: AndroidKeyStoreKeyProvider(
                alias = "mcp_credential_key",
                legacyKeyFile = File(storeDir, "mcp_cred.key"),
                storageFile = File(storeDir, "mcp_credentials.json"),
            ),
        )

    fun credentialKey(serverId: String, name: String): String = "$serverId::$name"

    fun setCredential(serverId: String, name: String, secret: String) {
        require(name.isNotBlank()) { "Credential name cannot be blank" }
        require(secret.isNotBlank()) { "Credential value cannot be blank" }
        backend.put(credentialKey(serverId, name), secret)
    }

    fun getCredential(serverId: String, name: String): String? =
        backend.get(credentialKey(serverId, name))

    fun hasCredential(serverId: String, name: String): Boolean =
        getCredential(serverId, name) != null

    fun removeCredential(serverId: String, name: String) =
        backend.remove(credentialKey(serverId, name))

    /**
     * Removes every credential belonging to a server. Called when a server is
     * uninstalled so secrets do not linger after removal.
     */
    fun removeServerCredentials(serverId: String, knownNames: List<String>) {
        knownNames.forEach { removeCredential(serverId, it) }
    }

    fun removeAllServerCredentials(serverId: String) {
        backend.removeAllForServer(serverId)
    }

    fun clear() = backend.clear()
}
