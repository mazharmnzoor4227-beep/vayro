package com.vayro.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vayro.app.MainActivity
import com.vayro.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives Firebase Cloud Messages for this app.
 *
 * - onMessageReceived: shows a system notification for
 *   notification-payload messages AND data-only messages (a data payload
 *   with title/body keys is rendered instead of being dropped).
 *
 * QA Medium: the FCM registration token used to be persisted locally
 * (PushTokenStore) but never sent to any server and never read anywhere.
 * The store is deleted — push delivery does not depend on it, and the
 * token can be fetched on demand via FirebaseMessaging when a backend
 * ever needs it.
 */
class MheMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val notification = message.notification
        // QA Medium: data-only payloads used to be silently dropped.
        val title = notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = notification?.body
            ?: message.data["body"]
            ?: message.data["message"]
            ?: ""
        if (title.isBlank() && body.isBlank()) return
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "mhe_general"
        // QA Medium: the unchecked cast used to crash if the service
        // ever returned something else.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val built = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        // QA Medium: System.currentTimeMillis().toInt() could collide
        // (same millisecond, int truncation) and replace a notification.
        // A process-unique sequence never collides.
        manager.notify(notificationIdSequence.incrementAndGet(), built)
    }

    companion object {
        private val notificationIdSequence = AtomicInteger(1_000_000)
    }
}
