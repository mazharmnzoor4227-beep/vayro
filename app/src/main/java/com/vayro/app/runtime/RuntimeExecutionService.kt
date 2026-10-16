package com.vayro.app.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

internal object RuntimeTaskController {
    private val stopActions = mutableMapOf<String, () -> Unit>()

    @Synchronized
    fun registerStop(sessionId: String, action: () -> Unit) {
        stopActions[sessionId] = action
    }

    @Synchronized
    fun unregisterStop(sessionId: String) {
        stopActions.remove(sessionId)
    }

    @Synchronized
    fun requestStop(sessionId: String? = null) {
        val action = if (sessionId != null) stopActions[sessionId] else stopActions.values.firstOrNull()
        action?.invoke()
    }
}

class RuntimeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var canStop: Boolean = true

    private data class SessionNotification(
        var projectName: String,
        var agentName: String,
        var detail: String,
    )

    private val sessions = mutableMapOf<String, SessionNotification>()

    override fun onCreate() {
        super.onCreate()
        NotificationCoordinator.ensureChannels(this)
    }

    private fun notificationManager(): android.app.NotificationManager =
        getSystemService(android.app.NotificationManager::class.java)

    private fun sessionNotificationId(sessionId: String): Int = sessionId.hashCode()

    private fun refreshForegroundNotification() {
        val summary = if (sessions.size == 1) {
            val info = sessions.values.first()
            NotificationCoordinator.running(
                context = this,
                title = "Vayro is working",
                detail = info.detail,
                canStop = canStop,
                sessionId = sessions.keys.first(),
            )
        } else {
            NotificationCoordinator.running(
                context = this,
                title = "Vayro is working",
                detail = "${sessions.size} tasks running",
                canStop = false,
                sessionId = null,
            )
        }
        startForeground(NotificationCoordinator.RUNNING_NOTIFICATION_ID, summary)
    }

    private fun stopIfIdle() {
        if (sessions.isEmpty()) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            refreshForegroundNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: "legacy"
        val projectName = intent?.getStringExtra(EXTRA_PROJECT_NAME)?.takeIf { it.isNotBlank() }
        val agentName = intent?.getStringExtra(EXTRA_AGENT_NAME)?.takeIf { it.isNotBlank() }
        if (intent?.hasExtra(EXTRA_CAN_STOP) == true) canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                RuntimeTaskController.requestStop(sessionId.takeIf { it != "legacy" })
                if (sessions.containsKey(sessionId)) {
                    notificationManager().notify(
                        sessionNotificationId(sessionId),
                        NotificationCoordinator.running(this, "Vayro is working", "Stopping safely…", false, sessionId),
                    )
                }
            }
            ACTION_PROGRESS -> {
                val info = sessions[sessionId] ?: return START_STICKY
                if (projectName != null) info.projectName = projectName
                if (agentName != null) info.agentName = agentName
                info.detail = intent?.getStringExtra(EXTRA_DETAIL)?.takeIf { it.isNotBlank() }
                    ?: "${info.agentName} is working in ${info.projectName}"
                notificationManager().notify(
                    sessionNotificationId(sessionId),
                    NotificationCoordinator.running(this, "Vayro is working", info.detail, canStop, sessionId),
                )
                refreshForegroundNotification()
            }
            ACTION_COMPLETE -> finishTask(
                sessionId = sessionId,
                title = "Task completed",
                detail = intent?.getStringExtra(EXTRA_DETAIL)
                    ?: "${agentName ?: "Agent"} finished working in ${projectName ?: "your project"}.",
                failed = false,
            )
            ACTION_FAILED -> finishTask(
                sessionId = sessionId,
                title = "Task needs attention",
                detail = intent?.getStringExtra(EXTRA_DETAIL)
                    ?: "${agentName ?: "Agent"} could not finish the task.",
                failed = true,
            )
            ACTION_CANCELLED -> {
                sessions.remove(sessionId)
                notificationManager().cancel(sessionNotificationId(sessionId))
                stopIfIdle()
            }
            else -> {
                val info = SessionNotification(
                    projectName = projectName ?: "your project",
                    agentName = agentName ?: "Coding agent",
                    detail = intent?.getStringExtra(EXTRA_DETAIL)?.takeIf { it.isNotBlank() }
                        ?: "${agentName ?: "Coding agent"} is working in ${projectName ?: "your project"}",
                )
                sessions[sessionId] = info
                notificationManager().notify(
                    sessionNotificationId(sessionId),
                    NotificationCoordinator.running(this, "Vayro is working", info.detail, canStop, sessionId),
                )
                refreshForegroundNotification()
                acquireLocks()
            }
        }
        return START_STICKY
    }

    private fun finishTask(sessionId: String, title: String, detail: String, failed: Boolean) {
        sessions.remove(sessionId)
        notificationManager().cancel(sessionNotificationId(sessionId))
        NotificationCoordinator.postResult(this, title, detail, failed)
        stopIfIdle()
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.vayro.app:active-coding-task")
                .apply { acquire(MAX_WAKE_LOCK_MS) }
        }
        if (wifiLock?.isHeld != true) {
            runCatching {
                val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val wifiLockMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(
                    wifiLockMode,
                    "com.vayro.app:active-coding-wifi",
                )?.apply { acquire() }
                // WifiLock has no acquire(timeout): emulate the wake lock's
                // cap so a missed completion path can't hold high-perf wifi
                // forever. The delayed release is a no-op if the lock was
                // already released normally.
                wifiLockHandler.removeCallbacks(wifiLockTimeoutRelease)
                wifiLockHandler.postDelayed(wifiLockTimeoutRelease, MAX_WAKE_LOCK_MS)
            }
        }
    }

    private val wifiLockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wifiLockTimeoutRelease = Runnable {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLockHandler.removeCallbacks(wifiLockTimeoutRelease)
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.vayro.app.START_RUNTIME"
        const val ACTION_STOP = "com.vayro.app.STOP_RUNTIME"
        const val ACTION_PROGRESS = "com.vayro.app.PROGRESS_RUNTIME"
        const val ACTION_COMPLETE = "com.vayro.app.COMPLETE_RUNTIME"
        const val ACTION_FAILED = "com.vayro.app.FAIL_RUNTIME"
        const val ACTION_CANCELLED = "com.vayro.app.CANCEL_RUNTIME"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_AGENT_NAME = "agent_name"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CAN_STOP = "can_stop"
        const val EXTRA_SESSION_ID = "session_id"

        private const val MAX_WAKE_LOCK_MS = 90 * 60 * 1_000L
    }
}
