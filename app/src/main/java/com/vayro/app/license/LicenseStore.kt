package com.vayro.app.license

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID

/**
 * Local storage for the Kodex license state.
 * Only the server-issued token and check bookkeeping live here;
 * the license itself lives on the license server.
 */
class LicenseStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("kodex_license", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

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
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EXPIRES)
            .remove(KEY_TYPE)
            .remove(KEY_LAST_OK)
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
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_TYPE = "lic_type"
        private const val KEY_LAST_OK = "last_ok_at"
        private const val KEY_ACTIVATED_AT = "activated_at"
        private const val KEY_LAST_MODE = "last_mode"
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
