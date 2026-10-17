package com.vayro.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vayro.app.MainActivity
import com.vayro.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kodex update notifications: when a newer release is published, the app
 * finds it on its own and tells the user with a notification. The check
 * runs at app start, throttled to once every 6 hours, and each version is
 * announced only once. Tapping the notification opens Kodex, where the
 * update can be installed from Settings -> App updates.
 */
object UpdateNotifier {
    private const val CHANNEL_ID = "app-updates"
    private const val NOTIFICATION_ID = 77
    private const val PREFS = "kodex_update_notify"
    private const val KEY_NOTIFIED = "notified_code"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val THROTTLE_MS = 6L * 60L * 60L * 1000L

    suspend fun checkAndNotify(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED_AT, 0L) < THROTTLE_MS) return
        val result = withContext(Dispatchers.IO) {
            runCatching { AppUpdater(appContext).check() }.getOrNull()
        } ?: return
        prefs.edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
        val info = (result as? AppUpdater.AppUpdateCheckResult.Available)?.info ?: return
        if (info.versionCode <= prefs.getLong(KEY_NOTIFIED, 0L)) return
        post(appContext, info)
        prefs.edit().putLong(KEY_NOTIFIED, info.versionCode).apply()
    }

    private fun post(context: Context, info: AppUpdateInfo) {
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            channel.description = "Tells you when a new Kodex version is ready"
            manager.createNotificationChannel(channel)
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
        val text = "Version ${info.versionName} is ready. Tap to open Kodex and install the update."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Kodex update available")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
