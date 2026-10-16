package com.vayro.app.core.resources

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Android implementation of [ResourceProbe] using ActivityManager and StatFs.
 *
 * Honesty rule: if a measurement fails (e.g., permission or API issue), the
 * probe fails OPEN for device checks (returns [Long.MAX_VALUE] so startup is
 * never blocked by a broken probe) and returns 0 for project stats. A probe
 * that cannot measure must never fabricate a violation.
 */
class AndroidResourceProbe(private val context: Context) : ResourceProbe {

    override fun freeStorageMb(): Long = runCatching {
        val stat = StatFs(context.filesDir.path)
        stat.availableBytes / (1024L * 1024L)
    }.getOrDefault(Long.MAX_VALUE)

    override fun availableRamMb(): Long = runCatching {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryInfo.availMem / (1024L * 1024L)
    }.getOrDefault(Long.MAX_VALUE)

    override fun projectFileCount(projectRoot: File): Long = runCatching {
        projectRoot.walkTopDown().count { it.isFile }.toLong()
    }.getOrDefault(0L)

    override fun projectBytes(projectRoot: File): Long = runCatching {
        projectRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)
}
