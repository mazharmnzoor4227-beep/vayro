package com.vayro.app.runtime

import com.vayro.app.AppCrashLogger

import android.content.Context
import android.os.StatFs
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import com.vayro.app.mcp.isHttpsOrLoopbackHttp
import com.vayro.app.model.AgentKind
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class EnvironmentManifestComponent(
    val id: String,
    val label: String,
    val version: String,
    val packageUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val status: String,
    val minRuntimeGeneration: Int,
    val activeLink: String,
    val healthCommand: String,
    val notes: String,
)

data class EnvironmentComponentState(
    val id: String,
    val label: String,
    val currentVersion: String?,
    val latestVersion: String?,
    val rollbackVersion: String?,
    val rollbackAvailable: Boolean,
    val packageSizeBytes: Long,
    val notes: String,
)

class EnvironmentUpdateManager(
    private val context: Context,
    private val runtime: RuntimeInstaller,
) {
    private val rootfs get() = runtime.installedRuntime().rootfs
    private val base get() = File(rootfs, "opt/pocket/environment")
    private val components get() = File(base, "components")
    private val backups get() = File(base, "backups")
    private val downloads get() = File(context.cacheDir, "environment-updates")
    private val activeDownloadConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    fun cancelActiveDownloads() {
        activeDownloadConnections.toList().forEach { runCatching { it.disconnect() } }
    }

    fun parseManifest(body: String): List<EnvironmentManifestComponent> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return defaultComponents()
        val array = root.optJSONArray("components")
        if (array != null && array.length() > 0) {
            val result = ArrayList<EnvironmentManifestComponent>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                result += EnvironmentManifestComponent(
                    id = id,
                    label = item.optString("label", id),
                    version = item.optString("version", "1.0"),
                    packageUrl = item.optString("packageUrl", ""),
                    sha256 = item.optString("sha256", ""),
                    sizeBytes = item.optLong("sizeBytes", 0L),
                    status = item.optString("status", "stable"),
                    minRuntimeGeneration = item.optInt("minRuntimeGeneration", 1),
                    activeLink = item.optString("activeLink", ""),
                    healthCommand = item.optString("healthCommand", ""),
                    notes = item.optString("notes", ""),
                )
            }
            if (result.isNotEmpty()) return result
        }

        val bundles = root.optJSONObject("bundles")
        if (bundles != null) {
            val result = ArrayList<EnvironmentManifestComponent>()
            val keys = bundles.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val bundle = bundles.optJSONObject(key) ?: continue
                val id = when (key) {
                    "core" -> "core"
                    "python" -> "python"
                    "android" -> "android"
                    "claude" -> "claude"
                    "agy" -> "antigravity"
                    "dsh" -> "deepseek"
                    else -> key
                }
                val label = when (id) {
                    "core" -> "Core Linux Runtime"
                    "python" -> "Python"
                    "android" -> "Android SDK / Build Tools / Gradle"
                    "claude" -> "Claude Code"
                    "deepseek" -> "DeepSeek Harness"
                    "antigravity" -> "Antigravity CLI"
                    else -> id.replaceFirstChar { it.uppercase() }
                }
                result += EnvironmentManifestComponent(
                    id = id,
                    label = label,
                    version = bundle.optString("version", "1.0"),
                    packageUrl = "",
                    sha256 = bundle.optString("sha256", ""),
                    sizeBytes = bundle.optLong("compressedBytes", 0L),
                    status = "stable",
                    minRuntimeGeneration = 1,
                    activeLink = "",
                    healthCommand = "",
                    notes = bundle.optJSONArray("includes")?.let { arr ->
                        (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                    } ?: "",
                )
            }
            if (result.none { it.id == "php" }) {
                result += EnvironmentManifestComponent(
                    id = "php",
                    label = "PHP",
                    version = "8.4",
                    packageUrl = "",
                    sha256 = "",
                    sizeBytes = 0L,
                    status = "stable",
                    minRuntimeGeneration = 1,
                    activeLink = "",
                    healthCommand = "php --version && composer --version",
                    notes = "PHP 8.4 package source via Ondřej PHP PPA; Composer latest-stable release.",
                )
            }
            if (result.none { it.id == "cpp" }) {
                result += EnvironmentManifestComponent(
                    id = "cpp",
                    label = "C/C++",
                    version = "10.2",
                    packageUrl = "",
                    sha256 = "",
                    sizeBytes = 0L,
                    status = "stable",
                    minRuntimeGeneration = 1,
                    activeLink = "",
                    healthCommand = "gcc --version && g++ --version && cmake --version",
                    notes = "GCC, G++, CMake, and GNU Make compilers and build tools.",
                )
            }
            if (result.isNotEmpty()) return result
        }

        return defaultComponents()
    }

    fun defaultComponents(): List<EnvironmentManifestComponent> = listOf(
        EnvironmentManifestComponent(
            id = "core",
            label = "Core Linux Runtime",
            version = "2026.09.5",
            packageUrl = "",
            sha256 = "df0cf7251c74f82d424231e3804114a4ca66b16130eea9abab11e220dc7ac012",
            sizeBytes = 72185773L,
            status = "stable",
            minRuntimeGeneration = 1,
            activeLink = "",
            healthCommand = "git --version && node --version",
            notes = "Ubuntu 20.04 ARM64 core runtime bundle with Node.js 24 and Git.",
        ),
        EnvironmentManifestComponent(
            id = "python",
            label = "Python",
            version = "2026.09.2",
            packageUrl = "",
            sha256 = "6b3f56f7743fec142bc045db3ea561ee83fe89af87c2799165ef60351164ef85",
            sizeBytes = 55419626L,
            status = "stable",
            minRuntimeGeneration = 1,
            activeLink = "",
            healthCommand = "python3 --version && pip3 --version",
            notes = "Python 3.8, pip, and venv runtime bundle.",
        ),
        EnvironmentManifestComponent(
            id = "android",
            label = "Android SDK / Build Tools / Gradle",
            version = "2026.09.1",
            packageUrl = "",
            sha256 = "01bea058ebcb17416d1eb08c0211b3782da3228eb3c8348eb3719f2d61dd3ec6",
            sizeBytes = 569652007L,
            status = "stable",
            minRuntimeGeneration = 1,
            activeLink = "",
            healthCommand = "java -version && gradle --version",
            notes = "Temurin OpenJDK 17, Android SDK 36, Gradle 8.14.",
        ),
        EnvironmentManifestComponent(
            id = "php",
            label = "PHP",
            version = "8.4",
            packageUrl = "",
            sha256 = "",
            sizeBytes = 0L,
            status = "stable",
            minRuntimeGeneration = 1,
            activeLink = "",
            healthCommand = "php --version && composer --version",
            notes = "PHP 8.4 package source via Ondřej PHP PPA; Composer latest-stable release.",
        ),
        EnvironmentManifestComponent(
            id = "cpp",
            label = "C/C++",
            version = "10.2",
            packageUrl = "",
            sha256 = "",
            sizeBytes = 0L,
            status = "stable",
            minRuntimeGeneration = 1,
            activeLink = "",
            healthCommand = "gcc --version && g++ --version && cmake --version",
            notes = "GCC, G++, CMake, and GNU Make compilers and build tools.",
        ),
    )

    suspend fun refreshManifest(manifest: List<EnvironmentManifestComponent>): List<EnvironmentManifestComponent> {
        val latest = runtime.latestEnvironmentVersions()
        return manifest.map { item ->
            val version = latest[item.id] ?: item.version
            item.copy(version = version, notes = if (latest.containsKey(item.id)) "Live upstream release: $version" else item.notes)
        }
    }

    fun inspect(manifest: List<EnvironmentManifestComponent>): List<EnvironmentComponentState> =
        manifest.map { item ->
            val current = currentVersion(item.id)
            val backup = File(backups, item.id + ".zip")
            EnvironmentComponentState(
                id = item.id,
                label = item.label,
                currentVersion = current,
                latestVersion = item.version.takeIf { current == null || it != current },
                rollbackVersion = backupVersion(backup),
                rollbackAvailable = backup.isFile,
                packageSizeBytes = item.sizeBytes,
                notes = item.notes,
            )
        }

    suspend fun update(
        item: EnvironmentManifestComponent,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(item.status != "nightly") { "Nightly packages require an explicit development build" }
        require(item.minRuntimeGeneration <= 1) { "This package requires a newer runtime generation" }
        val old = currentVersion(item.id)
        if (item.id in AGENT_COMPONENT_IDS) {
            try {
                AppCrashLogger.checkpoint("ENVIRONMENT_AGENT_UPDATE", "id=" + item.id + ", current=" + (old ?: "<none>") + ", target=" + item.version)
                updateAgent(item, old, onProgress)
            } catch (error: Throwable) {
                AppCrashLogger.logOperationFailure("Agent update " + item.id + " " + item.version, error)
                throw error
            }
            return
        }
        if (item.id in STACK_COMPONENT_IDS) {
            try {
                AppCrashLogger.checkpoint("ENVIRONMENT_STACK_UPDATE", "id=" + item.id + ", current=" + (old ?: "<none>") + ", target=" + item.version)
                runtime.installEnvironmentStack(item.id, item.version, onProgress)
            } catch (error: Throwable) {
                AppCrashLogger.logOperationFailure("Stack update " + item.id + " " + item.version, error)
                throw error
            }
            return
        }
        require(item.packageUrl.startsWith("https://")) { "Environment package must use HTTPS" }
        preflightStorage(item.sizeBytes)
        if (old == item.version) return

        // A previous crashed/failed run may have left package zips and
        // staging dirs behind; start from a clean slate.
        cleanup()

        val componentRoot = File(components, item.id)
        val target = File(componentRoot, item.version)
        val staging = File(componentRoot, "." + item.version + ".installing")
        val packageFile = File(downloads, item.id + "-" + item.version + ".zip")

        staging.deleteRecursively()
        staging.mkdirs()
        downloads.mkdirs()

        onProgress(RuntimeInstallProgress("Downloading " + item.label + " " + item.version, 0.05f, indeterminate = true))
        download(item.packageUrl, packageFile, item.sha256) { done, total ->
            val fraction = if (total > 0L) done.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading " + item.label, 0.05f + fraction * 0.45f, done, total))
        }

        try {
            onProgress(RuntimeInstallProgress("Installing " + item.label + " " + item.version, 0.55f, indeterminate = true))
            extractZip(packageFile, staging)
            verifyPackage(staging, item)

            val backup = File(backups, item.id + ".zip")
            if (old != null) {
                val oldDir = File(componentRoot, old)
                check(oldDir.isDirectory) { "Previous " + item.label + " installation is missing" }
                zipDirectory(oldDir, backup, old)
            }

            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not stage " + item.label }
            activate(item, target)
            healthCheck(item, target)

            if (old != null) File(componentRoot, old).deleteRecursively()
            packageFile.delete()
            staging.deleteRecursively()
            onProgress(RuntimeInstallProgress(item.label + " " + item.version + " is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (error is CancellationException) {
                cleanup()
                throw error
            }
            if (old != null && File(backups, item.id + ".zip").isFile) {
                runCatching { restore(item, old) }
            }
            // The failed run's package zip must not accumulate in cache.
            cleanup()
            throw IllegalStateException(item.label + " update failed: " + (error.message ?: "unknown error"), error)
        }
    }

    suspend fun rollback(
        item: EnvironmentManifestComponent,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val backup = File(backups, item.id + ".zip")
        if (item.id in AGENT_COMPONENT_IDS) {
            runtime.rollbackEnvironmentAgent(item.id, backup, onProgress)
            return
        }
        val version = backupVersion(backup) ?: error("No rollback backup is available for " + item.label)
        val current = currentVersion(item.id)
        val componentRoot = File(components, item.id)
        val restore = File(componentRoot, "." + version + ".restore")
        restore.deleteRecursively()
        restore.mkdirs()

        onProgress(RuntimeInstallProgress("Restoring " + item.label + " " + version, 0.15f, indeterminate = true))
        extractZip(backup, restore)
        verifyPackage(restore, item.copy(version = version))
        healthCheck(item.copy(version = version), restore)

        if (current != null && current != version) File(componentRoot, current).deleteRecursively()
        val active = File(componentRoot, version)
        active.deleteRecursively()
        check(restore.renameTo(active)) { "Could not finalize rollback" }
        activate(item.copy(version = version), active)
        backup.delete()
        onProgress(RuntimeInstallProgress(item.label + " rollback complete", 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    fun cleanup() {
        purgeStaleUpdateFiles(downloads, components)
    }

    private fun currentVersion(id: String): String? {
        runtime.installedEnvironmentVersions()[id]?.let { return it }
        return File(components, id).listFiles().orEmpty()
            .firstOrNull { it.isDirectory && !it.name.startsWith(".") }
            ?.name
    }

    private suspend fun updateAgent(item: EnvironmentManifestComponent, old: String?, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        val expected = item.version
        if (old == expected) return
        preflightStorage(8L * 1024L * 1024L)
        val backup = File(backups, item.id + ".zip")
        if (old != null) runtime.backupEnvironmentAgent(item.id, old, backup)
        try {
            runtime.updateEnvironmentAgent(item.id, expected, onProgress)
            onProgress(RuntimeInstallProgress(item.label + " " + expected + " is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
        } catch (error: Throwable) {
            if (old != null && backup.isFile) runCatching { runtime.rollbackEnvironmentAgent(item.id, backup, onProgress) }
            throw error
        }
    }

    private fun backupVersion(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            ZipInputStream(FileInputStream(file)).use { zip ->
                val entry = zip.nextEntry ?: return@runCatching null
                if (entry.name != ".version") return@runCatching null
                zip.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun restore(item: EnvironmentManifestComponent, version: String) {
        val backup = File(backups, item.id + ".zip")
        val root = File(components, item.id)
        val restore = File(root, "." + version + ".rollback")
        restore.deleteRecursively()
        restore.mkdirs()
        extractZip(backup, restore)
        root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.forEach(File::deleteRecursively)
        val active = File(root, version)
        check(restore.renameTo(active)) { "Rollback extraction failed" }
        activate(item, active)
        healthCheck(item, active)
    }

    private fun activate(item: EnvironmentManifestComponent, target: File) {
        if (item.activeLink.isBlank()) return
        val link = File(rootfs, item.activeLink.removePrefix("/"))
        link.parentFile?.mkdirs()
        val path = link.toPath()
        if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) java.nio.file.Files.delete(path)
        Os.symlink(target.absolutePath, link.absolutePath)
    }

    private fun healthCheck(item: EnvironmentManifestComponent, installRoot: File) {
        check(installRoot.listFiles().orEmpty().isNotEmpty()) { item.label + " package is empty" }
        val marker = File(installRoot, ".component.json")
        check(marker.isFile) { item.label + " package metadata is missing" }
        val json = JSONObject(marker.readText())
        check(json.optString("id") == item.id) { item.label + " package id mismatch" }
        check(json.optString("version") == item.version) { item.label + " package version mismatch" }
    }

    private fun verifyPackage(root: File, item: EnvironmentManifestComponent) {
        healthCheck(item, root)
    }

    private fun preflightStorage(packageBytes: Long) {
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        val required = packageBytes + 64L * 1024L * 1024L
        check(available >= required) {
            "Not enough storage. Need at least " + (required / (1024L * 1024L)) + " MB free."
        }
    }

    private fun zipDirectory(source: File, destination: File, version: String) {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        part.delete()
        ZipOutputStream(FileOutputStream(part)).use { zip ->
            zip.putNextEntry(ZipEntry(".version"))
            zip.write(version.toByteArray())
            zip.closeEntry()
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(source).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(name))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        destination.delete()
        check(part.renameTo(destination)) { "Could not finalize rollback archive" }
    }

    private fun extractZip(archive: File, destination: File) {
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                check(name.isNotBlank() && !name.startsWith("/") && !name.contains("../")) { "Unsafe update archive path" }
                val target = File(destination, name)
                check(target.canonicalFile.toPath().startsWith(destination.canonicalFile.toPath())) { "Unsafe update archive path" }
                if (entry.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }
    }

    private suspend fun download(
        url: String,
        destination: File,
        expectedSha256: String,
        onBytes: suspend (Long, Long) -> Unit,
    ) {
        check(isHttpsOrLoopbackHttp(url)) { "Environment package URL must use https (got ${url.substringBefore("://")})" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        check(connection.responseCode in 200..299) { "Environment download failed: HTTP " + connection.responseCode }
        val total = connection.contentLengthLong
        activeDownloadConnections.add(connection)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        onBytes(done, total)
                    }
                }
            }
        } finally {
            activeDownloadConnections.remove(connection)
            connection.disconnect()
        }
        check(sha256(destination).equals(expectedSha256, true)) { "Environment package SHA-256 verification failed" }
    }

    companion object {
        private val AGENT_COMPONENT_IDS = setOf("claude", "deepseek", "antigravity")
        private val STACK_COMPONENT_IDS = setOf("core", "python", "android", "php", "cpp")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Deletes stale environment-update leftovers: abandoned package zips /
 * partials in [downloads], and orphaned .installing / .restore staging dirs
 * under [components]. Pure file logic so it stays unit-testable; the
 * manager's [EnvironmentUpdateManager.cleanup] delegates to it.
 */
internal fun purgeStaleUpdateFiles(downloads: File, components: File) {
    downloads.listFiles().orEmpty()
        .forEach { if (it.name.endsWith(".zip") || it.name.endsWith(".part")) it.delete() }
    components.listFiles().orEmpty().forEach { dir ->
        dir.listFiles().orEmpty()
            .filter { it.name.contains(".installing") || it.name.contains(".restore") }
            .forEach(File::deleteRecursively)
    }
}
