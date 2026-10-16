package com.vayro.app.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.vayro.app.files.SharedExport
import java.io.File

/** Installs a locally-built APK through Android's package manager, without ADB. */
object AndroidAppInstaller {
    fun install(context: Context, apk: File) {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true) && apk.length() > 0L) {
            "A valid APK was not produced: ${apk.name}"
        }
        if (isCustomOemDevice() || !canUsePackageInstallerSession(context)) {
            launchInstallerIntent(context, apk)
            return
        }
        try {
            installViaPackageInstaller(context, apk)
        } catch (_: Throwable) {
            launchInstallerIntent(context, apk)
        }
    }

    fun launchInstallerIntent(context: Context, apk: File) {
        val staged = SharedExport.stageApkForInstall(context.filesDir, apk)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", staged)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun installViaPackageInstaller(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setSize(apk.length())
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, AndroidAppInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                // The status fill-in extras are supplied by the system at
                // send time, which works with an immutable PendingIntent.
                val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    const val ACTION_INSTALL_RESULT = "com.vayro.app.action.APK_INSTALL_RESULT"

    private fun canUsePackageInstallerSession(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        }
        return true
    }

    private fun isCustomOemDevice(): Boolean = Build.MANUFACTURER.lowercase() in
        setOf("xiaomi", "redmi", "poco", "oppo", "vivo", "realme", "oneplus", "transsion", "infinix", "tecno")
}

/**
 * Deletes downloaded self-update APKs from [updatesDir]. Only .apk files
 * are removed; partials and other files are left alone. Kept top-level
 * and pure so it stays unit-testable (the receiver needs a Context).
 */
internal fun clearDownloadedUpdateApks(updatesDir: File) {
    updatesDir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
        .forEach { runCatching { it.delete() } }
}
