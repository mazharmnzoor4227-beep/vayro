package com.vayro.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vayro.app.BuildConfig
import com.vayro.app.MainActivity
import com.vayro.app.R
import com.vayro.app.runtime.EnvironmentManifestComponent
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
 * "Update available for X — tap to install".
 *
 * When the user turns ON "Auto-update components" in Runtime resources,
 * the same check also installs the updates in the background through the
 * existing EnvironmentUpdateManager flow (backup -> install -> health
 * check -> auto-restore on failure) and posts a notification when done.
 * Agents (Claude/DeepSeek/Antigravity) are never touched here — they
 * update from the Agents screen. Checking is silent on any failure and
 * throttled to once per 6 hours, like [UpdateNotifier].
 */
object ComponentUpdateNotifier {
    const val PREFS_NAME = "kodex_component_update_notify"
    const val KEY_AUTO_UPDATE = "auto_update"

    private const val KEY_CHECKED_AT = "checked_at"
    private const val THROTTLE_MS = 6L * 60L * 60L * 1000L
    private const val CHANNEL_ID = "component-updates"
    private const val NOTIFICATION_ID = 78

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
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED_AT, 0L) < THROTTLE_MS) return
        prefs.edit().putLong(KEY_CHECKED_AT, now).apply()
        runCatching {
            val pending = withContext(Dispatchers.IO) { findPendingUpdates(appContext) }
            if (pending.isEmpty()) return
            val autoUpdate = prefs.getBoolean(KEY_AUTO_UPDATE, false)
            if (!autoUpdate) {
                if (!dismissed) updatesAvailable = pending.map { it.label }
                return
            }
            val (done, failed) = withContext(Dispatchers.IO) {
                installPendingUpdates(appContext, pending)
            }
            if (!dismissed) updatesAvailable = failed.map { it.label }
            if (done.isNotEmpty()) postUpdatedNotification(appContext, done)
        }
    }

    private data class PendingUpdate(
        val component: EnvironmentManifestComponent,
        val label: String,
        val latestVersion: String,
    )

    private suspend fun findPendingUpdates(context: Context): List<PendingUpdate> {
        val manager = EnvironmentUpdateManager(context, RuntimeInstaller(context))
        val manifest = loadManifest(context, manager)
        val live = runCatching { manager.refreshManifest(manifest) }.getOrDefault(manifest)
        val components = live.filterNot { it.id in AGENT_IDS }
        val states = manager.inspect(components)
        return components.mapNotNull { component ->
            val state = states.firstOrNull { it.id == component.id } ?: return@mapNotNull null
            val current = state.currentVersion
            val latest = state.latestVersion
            if (current != null && latest != null && latest != current) {
                PendingUpdate(component, component.label, latest)
            } else {
                null
            }
        }
    }

    private suspend fun loadManifest(
        context: Context,
        manager: EnvironmentUpdateManager,
    ): List<EnvironmentManifestComponent> {
        val remoteBody = fetchRemoteManifest()
        val parsed = remoteBody?.let { manager.parseManifest(it).takeIf { list -> list.isNotEmpty() } }
        if (parsed != null) return parsed
        val assetJson = runCatching {
            context.assets.open("runtime/manifest.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        return if (assetJson != null) manager.parseManifest(assetJson) else manager.defaultComponents()
    }

    /**
     * Installs through the same manager the Settings screen uses, so the
     * backup / health-check / auto-restore behaviour is identical.
     */
    private suspend fun installPendingUpdates(
        context: Context,
        pending: List<PendingUpdate>,
    ): Pair<List<String>, List<PendingUpdate>> {
        val manager = EnvironmentUpdateManager(context, RuntimeInstaller(context))
        val done = mutableListOf<String>()
        val failed = mutableListOf<PendingUpdate>()
        for (update in pending) {
            val ok = runCatching { manager.update(update.component) {} }.isSuccess
            if (ok) {
                done += "${update.label} updated to ${update.latestVersion}"
            } else {
                failed += update
            }
        }
        return done to failed
    }

    private fun postUpdatedNotification(context: Context, done: List<String>) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Component updates", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = done.joinToString("\n")
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Kodex components updated")
                .setContentText(done.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
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
