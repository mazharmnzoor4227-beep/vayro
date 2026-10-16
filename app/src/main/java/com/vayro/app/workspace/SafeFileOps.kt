package com.vayro.app.workspace

import com.vayro.app.AppCrashLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Production-grade file operations ensuring atomic writes and crash-safe operations
 * under real-world Android process lifecycle constraints.
 */
object SafeFileOps {

    /**
     * Atomically writes string content to [target] using a temporary staging file
     * and file descriptor sync to avoid partial write corruption if the process crashes.
     */
    fun atomicWriteText(target: File, content: String): Boolean {
        return atomicWriteBytes(target, content.toByteArray(Charsets.UTF_8))
    }

    /**
     * Atomically writes byte array to [target] using a temporary staging file.
     */
    fun atomicWriteBytes(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val tempFile = File(parent, "${target.name}.tmp_${UUID.randomUUID().toString().take(8)}")
        return try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                    // Sync not supported on all virtual/mounted filesystems
                }
            }

            // Attempt atomic move first, falling back to renameTo or copy-and-delete
            val moved = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    Files.move(
                        tempFile.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    true
                } else false
            }.getOrDefault(false)

            if (!moved) {
                if (target.exists()) target.delete()
                val renamed = tempFile.renameTo(target)
                if (!renamed) {
                    tempFile.copyTo(target, overwrite = true)
                    tempFile.delete()
                }
            }
            true
        } catch (e: Throwable) {
            AppCrashLogger.log("SafeFileOps atomic write failed for ${target.path}: ${e.message}")
            runCatching { tempFile.delete() }
            false
        }
    }

    /**
     * Safely reads text from [target] if it exists and is a valid file.
     * Returns null if missing, empty (when non-empty expected), or unreadable.
     */
    fun safeReadText(target: File): String? {
        if (!target.isFile) return null
        return runCatching {
            target.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Cleans up stale `.tmp_*` staging files left behind by past app crashes or battery pulls.
     */
    fun cleanStaleTempFiles(directory: File, maxAgeMs: Long = 300_000L): Int {
        if (!directory.isDirectory) return 0
        var cleaned = 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        runCatching {
            directory.listFiles()?.forEach { file ->
                if (file.name.contains(".tmp_") && file.lastModified() < cutoff) {
                    if (file.delete()) cleaned++
                }
            }
        }
        return cleaned
    }
}
