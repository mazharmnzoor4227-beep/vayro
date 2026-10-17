package com.vayro.app.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.vayro.app.AppCrashLogger

object BackgroundProtectionHelper {

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        val packageName = context.packageName
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    fun getAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Checks if the device manufacturer is known for aggressive background process killing
     * (Xiaomi, Oppo, Realme, OnePlus, Vivo, Huawei, Samsung).
     */
    fun isOemDeviceWithAggressiveKiller(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco") ||
            manufacturer.contains("oppo") ||
            manufacturer.contains("realme") ||
            manufacturer.contains("oneplus") ||
            manufacturer.contains("vivo") ||
            manufacturer.contains("iqoo") ||
            manufacturer.contains("samsung") ||
            manufacturer.contains("huawei") ||
            manufacturer.contains("honor")
    }

    fun getOemName(): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "Xiaomi (HyperOS/MIUI)"
            m.contains("samsung") -> "Samsung (OneUI)"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "Oppo/Realme/OnePlus"
            m.contains("vivo") || m.contains("iqoo") -> "Vivo/iQOO"
            m.contains("huawei") || m.contains("honor") -> "Huawei/Honor"
            else -> Build.MANUFACTURER
        }
    }

    /**
     * Attempts to open OEM-specific Autostart or Background Power Management settings.
     * Returns true if a valid OEM intent was successfully launched.
     */
    fun openOemBackgroundSettings(context: Context): Boolean {
        val intents = getOemIntents(context)
        for (intent in intents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    AppCrashLogger.log("Opened OEM background settings: ${intent.component?.className ?: intent.action}")
                    return true
                }
            } catch (t: Throwable) {
                AppCrashLogger.log("Failed to launch OEM intent: ${t.message}")
            }
        }
        // Fallback to application details settings
        return try {
            val fallback = getAppDetailsSettingsIntent(context).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun getOemIntents(context: Context): List<Intent> {
        val packageName = context.packageName
        return listOf(
            // Xiaomi / MIUI / HyperOS AutoStart
            Intent().apply {
                component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            },
            // Xiaomi Battery Saver No-Restrictions
            Intent().apply {
                component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                putExtra("package_name", packageName)
                putExtra("package_label", "Kodex")
            },
            // Oppo / Realme / OnePlus ColorOS Startup
            Intent().apply {
                component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            },
            Intent().apply {
                component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            },
            Intent().apply {
                component = ComponentName("com.oplus.battery", "com.oplus.battery.AppListActivity")
            },
            // Vivo / iQOO Background Management
            Intent().apply {
                component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            },
            Intent().apply {
                component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            },
            Intent().apply {
                component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            },
            // Samsung Device Care / Never Sleeping Apps
            Intent().apply {
                component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            },
            Intent().apply {
                component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            },
            // Huawei / Honor
            Intent().apply {
                component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            },
            Intent().apply {
                component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            },
        )
    }
}
