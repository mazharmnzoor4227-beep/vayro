package com.vayro.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayro.app.MainActivity
import com.vayro.app.R

/**
 * Single Android notification policy for Vayro.
 *
 * Runtime events originate locally from the Android/Linux execution stack, so
 * system notifications do not depend on Firebase or a network connection.
 * FCM can be layered on later for genuinely remote events.
 */
object NotificationCoordinator {
    const val RUNNING_CHANNEL_ID = "runtime"
    const val RESULT_CHANNEL_ID = "task-results"
    const val REMOTE_CHANNEL_ID = "remote-events"
    const val RUNNING_NOTIFICATION_ID = 41
    const val RESULT_NOTIFICATION_ID = 42
    private const val REMOTE_NOTIFICATION_BASE_ID = 10_000

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RUNNING_CHANNEL_ID,
                "Running coding tasks",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while Vayro is working in the background"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                "Task results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies you when a coding task finishes or needs attention"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                REMOTE_CHANNEL_ID,
                "Vayro updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Remote updates for Vayro projects and agent sessions"
            },
        )
    }

    fun running(
        context: Context,
        title: String,
        detail: String,
        canStop: Boolean,
        sessionId: String? = null,
    ): Notification {
        val builder = NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openAppIntent(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (canStop) {
            val stopIntent = PendingIntent.getService(
                context,
                sessionId?.hashCode() ?: 2,
                Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_STOP)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop task", stopIntent)
        }
        return builder.build()
    }

    fun result(
        context: Context,
        title: String,
        detail: String,
        failed: Boolean,
    ): Notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(detail)
        .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        .setContentIntent(openAppIntent(context))
        .setAutoCancel(true)
        .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    fun postRemote(
        context: Context,
        title: String,
        detail: String,
        messageId: String?,
        route: String?,
    ) {
        ensureChannels(context)
        val notificationId = REMOTE_NOTIFICATION_BASE_ID + stableNotificationOffset(messageId, title, detail)
        context.getSystemService(NotificationManager::class.java).notify(
            notificationId,
            NotificationCompat.Builder(context, REMOTE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent(context, route))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    fun postResult(context: Context, title: String, detail: String, failed: Boolean) {
        ensureChannels(context)
        context.getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            result(context, title, detail, failed),
        )
    }

    private fun openAppIntent(context: Context, route: String? = null): PendingIntent = PendingIntent.getActivity(
        context,
        route?.hashCode() ?: 1,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_ROUTE, it) }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stableNotificationOffset(messageId: String?, title: String, detail: String): Int =
        (messageId ?: "$title\u0000$detail").hashCode().and(0x0FFF)

    private const val EXTRA_ROUTE = "notification_route"
}