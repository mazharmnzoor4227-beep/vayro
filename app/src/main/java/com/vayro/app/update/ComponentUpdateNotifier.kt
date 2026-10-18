package com.vayro.app.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayro.app.BuildConfig
import com.vayro.app.runtime.EnvironmentUpdateManager
import com.vayro.app.runtime.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kodex: throttled runtime-component update check on app start.
 *
 * Mirrors the Settings -> Runtime resources catalog flow (remote update
 * manifest, bundled asset fallback, built-in defaults, live upstream
 * versions) and exposes the labels of installed components that have a
 * newer version, so the shell can show an in-app notice:
 * "Update available for X — tap to install". Checking is silent on any
 * failure and throttled to once per 6 hours, like [UpdateNotifier].
 */
object ComponentUpdateNotifier {
    private const val PREFS = "kodex_component_update_notify"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val THROTTLE_MS = 6L * 60L * 60L * 1000L

    // Agents are managed from the Agents screen, not Runtime resources.
    private val AGENT_IDS = setOf("claude", "deepseek", "antigravity")

    /** Labels of installed components with a newer version available. */
    var updatesAvailable by mutableStateOf<List<String>>(emptyList())
        private set

    @Volatile
    private var dismissed = false

    fun dismiss() {
        dismissed = true
        updatesAvailable = emptyList()
    }

    suspend fun check(context: Context) {
        if (dismissed || updatesAvailable.isNotEmpty()) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED_AT, 0L) < THROTTLE_MS) return
        prefs.edit().putLong(KEY_CHECKED_AT, now).apply()
        runCatching {
            val labels = withContext(Dispatchers.IO) { findUpdates(appContext) }
            if (labels.isNotEmpty() && !dismissed) updatesAvailable = labels
        }
    }

    private suspend fun findUpdates(context: Context): List<String> {
        val manager = EnvironmentUpdateManager(context, RuntimeInstaller(context))
        val remoteBody = fetchRemoteManifest()
        val parsed = remoteBody?.let { manager.parseManifest(it).takeIf { list -> list.isNotEmpty() } }
        val manifest = parsed ?: run {
            val assetJson = runCatching {
                context.assets.open("runtime/manifest.json").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (assetJson != null) manager.parseManifest(assetJson) else manager.defaultComponents()
        }
        val live = runCatching { manager.refreshManifest(manifest) }.getOrDefault(manifest)
        val components = live.filterNot { it.id in AGENT_IDS }
        val states = manager.inspect(components)
        return components.mapNotNull { component ->
            val state = states.firstOrNull { it.id == component.id } ?: return@mapNotNull null
            val current = state.currentVersion
            val latest = state.latestVersion
            if (current != null && latest != null && latest != current) component.label else null
        }
    }

    private fun fetchRemoteManifest(): String? {
        val urlString = BuildConfig.APP_UPDATE_MANIFEST_URL
        if (urlString.isBlank() || !urlString.startsWith("https://")) return null
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Kodex/" + BuildConfig.VERSION_NAME)
            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
            connection.disconnect()
            text?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
