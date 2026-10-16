package com.vayro.app.files

import java.io.File
import java.util.UUID

object SharedExport {
    private const val MAX_STAGED_AGE_MS = 24L * 60L * 60L * 1000L
    private val SAFE_NAME = Regex("[^A-Za-z0-9._-]")

    fun installDir(filesDir: File): File = File(filesDir, "shared/install").apply { mkdirs() }

    fun openDir(filesDir: File): File = File(filesDir, "shared/open").apply { mkdirs() }

    fun stageApkForInstall(filesDir: File, apk: File): File {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true)) { "Not a valid APK file" }
        val dir = installDir(filesDir)
        pruneStale(dir)
        val base = apk.nameWithoutExtension.replace(SAFE_NAME, "_").replace("..", "__").take(40).trim('_').ifBlank { "update" }
        val staged = File(dir, "$base-${UUID.randomUUID().toString().take(8)}.apk")
        apk.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
        return staged
    }

    fun stageFileForOpen(filesDir: File, source: File, displayName: String): File {
        require(source.isFile) { "Attachment is unavailable" }
        val dir = openDir(filesDir)
        pruneStale(dir)
        val safe = displayName.replace(SAFE_NAME, "_").replace("..", "__").take(80).trim('_').ifBlank { "file" }
        var staged = File(dir, safe)
        var counter = 2
        while (staged.exists()) {
            val stem = safe.substringBeforeLast('.', safe)
            val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            staged = File(dir, "$stem-$counter$extension")
            counter += 1
        }
        source.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
        return staged
    }

    private fun pruneStale(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_STAGED_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
