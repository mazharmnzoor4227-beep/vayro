package com.vayro.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vayro.app.R

/**
 * Lightweight keep-alive foreground service that survives aggressive OEM
 * background killing (e.g. OnePlus) when the app sits idle in the background.
 *
 * Lifecycle is driven automatically by the Application class via
 * ProcessLifecycleOwner: started when the app goes to background (ON_STOP),
 * stopped when it returns to foreground (ON_START), so the persistent
 * notification only shows while the app is actually in the background.
 *
 * QA H30 bounds: the wake lock is capped at [WAKE_LOCK_TIMEOUT_MS]
 * (previously indefinite — battery drain), and the service never
 * resurrects itself: no restart alarm on task removal, and
 * START_NOT_STICKY, so a user swipe-away or a system kill STAYS dead
 * until the app backgrounds again. The foreground service itself is
 * best-effort keep-alive, not a guarantee against OEM killers.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // QA H30: NOT_STICKY — a system kill or a user swipe-away does
        // not resurrect this service. It restarts only when the app
        // backgrounds again (Application ON_STOP).
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // QA H30: no onTaskRemoved restart. The old scheduleRestart() alarm
    // brought the service back 2s after the user swiped the app away —
    // the user's explicit "stop" must be respected.

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Vayro alive while it is in the background"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Vayro")
            .setContentText("Running in background")
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // QA H30: bounded wake lock. The old indefinite acquire()
            // held the CPU awake for the whole background stint (battery
            // drain) and was released only in onDestroy(). Ten minutes
            // gives a background agent task a grace window; after that
            // Doze may pause background work while the foreground
            // service itself still keeps the process alive.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "keep-alive"
        const val NOTIFICATION_ID = 4401
        const val WAKE_LOCK_TAG = "com.vayro.app:keep-alive"
        // QA H30: the partial wake lock auto-releases after 10 minutes.
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
