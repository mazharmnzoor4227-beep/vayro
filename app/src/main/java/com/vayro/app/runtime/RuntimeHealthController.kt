package com.vayro.app.runtime

import android.content.Context
import com.vayro.app.AppCrashLogger
import java.io.File

sealed interface StorageHealthResult {
    data class Healthy(val usableBytes: Long, val totalBytes: Long) : StorageHealthResult
    data class Warning(val usableBytes: Long, val totalBytes: Long, val message: String) : StorageHealthResult
    data class Critical(val usableBytes: Long, val totalBytes: Long, val message: String) : StorageHealthResult
}

sealed interface RootfsIntegrityResult {
    data object Intact : RootfsIntegrityResult
    data object NotInstalled : RootfsIntegrityResult
    data class Corrupted(val reason: String, val missingCriticalFiles: List<String>) : RootfsIntegrityResult
}

/**
 * Monitors and enforces runtime environment integrity, low-storage protections,
 * and orphan process cleanup.
 */
class RuntimeHealthController(
    private val baseFilesDir: File,
    private val storageProvider: (() -> Pair<Long, Long>)? = null,
) {

    constructor(context: Context) : this(context.filesDir)

    companion object {
        const val MIN_SAFE_STORAGE_BYTES: Long = 500L * 1024L * 1024L // 500 MB
        const val MIN_BUILD_STORAGE_BYTES: Long = 1024L * 1024L * 1024L // 1 GB
        const val WARNING_STORAGE_BYTES: Long = 1500L * 1024L * 1024L // 1.5 GB
    }

    private val runtimeDir: File
        get() = File(baseFilesDir, "runtime/ubuntu")

    /**
     * Inspects available storage on the app private partition to protect
     * against corrupted installs and failed builds.
     */
    fun checkStorageHealth(requiredBytes: Long = MIN_SAFE_STORAGE_BYTES): StorageHealthResult {
        val (usable, total) = storageProvider?.invoke() ?: (baseFilesDir.usableSpace to baseFilesDir.totalSpace)

        return when {
            usable < requiredBytes -> {
                val usableMb = usable / (1024 * 1024)
                val requiredMb = requiredBytes / (1024 * 1024)
                StorageHealthResult.Critical(
                    usableBytes = usable,
                    totalBytes = total,
                    message = "Critically low storage: ${usableMb}MB available. At least ${requiredMb}MB required.",
                )
            }
            usable < WARNING_STORAGE_BYTES -> {
                val usableMb = usable / (1024 * 1024)
                StorageHealthResult.Warning(
                    usableBytes = usable,
                    totalBytes = total,
                    message = "Storage is running low: ${usableMb}MB remaining.",
                )
            }
            else -> StorageHealthResult.Healthy(usable, total)
        }
    }

    /**
     * Verifies rootfs directory and critical binaries to detect corruption
     * caused by OS kills during initial rootfs extraction.
     */
    fun verifyRootfsIntegrity(): RootfsIntegrityResult {
        if (!runtimeDir.exists() || !runtimeDir.isDirectory) {
            return RootfsIntegrityResult.NotInstalled
        }

        val criticalFiles = listOf(
            "bin/sh",
            "bin/bash",
            "etc/passwd",
            "usr/bin/env",
        )

        val missing = criticalFiles.filter { relPath ->
            val file = File(runtimeDir, relPath)
            !file.exists() || file.length() == 0L
        }

        if (missing.isNotEmpty()) {
            AppCrashLogger.log("Rootfs corruption detected: missing $missing")
            return RootfsIntegrityResult.Corrupted(
                reason = "Critical rootfs files missing or 0 bytes: ${missing.joinToString()}",
                missingCriticalFiles = missing,
            )
        }

        return RootfsIntegrityResult.Intact
    }

    /**
     * Attempts safe recovery or cleanup of a corrupted rootfs to allow
     * clean re-installation.
     */
    fun resetCorruptedRootfs(): Boolean {
        return runCatching {
            AppCrashLogger.checkpoint("ResetCorruptedRootfs", "Removing corrupted rootfs at ${runtimeDir.path}")
            if (runtimeDir.exists()) {
                runtimeDir.deleteRecursively()
            }
            true
        }.getOrElse { false }
    }

    /**
     * Reaps stale temporary process sockets, proot binders, and orphaned pty artifacts.
     */
    fun cleanupOrphanArtifacts(): Int {
        var count = 0
        val tmpDir = File(runtimeDir, "tmp")
        if (tmpDir.isDirectory) {
            runCatching {
                tmpDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("tmp") || file.name.endsWith(".sock") || file.name.contains("pty")) {
                        if (file.delete()) count++
                    }
                }
            }
        }
        val prootTmp = File(baseFilesDir.parentFile, "cache/proot-tmp")
        if (prootTmp.isDirectory) {
            runCatching {
                prootTmp.listFiles()?.forEach { file ->
                    if (file.name.startsWith("proot") || file.name.endsWith(".sock")) {
                        if (file.delete()) count++
                    }
                }
            }
        }
        return count
    }
}
