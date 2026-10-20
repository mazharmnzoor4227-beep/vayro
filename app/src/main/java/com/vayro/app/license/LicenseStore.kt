package com.vayro.app.license

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local storage for the Kodex license state.
 * Only the server-issued token and check bookkeeping live here;
 * the license itself lives on the license server.
 *
 * The token itself is a credential, so it is NOT kept in these plaintext
 * preferences: it sits in a separate store encrypted with AES-256-GCM
 * under a key that never leaves the Android Keystore (the same pattern
 * as the provider-key vault — audit L3). A token written by an older
 * version in plaintext is migrated into the encrypted store on first
 * read and the plaintext copy wiped.
 */
class LicenseStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("kodex_license", Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences =
        context.getSharedPreferences("kodex_license_secure", Context.MODE_PRIVATE)

    var token: String?
        get() {
            readSecureToken()?.let { return it }
            val legacy = prefs.getString(KEY_TOKEN, null) ?: return null
            // Migrate-on-read: move the legacy plaintext token into the
            // encrypted store and wipe the plaintext copy. If the
            // Keystore is somehow unavailable, keep serving the legacy
            // value rather than locking a paying customer out; the
            // migration is retried on the next read.
            if (writeSecureToken(legacy)) {
                prefs.edit().remove(KEY_TOKEN).apply()
            }
            return legacy
        }
        set(value) {
            if (value == null) {
                clearSecureToken()
                prefs.edit().remove(KEY_TOKEN).apply()
            } else if (writeSecureToken(value)) {
                prefs.edit().remove(KEY_TOKEN).apply()
            } else {
                // Keystore unavailable: fall back to the legacy slot so
                // activation still works on this phone.
                prefs.edit().putString(KEY_TOKEN, value).apply()
            }
        }

    private fun readSecureToken(): String? {
        val iv = securePrefs.getString(KEY_TOKEN_IV, null) ?: return null
        val value = securePrefs.getString(KEY_TOKEN_VALUE, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun writeSecureToken(value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        securePrefs.edit()
            .putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKEN_VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    private fun clearSecureToken() {
        securePrefs.edit().remove(KEY_TOKEN_IV).remove(KEY_TOKEN_VALUE).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES, value).apply()

    var licenseType: String?
        get() = prefs.getString(KEY_TYPE, null)
        set(value) = prefs.edit().putString(KEY_TYPE, value).apply()

    /** Last time the server confirmed this license is active (activation or check). */
    var lastOkAt: Long
        get() = prefs.getLong(KEY_LAST_OK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_OK, value).apply()

    /** Monotonic anchor stored next to [lastOkAt]: the
     *  `SystemClock.elapsedRealtime()` reading at that same moment.
     *  The offline grace window is judged by BOTH clocks and the smaller
     *  remaining window wins (audit M2), so setting the phone clock back
     *  cannot stretch a timed license. 0 = no anchor stored yet. */
    var lastOkElapsed: Long
        get() = prefs.getLong(KEY_LAST_OK_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_OK_ELAPSED, value).apply()

    /** True once any successful license-server contact has resolved the
     *  enforcement mode on this install (a fetched mode, an activation,
     *  or a successful check). Until then the stored [lastMode] is only
     *  the factory default and must not open the app on its own (H1b). */
    var modeConfirmed: Boolean
        get() = prefs.getBoolean(KEY_MODE_CONFIRMED, false)
        set(value) = prefs.edit().putBoolean(KEY_MODE_CONFIRMED, value).apply()

    /** When this phone last activated successfully. Used for a short settle
     *  window right after activation, while server writes propagate. */
    var activatedAt: Long
        get() = prefs.getLong(KEY_ACTIVATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_ACTIVATED_AT, value).apply()

    /** Last enforcement mode seen from the server ("on"/"off"); used when offline. */
    var lastMode: String
        get() = prefs.getString(KEY_LAST_MODE, "off") ?: "off"
        set(value) = prefs.edit().putString(KEY_LAST_MODE, value).apply()

    fun clearLicense() {
        clearSecureToken()
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EXPIRES)
            .remove(KEY_TYPE)
            .remove(KEY_LAST_OK)
            .remove(KEY_LAST_OK_ELAPSED)
            .remove(KEY_ACTIVATED_AT)
            .apply()
    }

    fun installUuid(): String {
        var id = prefs.getString(KEY_INSTALL_UUID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_UUID, id).apply()
        }
        return id
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_TOKEN_IV = "token.iv"
        private const val KEY_TOKEN_VALUE = "token.value"
        private const val KEY_ALIAS = "kodex-license-key"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_TYPE = "lic_type"
        private const val KEY_LAST_OK = "last_ok_at"
        private const val KEY_LAST_OK_ELAPSED = "last_ok_elapsed"
        private const val KEY_ACTIVATED_AT = "activated_at"
        private const val KEY_LAST_MODE = "last_mode"
        private const val KEY_MODE_CONFIRMED = "mode_confirmed"
        private const val KEY_INSTALL_UUID = "install_uuid"

        const val GRACE_MS = 7L * 24 * 3600 * 1000
    }
}

/** Stable per-phone identity used for license binding. Only its hash leaves the phone. */
object DeviceId {
    fun hash(context: Context): String {
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull()
        val base = if (!androidId.isNullOrBlank()) {
            "kodex|$androidId"
        } else {
            // Fallback for the rare devices without an ANDROID_ID: per-install id.
            "kodex-install|" + LicenseStore(context).installUuid()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(base.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
