package com.vayro.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyVault(context: Context) {
    private val preferences = context.getSharedPreferences("pocket_secrets", Context.MODE_PRIVATE)
    private val alias = "pocket-provider-key"

    @Synchronized
    fun put(providerId: String, secret: String) {
        if (secret.isBlank()) return
        val entries = ensurePool(providerId)
        val active = entries.firstOrNull { it.id == activeId(providerId) } ?: entries.firstOrNull()
        if (active == null) {
            add(providerId, "Primary", secret)
        } else {
            putEncrypted(secretKey(providerId, active.id), secret)
        }
    }

    @Synchronized
    fun add(providerId: String, name: String, secret: String): ApiKeyInfo {
        require(secret.isNotBlank()) { "API key cannot be empty" }
        val entries = ensurePool(providerId).toMutableList()
        val entry = ApiKeyInfo(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "API key ${entries.size + 1}" }.take(60),
            isActive = entries.isEmpty(),
        )
        putEncrypted(secretKey(providerId, entry.id), secret)
        entries += entry.copy(isActive = false)
        savePool(providerId, entries)
        if (entries.size == 1) setActiveId(providerId, entry.id)
        return entry.copy(isActive = entries.size == 1)
    }

    @Synchronized
    fun list(providerId: String): List<ApiKeyInfo> {
        val entries = ensurePool(providerId)
        val active = activeId(providerId) ?: entries.firstOrNull()?.id
        return entries.map { it.copy(isActive = it.id == active) }
    }

    @Synchronized
    fun credentials(providerId: String): List<ApiKeyCredential> = list(providerId).mapNotNull { info ->
        getEncrypted(secretKey(providerId, info.id))?.let { secret ->
            ApiKeyCredential(info.id, info.name, secret, info.isActive)
        }
    }.sortedByDescending(ApiKeyCredential::isActive)

    @Synchronized
    fun activate(providerId: String, keyId: String): Boolean {
        if (ensurePool(providerId).none { it.id == keyId }) return false
        setActiveId(providerId, keyId)
        return true
    }

    @Synchronized
    fun remove(providerId: String, keyId: String) {
        val remaining = ensurePool(providerId).filterNot { it.id == keyId }
        removeEncrypted(secretKey(providerId, keyId))
        savePool(providerId, remaining)
        if (activeId(providerId) == keyId) {
            preferences.edit().remove(activeKey(providerId)).commit()
            remaining.firstOrNull()?.let { setActiveId(providerId, it.id) }
        }
    }

    private fun putEncrypted(storageId: String, secret: String) {
        // A key invalidated by a lock-screen/biometric change throws when it is
        // *used*, not when it is loaded, so a single retry after dropping the
        // dead key is what turns a permanent save crash-loop into a fresh save.
        // Previously stored secrets under an invalidated key are unrecoverable
        // by definition; the retry lets the user save a new key instead of
        // force-stopping on every Continue tap (issues #6, #7).
        var attempt = 0
        while (true) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
                preferences.edit()
                    .putString("$storageId.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .putString("$storageId.value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit()
                return
            } catch (error: Exception) {
                if (attempt == 0 && shouldRecreateKeystoreKey(error)) {
                    runCatching {
                        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
                    }
                    attempt++
                } else {
                    throw error
                }
            }
        }
    }

    fun contains(providerId: String): Boolean = get(providerId) != null

    @Synchronized
    fun remove(providerId: String) {
        ensurePool(providerId).forEach { removeEncrypted(secretKey(providerId, it.id)) }
        preferences.edit()
            .remove("$providerId.iv")
            .remove("$providerId.value")
            .remove(poolKey(providerId))
            .remove(activeKey(providerId))
            .commit()
    }

    @Synchronized
    fun get(providerId: String): String? {
        val active = list(providerId).firstOrNull { it.isActive } ?: return null
        return getEncrypted(secretKey(providerId, active.id))
    }

    private fun getEncrypted(storageId: String): String? = runCatching {
        val iv = Base64.decode(preferences.getString("$storageId.iv", null), Base64.NO_WRAP)
        val encrypted = Base64.decode(preferences.getString("$storageId.value", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun removeEncrypted(storageId: String) {
        preferences.edit().remove("$storageId.iv").remove("$storageId.value").commit()
    }

    private fun ensurePool(providerId: String): List<ApiKeyInfo> {
        readPool(providerId).takeIf(List<ApiKeyInfo>::isNotEmpty)?.let { return it }
        if (!preferences.contains("$providerId.value")) return emptyList()
        val legacySecret = getEncrypted(providerId) ?: return emptyList()
        val legacy = ApiKeyInfo("legacy", "Primary", true)
        putEncrypted(secretKey(providerId, legacy.id), legacySecret)
        savePool(providerId, listOf(legacy))
        setActiveId(providerId, legacy.id)
        return listOf(legacy)
    }

    private fun readPool(providerId: String): List<ApiKeyInfo> = runCatching {
        val array = JSONArray(preferences.getString(poolKey(providerId), "[]"))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val id = item.optString("id")
                if (id.isBlank()) null else ApiKeyInfo(id, item.optString("name").ifBlank { "API key" })
            }
        }
    }.getOrDefault(emptyList())

    private fun savePool(providerId: String, entries: List<ApiKeyInfo>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        preferences.edit().putString(poolKey(providerId), array.toString()).commit()
    }

    private fun activeId(providerId: String): String? = preferences.getString(activeKey(providerId), null)
    private fun setActiveId(providerId: String, id: String) = preferences.edit().putString(activeKey(providerId), id).commit()
    private fun poolKey(providerId: String) = "$providerId.pool"
    private fun activeKey(providerId: String) = "$providerId.active"
    private fun secretKey(providerId: String, id: String) = "$providerId.pool.$id"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        } catch (error: Exception) {
            // An invalidated key must be dropped so a fresh one can be created;
            // any other keystore failure still propagates to the caller.
            if (shouldRecreateKeystoreKey(error)) {
                runCatching { keyStore.deleteEntry(alias) }
            } else {
                throw error
            }
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}

data class ApiKeyInfo(val id: String, val name: String, val isActive: Boolean = false)

data class ApiKeyCredential(val id: String, val name: String, val secret: String, val isActive: Boolean)

/**
 * True when a keystore failure means the stored key itself is unusable and must
 * be replaced instead of failing every save. The Android invalidated-key
 * exception is matched by class name so this stays loadable on the JVM (unit
 * tests), where android.security.keystore does not exist.
 */
internal fun shouldRecreateKeystoreKey(error: Throwable): Boolean =
    error is java.security.UnrecoverableKeyException ||
        error.javaClass.name == "android.security.keystore.KeyPermanentlyInvalidatedException"
