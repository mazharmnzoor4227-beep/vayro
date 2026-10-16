package com.vayro.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

const val GEMINI_API_DEFAULT_MODEL = "gemini-4-argon"

val GEMINI_API_MODELS: List<Pair<String, String>> = listOf(
    "gemini-4-argon" to "Gemini 4 Argon",
    "gemini-3.8-flash" to "Gemini 3.8 Flash",
    "gemini-3.1-pro" to "Gemini 3.1 Pro",
)

class GeminiApiStore(context: Context) {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "gemini_api_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiModeEnabled: Boolean
        get() = runCatching { prefs.getBoolean(KEY_MODE, false) }.getOrDefault(false)
        set(value) { runCatching { prefs.edit().putBoolean(KEY_MODE, value).apply() } }

    fun getApiKey(): String? =
        runCatching { prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } }.getOrNull()

    fun setApiKey(key: String) {
        if (key.isBlank()) return
        runCatching { prefs.edit().putString(KEY_API_KEY, key.trim()).apply() }
    }

    fun clearApiKey() {
        runCatching { prefs.edit().remove(KEY_API_KEY).apply() }
    }

    fun hasApiKey(): Boolean = getApiKey() != null

    var apiModel: String
        get() = runCatching {
            prefs.getString(KEY_MODEL, GEMINI_API_DEFAULT_MODEL) ?: GEMINI_API_DEFAULT_MODEL
        }.getOrDefault(GEMINI_API_DEFAULT_MODEL)
        set(value) {
            if (GEMINI_API_MODELS.none { it.first == value }) return
            runCatching { prefs.edit().putString(KEY_MODEL, value).apply() }
        }

    private companion object {
        const val KEY_MODE = "gemini_api_mode_enabled"
        const val KEY_API_KEY = "gemini_api_key"
        const val KEY_MODEL = "gemini_api_model"
    }
}
