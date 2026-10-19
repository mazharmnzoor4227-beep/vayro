package com.vayro.app

import android.app.Application

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vayro.app.runtime.KeepAliveService
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide bootstrap. Installed before activities/services so fatal Java/Kotlin
 * startup exceptions are persisted before Android terminates the process.
 *
 * Firebase Crashlytics is enabled here so every uncaught exception is uploaded
 * automatically — no manual log collection needed.
 */
class MobileHarnessApplication : Application() {
    companion object {
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        AppCrashLogger.initialize(this)
        reportPreviousDeath()
        runCatching { com.vayro.app.runtime.AnrWatchdog.start() }
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
            val ignored = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
            AppCrashLogger.log("Battery optimization ignored=$ignored")
        }
        runCatching {
            FirebaseApp.initializeApp(this)
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled =
                AppCrashLogger.isCrashReportingEnabled
        }.onFailure {
            AppCrashLogger.logThrowable("Firebase init failed; continuing without Firebase", it)
        }
        // (FCM needs no local token persistence: the PushTokenStore that
        // used to save the registration token here was removed — nothing
        // ever read it and no server ever received it. QA Medium.)
        AppCrashLogger.log("Application.onCreate completed")
        // Keep-alive: when the app goes to the background, start a lightweight
        // foreground service so aggressive OEM task killers do not freeze the
        // process. When the app returns to the foreground the service stops,
        // so no persistent notification shows while the app is in use.
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        markBackgrounded(true)
                        // v3.2.38 (J): "Keep working in background" OFF must
                        // also stop the keep-alive foreground service —
                        // previously it started unconditionally, so OFF
                        // still promoted the app to a foreground service.
                        if (com.vayro.app.data.AppPreferences(this@MobileHarnessApplication).keepWorkingInBackground) {
                            runCatching { KeepAliveService.start(this@MobileHarnessApplication) }
                        }
                    }

                    override fun onStart(owner: LifecycleOwner) {
                        markBackgrounded(false)
                        runCatching { KeepAliveService.stop(this@MobileHarnessApplication) }
                    }
                }
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppCrashLogger.log("onTrimMemory level=$level")
    }

    private fun forensicsPrefs() =
        getSharedPreferences("process_forensics", MODE_PRIVATE)

    private fun reportPreviousDeath() {
        runCatching {
            val prefs = forensicsPrefs()
            val cleanExit = prefs.getBoolean("clean_exit", true)
            val wasBackground = prefs.getBoolean("was_background", false)
            if (!cleanExit) {
                if (wasBackground) {
                    AppCrashLogger.log("PREVIOUS PROCESS DIED IN BACKGROUND (system reclaim, likely normal)")
                } else {
                    AppCrashLogger.log("PREVIOUS PROCESS DIED WHILE FOREGROUND (crash/kill, investigate)")
                }
            }
            prefs.edit().putBoolean("clean_exit", false).apply()
        }
    }

    fun markCleanExit() {
        runCatching {
            forensicsPrefs().edit().putBoolean("clean_exit", true).apply()
        }
    }

    fun markBackgrounded(background: Boolean) {
        runCatching {
            forensicsPrefs().edit().putBoolean("was_background", background).apply()
        }
    }
}
