package com.vayro.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import android.system.Os
import com.vayro.app.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import com.vayro.app.model.DevStack
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

data class InstalledRuntime(
    val proot: File,
    val rootfs: File,
)

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

private data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
    val remoteUrl: String? = null,
) {
    val version: String
        get() = fileName.substringAfterLast("-").removeSuffix(".tar.zst")
}

internal const val MAX_RUNTIME_ARCHIVE_BYTES = 2_147_483_648L
internal const val MAX_RUNTIME_EXTRACTED_BYTES = 4_294_967_296L
internal const val MAX_RUNTIME_ARCHIVE_ENTRIES = 1_000_000
internal const val MAX_RUNTIME_ARCHIVE_FILE_BYTES = 1_073_741_824L
internal const val EXTRACTION_TIMEOUT_MS = 1_800_000L

internal class RuntimeExtractionBudget {
    var entries = 0
        private set
    var totalBytes = 0L
        private set

    fun checkEntry() {
        entries += 1
        require(entries <= MAX_RUNTIME_ARCHIVE_ENTRIES) { "Runtime archive contains too many entries" }
    }

    fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(128 * 1024)
        var fileBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            fileBytes += count
            require(fileBytes <= MAX_RUNTIME_ARCHIVE_FILE_BYTES) { "Runtime archive entry exceeds the per-file size limit" }
            totalBytes += count
            require(totalBytes <= MAX_RUNTIME_EXTRACTED_BYTES) { "Runtime archive exceeds the maximum extracted size" }
            output.write(buffer, 0, count)
        }
    }
}

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val coreReadyMarker = File(rootfs, ".pocket-runtime-ready")
    private val claudeMarker = File(rootfs, ".pocket-claude-version")
    // Read only for migration from Core bundles that embedded Claude Code.
    private val bundledClaudeMarker = File(rootfs, ".pocket-bundled-claude-version")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val languageToolsMarker = File(rootfs, ".pocket-language-tools-version")
    private val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".pocket-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val dshMarker = File(rootfs, ".pocket-dsh-version")
    private val agyMarker = File(rootfs, ".pocket-agy-version")
    private val codexMarker = File(rootfs, ".pocket-codex-version")
    private val githubCliMarker = File(rootfs, ".pocket-github-cli-version")
    private val dshAndroidCompatibilityMarker = File(rootfs, ".pocket-dsh-android-compat-version")
    private val macosMetadataRepairMarker = File(rootfs, ".pocket-macos-metadata-repair")
    private val activeDownloadConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    fun cancelActiveDownloads() {
        activeDownloadConnections.toList().forEach { runCatching { it.disconnect() } }
    }

    fun isInstalled(): Boolean {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val rootfsLayoutReady = ensureRootfsCompatibilityLinks()
        // Devices set up before staged toolchains keep working through the legacy marker;
        // fresh installs require the new core-tools marker instead.
        val legacyLanguageTools = languageToolsMarker.readTextOrNull() == LANGUAGE_TOOLS_VERSION
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() && isSupportedCoreToolsVersion()
        val ready = rootfsLayoutReady &&
            proot.canExecute() &&
            File(rootfs, "usr/bin/bash").exists() &&
            rootfsMarker.readTextOrNull() == ROOTFS_VERSION &&
            File(rootfs, "usr/local/bin/node").exists() &&
            (legacyLanguageTools || coreToolsReady) &&
            coreReadyMarker.exists()
        if (ready) repairLegacyMacosMetadata()
        return ready
    }

    /**
     * One-shot cleanup for devices that already extracted a tarball built on
     * macOS without the `--no-mac-metadata` flag. Such bundles contain
     * `._filename` AppleDouble metadata files that crash Python 3.8 when it
     * reads every `.pth` file in site-packages. After the first successful
     * cleanup we write a marker so we never walk the entire rootfs again.
     */
    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        stripMacosMetadataArtifacts(rootfs)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeText("1")
    }

    /** Returns the already verified runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { "Core runtime setup is incomplete. Reopen Kodex to repair it." }
        return InstalledRuntime(
            proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
            rootfs = rootfs,
        )
    }

    fun secureCredentialRoot(): File = File(context.filesDir, "secure-credentials").apply { mkdirs() }

    fun githubCredentialHostDir(): File = File(secureCredentialRoot(), "github").apply { mkdirs() }

    fun antigravityCredentialHostDir(): File = File(secureCredentialRoot(), "antigravity").apply { mkdirs() }

    fun githubCredentialBinds(): List<Pair<String, String>> =
        listOf(githubCredentialHostDir().absolutePath to GUEST_GITHUB_CONFIG_DIR)

    fun antigravityCredentialBinds(): List<Pair<String, String>> =
        listOf(antigravityCredentialHostDir().absolutePath to GUEST_ANTIGRAVITY_CONFIG_DIR)

    private val credentialMigrationLock = Any()
    @Volatile private var credentialMigrationDone = false

    fun ensureGuestCredentialsMigrated() {
        if (credentialMigrationDone) return
        synchronized(credentialMigrationLock) {
            if (credentialMigrationDone) return
            relocateCredentialFiles(File(rootfs, "root/.config/gh"), githubCredentialHostDir())
            relocateCredentialFiles(File(rootfs, "root/.gemini/antigravity-cli"), antigravityCredentialHostDir())
            credentialMigrationDone = true
        }
    }

    /** Removes only scaffolding written automatically by earlier PocketDev alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        agent: com.vayro.app.model.AgentKind = com.vayro.app.model.AgentKind.CLAUDE_CODE,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(
            supportsArm64Runtime(
                android.os.Build.SUPPORTED_ABIS,
                System.getProperty("os.arch"),
            ),
        ) { "Unsupported architecture: Kodex requires an ARM64 device or ARM64 emulator" }
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        require(proot.canExecute()) { "The embedded PRoot launcher is unavailable" }

        if (!File(rootfs, "usr/bin/bash").exists() || rootfsMarker.readTextOrNull() != ROOTFS_VERSION) {
            onProgress(RuntimeInstallProgress("Preparing the private development runtime", 0.03f))
            val archive = obtainRuntimeBundle(
                CORE_BUNDLE,
                preferEmbedded = BuildConfig.OFFLINE_RUNTIME_BUNDLES,
                from = 0.03f,
                to = 0.25f,
                onProgress,
            )
            onProgress(RuntimeInstallProgress("Verifying and unpacking the Core runtime", 0.28f))
            val staging = File(runtimeDir, "ubuntu.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            withTimeout(EXTRACTION_TIMEOUT_MS) { extractZstdTar(archive, staging) }
            stripMacosMetadataArtifacts(staging)
            require(File(staging, "usr/bin/bash").isFile) { "Core bundle is missing Bash" }
            // The old code deleted the live rootfs before the rename: a
            // kill or storage failure in between destroyed the runtime.
            // Swap via a backup so a failed activation restores the old one.
            activateStagingDir(staging, rootfs, File(runtimeDir, "ubuntu.previous"))
            check(ensureRootfsCompatibilityLinks()) { "Core runtime has an invalid Linux filesystem layout" }
            writeResolver()
            if (archive.parentFile == downloads) archive.delete()
        }

        check(ensureRootfsCompatibilityLinks()) { "Core runtime has an invalid Linux filesystem layout" }

        migrateLegacyClaudeMarker()
        ensureSettingsAndHooks()

        // Node.js and Git are always available in the Core runtime. Python,
        // C/C++, PHP, and Android remain opt-in stacks during onboarding.
        val coreNeeded = !File(rootfs, "usr/bin/git").exists() || !isSupportedCoreToolsVersion()
        if (coreNeeded) {
            installNodeIfNeeded(proot, 0.58f, 0.66f, onProgress)
        }
        if (systemUpgradeMarker.readTextOrNull() != SYSTEM_UPGRADE_VERSION) {
            if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
                // Offline first-run setup must never turn into a network operation.
                onProgress(RuntimeInstallProgress("Using the bundled Ubuntu environment", 0.69f))
                systemUpgradeMarker.writeText(SYSTEM_UPGRADE_VERSION)
            } else {
                runSystemMaintenance(proot, onProgress)
                systemUpgradeMarker.writeText(SYSTEM_UPGRADE_VERSION)
            }
        }
        if (coreNeeded) {
            aptInstall(
                proot,
                listOf("git", "ca-certificates"),
                "Installing Git and base tools",
                0.70f,
                onProgress,
            )
            writeResolver()
            verifyGuest(proot, "git --version", "Base tools could not be verified")
            coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        }

        val eligibleStacks = if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            val dropped = selectedStacks.filterNot { it in setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID) }
            if (dropped.isNotEmpty()) {
                onProgress(
                    RuntimeInstallProgress(
                        "Skipped ${dropped.joinToString { it.label }}: not included in the offline build. " +
                            "Use the online build to install these stacks.",
                        0.71f,
                    )
                )
            }
            selectedStacks.filter { it in setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID) }
        } else {
            selectedStacks
        }
        val missingStacks = eligibleStacks.filterNot(::isStackInstalled)
        missingStacks.forEachIndexed { index, stack ->
            val slice = 0.26f / maxOf(1, missingStacks.size)
            val from = 0.72f + index * slice
            applyStack(proot, stack, from, from + slice, onProgress)
        }

        when (agent) {
            com.vayro.app.model.AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(proot, 0.985f, onProgress)
            com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(proot, 0.985f, onProgress)
            com.vayro.app.model.AgentKind.ANTIGRAVITY -> ensureAgyInstalled(proot, 0.985f, onProgress)
            com.vayro.app.model.AgentKind.CODEX -> ensureCodexInstalled(proot, 0.985f, onProgress)
            // Custom is bring-your-own-API: nothing to install.
            com.vayro.app.model.AgentKind.CUSTOM -> Unit
        }
        onProgress(RuntimeInstallProgress("Setup complete", 1f))
        return InstalledRuntime(proot, rootfs)
    }

    /**
     * Installs one coding agent on demand. Safe to call again: an already-installed
     * agent returns immediately without network access. Every coding agent is a
     * separate overlay and is fetched or loaded only when selected.
     */
    suspend fun ensureAgentInstalled(
        agent: com.vayro.app.model.AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            com.vayro.app.model.AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(runtime.proot, 0.05f, onProgress)
            com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(runtime.proot, 0.05f, onProgress)
            com.vayro.app.model.AgentKind.ANTIGRAVITY -> ensureAgyInstalled(runtime.proot, 0.05f, onProgress)
            com.vayro.app.model.AgentKind.CODEX -> ensureCodexInstalled(runtime.proot, 0.05f, onProgress)
            // Custom is bring-your-own-API: nothing to install.
            com.vayro.app.model.AgentKind.CUSTOM -> Unit
        }
        onProgress(RuntimeInstallProgress("${agent.title} is ready", 1f))
    }

    fun isAgentInstalled(agent: com.vayro.app.model.AgentKind): Boolean {
        if (!isInstalled()) return false
        return when (agent) {
            com.vayro.app.model.AgentKind.CLAUDE_CODE -> {
                migrateLegacyClaudeMarker()
                guestExecutableFile(CLAUDE_GUEST_PATH) != null &&
                    !claudeMarker.readTextOrNull().isNullOrBlank()
            }
            com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS ->
                guestExecutableFile("/usr/local/lib/dsh/node_modules/.bin/dsh") != null &&
                    !dshMarker.readTextOrNull().isNullOrBlank()
            com.vayro.app.model.AgentKind.ANTIGRAVITY ->
                guestExecutableFile(AGY_GUEST_PATH) != null &&
                    !agyMarker.readTextOrNull().isNullOrBlank()
            com.vayro.app.model.AgentKind.CODEX ->
                guestExecutableFile(CODEX_GUEST_PATH) != null &&
                    !codexMarker.readTextOrNull().isNullOrBlank()
            // Custom is bring-your-own-API: always available, nothing to install.
            com.vayro.app.model.AgentKind.CUSTOM -> true
        }
    }

    /**
     * Resolve an executable using the Ubuntu guest filesystem, not Android's host
     * filesystem. Agent bundles may publish absolute guest symlinks (for example
     * /root/.local/bin/agy); java.io.File would otherwise follow that link from
     * the Android filesystem root and report a false "not installed".
     */
    private fun guestExecutableFile(guestPath: String): File? {
        var candidate = File(rootfs, guestPath.removePrefix("/"))
        repeat(16) {
            val nio = candidate.toPath()
            if (java.nio.file.Files.isSymbolicLink(nio)) {
                val link = runCatching { java.nio.file.Files.readSymbolicLink(nio).toString() }.getOrNull()
                    ?: return null
                candidate = if (link.startsWith("/")) {
                    File(rootfs, link.removePrefix("/"))
                } else {
                    File(candidate.parentFile ?: return null, link)
                }
                return@repeat
            }
            return if (candidate.isFile && candidate.canExecute()) candidate else null
        }
        return null
    }

    val dshVersion: String get() = dshMarker.readTextOrNull().orEmpty()


    fun isGitHubCliInstalled(): Boolean = isInstalled() &&
        File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
        githubCliMarker.readTextOrNull() == GITHUB_CLI_VERSION

    /** Installs GitHub's official ARM64 CLI on demand; it is not bundled in the APK. */
    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitHubCliInstalled()) return
        // The offline APK contains the core runtime locally, but GitHub itself is
        // an online service. Do not disable GitHub authentication merely because
        // runtime bundles were embedded in this APK. The verified gh binary is
        // downloaded on demand when the device has network access.
        writeResolver()
        downloads.mkdirs()
        val downloaded = File(downloads, "gh-$GITHUB_CLI_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress("Downloading official GitHub CLI", 0.05f))
        downloadVerified(GITHUB_CLI_RELEASE_URL, downloaded, GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = "Downloading GitHub CLI $GITHUB_CLI_VERSION",
                    fraction = 0.05f + ratio * 0.75f,
                    downloadedBytes = bytes,
                    totalBytes = total.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing GitHub CLI $GITHUB_CLI_VERSION", 0.85f, indeterminate = true))
        val destination = File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                    val staged = File(destination.parentFile, ".gh-$GITHUB_CLI_VERSION.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        check(found) { "Official GitHub CLI archive did not contain the expected binary" }
        downloaded.delete()
        verifyGuest(proot = installedRuntime().proot, command = "$GITHUB_CLI_GUEST_PATH --version", failureMessage = "GitHub CLI verification failed")
        githubCliMarker.writeText(GITHUB_CLI_VERSION)
        check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress("GitHub CLI is ready", 1f))
    }

    /**
     * Versions recorded after each real agent binary has been installed and verified.
     * This intentionally reports what is present in PRoot, even when a newer app build
     * would subsequently offer an agent update.
     */
    fun installedEnvironmentVersions(): Map<String, String> = buildMap {
        installedAgentVersions().forEach { (agent, version) ->
            put(
                when (agent) {
                    com.vayro.app.model.AgentKind.CLAUDE_CODE -> "claude"
                    com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS -> "deepseek"
                    com.vayro.app.model.AgentKind.ANTIGRAVITY -> "antigravity"
                    com.vayro.app.model.AgentKind.CODEX -> "codex"
                    com.vayro.app.model.AgentKind.CUSTOM -> "custom"
                },
                version,
            )
        }
        val coreVer = coreToolsMarker.readTextOrNull()?.trim()?.takeIf { it.isNotBlank() }?.removePrefix("core-bundle-")
            ?: if (isInstalled()) CORE_BUNDLE.version else null
        coreVer?.let { put("core", it) }

        val androidVer = File(rootfs, "root/.pocket-android-tools-version").readTextOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: if (isStackInstalled(DevStack.ANDROID)) ANDROID_BUNDLE.version else null
        androidVer?.let { put("android", it) }

        val pythonVer = File(rootfs, ".pocket-python-tools-version").readTextOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: if (isStackInstalled(DevStack.PYTHON)) PYTHON_BUNDLE.version else null
        pythonVer?.let { put("python", it) }

        val phpVer = File(rootfs, ".pocket-php-tools-version").readTextOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: if (isStackInstalled(DevStack.PHP)) "8.4" else null
        phpVer?.let { put("php", it) }

        val cppVer = File(rootfs, ".pocket-cpp-tools-version").readTextOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: if (isStackInstalled(DevStack.CPP)) "10.2" else null
        cppVer?.let { put("cpp", it) }

        val browserVer = File(rootfs, ".pocket-browser-tools-version").readTextOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: if (isStackInstalled(DevStack.BROWSER)) "1.0" else null
        browserVer?.let { put("browser", it) }
    }

    /**
     * QA Medium: the version probes used to run sequentially (each with its
     * own network round trip) and the live PyPI result was computed then
     * discarded (pipVer never stored). The independent probes now run in
     * parallel, so the total time is bounded by the slowest single fetch
     * (each fetch already carries its own connect/read timeout), and the
     * pip version is stored in the python entry as "3.12 (pip 25.1)" —
     * the format Settings already parses with substringBefore(" (pip").
     */
    private var cachedEnvironmentVersions: Map<String, String>? = null
    private var cachedEnvironmentVersionsAt = 0L

    fun invalidateEnvironmentVersionCache() {
        cachedEnvironmentVersions = null
        cachedEnvironmentVersionsAt = 0L
    }

    suspend fun latestEnvironmentVersions(): Map<String, String> {
        val cached = cachedEnvironmentVersions
        if (cached != null && System.currentTimeMillis() - cachedEnvironmentVersionsAt < ENVIRONMENT_VERSION_CACHE_TTL_MS) {
            return cached
        }
        val fresh = fetchLatestEnvironmentVersions()
        cachedEnvironmentVersions = fresh
        cachedEnvironmentVersionsAt = System.currentTimeMillis()
        return fresh
    }

    private suspend fun fetchLatestEnvironmentVersions(): Map<String, String> = coroutineScope {
        val claude = async(Dispatchers.IO) {
            runCatching { JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version") }.getOrNull()
        }
        val antigravity = async(Dispatchers.IO) {
            runCatching { fetchAgyManifest().getString("version") }.getOrNull()
        }
        val codex = async(Dispatchers.IO) {
            runCatching { JSONObject(fetchText("https://registry.npmjs.org/@openai/codex/latest")).getString("version") }.getOrNull()
        }
        val core = async(Dispatchers.IO) {
            runCatching { latestRuntimeBundle("core")?.version }.getOrNull()
        }
        val pythonBundle = async(Dispatchers.IO) {
            runCatching { latestRuntimeBundle("python")?.version }.getOrNull()
        }
        val android = async(Dispatchers.IO) {
            runCatching { latestRuntimeBundle("android")?.version }.getOrNull()
        }
        // Live official PyPI API for Python pip.
        val pip = async(Dispatchers.IO) {
            runCatching {
                JSONObject(fetchText("https://pypi.org/pypi/pip/json")).getJSONObject("info").getString("version")
            }.getOrNull()
        }
        // Live official Composer version from getcomposer.org.
        val composer = async(Dispatchers.IO) {
            runCatching {
                val composerJson = JSONObject(fetchText("https://getcomposer.org/versions"))
                composerJson.optJSONArray("stable")?.optJSONObject(0)?.optString("version")?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        val browser = async(Dispatchers.IO) {
            runCatching { JSONObject(fetchText("https://registry.npmjs.org/puppeteer-core/latest")).getString("version") }.getOrNull()
        }

        buildMap {
            claude.await()?.let { put("claude", it) }
            // DeepSeek Harness is pinned to this build's verified release:
            // its "latest" is the pin, never npm latest (see updateDsh).
            put("deepseek", DSH_VERSION)
            antigravity.await()?.let { put("antigravity", it) }
            codex.await()?.let { put("codex", it) }
            core.await()?.let { put("core", it) }
            pythonBundle.await()?.let { base ->
                val pipVer = pip.await()
                put("python", if (!pipVer.isNullOrBlank()) "$base (pip $pipVer)" else base)
            }
            android.await()?.let { put("android", it) }

            // C/C++ GCC & build tools upstream
            put("cpp", "10.2")

            // Chromium & Puppeteer upstream
            browser.await()?.let { put("browser", it) } ?: put("browser", "1.0")

            val composerVer = composer.await()
            put("php", if (!composerVer.isNullOrBlank()) "8.4 (Composer $composerVer)" else "8.4")
        }
    }

    suspend fun installEnvironmentStack(
        id: String,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (id) {
            "python" -> {
                val cleanExpected = expectedVersion.substringBefore(" ").trim()
                val bundle = latestRuntimeBundle(id, cleanExpected)
                    ?: latestRuntimeBundle(id)
                val currentVer = File(rootfs, ".pocket-python-tools-version").readTextOrNull()?.trim()
                if (bundle != null && currentVer?.substringBefore(" ")?.trim() != cleanExpected) {
                    installRuntimeOverlay(bundle, "Installing Python $cleanExpected", 0.05f, 0.65f, onProgress)
                }
                onProgress(RuntimeInstallProgress("Upgrading pip, setuptools, and wheel from official PyPI", 0.70f))
                val pipUpgradeOk = runCatching {
                    runGuestExitCode(runtime.proot, "python3 -m pip install --upgrade --no-warn-script-location pip setuptools wheel", 120_000L) == 0
                }.getOrDefault(false)
                verifyGuest(runtime.proot, "python3 --version && pip3 --version", "Python tools could not be verified")
                val pipVer = runCatching {
                    runGuest(runtime.proot, "pip3 --version").split(" ").getOrNull(1)
                }.getOrNull()?.takeIf { it.isNotBlank() }
                // QA Medium: the installed pip version used to be computed
                // then discarded, so the marker never recorded it. The
                // "python" version carries pip as "3.12 (pip 25.1)" — the
                // format Settings parses and installEnvironmentStack
                // already strips on read.
                val finalVersion = if (pipVer != null) "$cleanExpected (pip $pipVer)" else cleanExpected
                if (pipUpgradeOk) {
                    File(rootfs, ".pocket-python-tools-version").writeText(finalVersion)
                } else {
                    onProgress(RuntimeInstallProgress("pip upgrade did not complete; keeping the previous version marker so the update is retried later", 0.70f))
                }
                writeDevStackState(readDevStackState().apply { put(DevStack.PYTHON.name, true) })
                onProgress(RuntimeInstallProgress("Python tools are ready ($finalVersion)", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            "android" -> {
                val cleanExpected = expectedVersion.substringBefore(" ").trim()
                val bundle = latestRuntimeBundle(id, cleanExpected)
                    ?: latestRuntimeBundle(id)
                    ?: error("No verified runtime package is published for $id $expectedVersion")
                installAndroidToolchain(runtime.proot, 0.05f, 0.95f, onProgress, bundle)
                File(rootfs, "root/.pocket-android-tools-version").writeText(cleanExpected)
                writeDevStackState(readDevStackState().apply { put(DevStack.ANDROID.name, true) })
                onProgress(RuntimeInstallProgress("Android $cleanExpected is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            "php" -> {
                updateStack(DevStack.PHP, onProgress)
                writeDevStackState(readDevStackState().apply { put(DevStack.PHP.name, true) })
                val recordedVer = File(rootfs, ".pocket-php-tools-version").readTextOrNull()?.trim() ?: expectedVersion
                onProgress(RuntimeInstallProgress("PHP $recordedVer is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            "cpp" -> {
                updateStack(DevStack.CPP, onProgress)
                writeDevStackState(readDevStackState().apply { put(DevStack.CPP.name, true) })
                val recordedVer = File(rootfs, ".pocket-cpp-tools-version").readTextOrNull()?.trim() ?: expectedVersion
                onProgress(RuntimeInstallProgress("C/C++ $recordedVer is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            "core" -> {
                onProgress(RuntimeInstallProgress("Updating core packages and Node tooling", 0.1f))
                runSystemMaintenance(runtime.proot, onProgress)
                runCatching {
                    runGuest(runtime.proot, "npm install -g npm@latest || true", 120_000L)
                }
                val cleanExpected = expectedVersion.substringBefore(" ").trim()
                coreToolsMarker.writeText("core-bundle-$cleanExpected")
                onProgress(RuntimeInstallProgress("Core runtime $cleanExpected is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            else -> error("Unsupported environment stack: $id")
        }
    }

    suspend fun updateEnvironmentAgent(
        id: String,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val agent = when (id) {
            "claude" -> com.vayro.app.model.AgentKind.CLAUDE_CODE
            "deepseek" -> com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS
            "antigravity" -> com.vayro.app.model.AgentKind.ANTIGRAVITY
            else -> error("Unsupported environment agent: " + id)
        }
        updateAgent(agent, expectedVersion, onProgress)
    }

    fun backupEnvironmentAgent(id: String, version: String, destination: File) {
        val entries = when (id) {
            "claude" -> listOf(CLAUDE_GUEST_PATH.removePrefix("/"))
            "antigravity" -> listOf(AGY_GUEST_PATH.removePrefix("/"))
            "deepseek" -> listOf("usr/local/lib/dsh")
            else -> error("Unsupported environment agent: " + id)
        }
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        part.delete()
        ZipOutputStream(FileOutputStream(part)).use { zip ->
            zip.putNextEntry(ZipEntry(".version"))
            zip.write(version.toByteArray())
            zip.closeEntry()
            entries.forEach { relative ->
                val source = File(rootfs, relative)
                check(source.exists()) { "Installed " + id + " runtime is missing: " + relative }
                source.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = file.relativeTo(rootfs).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(name))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        destination.delete()
        check(part.renameTo(destination)) { "Could not save " + id + " rollback backup" }
    }

    suspend fun rollbackEnvironmentAgent(
        id: String,
        backup: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(backup.isFile) { "No rollback backup is available for " + id }
        val root = rootfs
        onProgress(RuntimeInstallProgress("Restoring " + id + " " + backupVersionFromEnvironmentBackup(backup), 0.2f, indeterminate = true))
        ZipInputStream(FileInputStream(backup)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name != ".version") {
                    val target = File(root, entry.name)
                    check(target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Unsafe rollback path" }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }
        when (id) {
            "claude" -> Os.chmod(File(root, CLAUDE_GUEST_PATH.removePrefix("/")).absolutePath, 0b111101101)
            "antigravity" -> Os.chmod(File(root, AGY_GUEST_PATH.removePrefix("/")).absolutePath, 0b111101101)
        }
        onProgress(RuntimeInstallProgress(id + " rollback complete", 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    private fun backupVersionFromEnvironmentBackup(file: File): String =
        ZipInputStream(FileInputStream(file)).use { zip ->
            val entry = zip.nextEntry ?: return@use "unknown"
            if (entry.name != ".version") "unknown" else zip.bufferedReader().readText().trim()
        }

    fun installedAgentVersions(): Map<com.vayro.app.model.AgentKind, String> = buildMap {
        migrateLegacyClaudeMarker()
        claudeMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(com.vayro.app.model.AgentKind.CLAUDE_CODE) && it.matches(CLAUDE_VERSION_PATTERN) }
            ?.let { put(com.vayro.app.model.AgentKind.CLAUDE_CODE, it) }

        dshMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && guestExecutableFile("/usr/local/lib/dsh/node_modules/.bin/dsh") != null }
            ?.let { put(com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS, it) }

        agyMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && guestExecutableFile(AGY_GUEST_PATH) != null }
            ?.let { put(com.vayro.app.model.AgentKind.ANTIGRAVITY, it) }

        codexMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && guestExecutableFile(CODEX_GUEST_PATH) != null }
            ?.let { put(com.vayro.app.model.AgentKind.CODEX, it) }
    }

    /** Checks each installed agent against its own authoritative release source. */
    /**
     * QA Medium: an update check used to swallow per-agent fetch failures
     * ([runCatching] + [getOrNull]), so offline looked exactly like "all
     * installed agents are up to date" (empty map). Failures are now
     * reported alongside any updates that did come back, so the UI can
     * say "could not check" instead of "up to date".
     */
    data class AgentUpdateCheckResult(
        val updates: Map<com.vayro.app.model.AgentKind, AgentUpdateInfo> = emptyMap(),
        val failedChecks: List<com.vayro.app.model.AgentKind> = emptyList(),
    )

    suspend fun checkAgentUpdates(): AgentUpdateCheckResult {
        val installed = installedAgentVersions()
        val updates = mutableMapOf<com.vayro.app.model.AgentKind, AgentUpdateInfo>()
        val failed = mutableListOf<com.vayro.app.model.AgentKind>()
        installed[com.vayro.app.model.AgentKind.CLAUDE_CODE]?.let { current ->
            runCatching {
                JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
            }.onSuccess { latest ->
                if (isVersionNewer(latest, current)) updates[com.vayro.app.model.AgentKind.CLAUDE_CODE] = AgentUpdateInfo(current, latest)
            }.onFailure {
                failed += com.vayro.app.model.AgentKind.CLAUDE_CODE
            }
        }
        installed[com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS]?.let { current ->
            // DSH is pinned to this build's verified release (see updateDsh):
            // the only "update" ever offered is a restore of the pinned
            // bundle when the installed tree differs from it. npm latest is
            // deliberately NOT consulted — a newer upstream release is not
            // runnable by this build until a Kodex update re-pins it.
            if (current != DSH_VERSION) {
                updates[com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS] = AgentUpdateInfo(current, DSH_VERSION)
            }
        }
        installed[com.vayro.app.model.AgentKind.ANTIGRAVITY]?.let { current ->
            runCatching { fetchAgyManifest().getString("version") }
                .onSuccess { latest ->
                    if (isVersionNewer(latest, current)) updates[com.vayro.app.model.AgentKind.ANTIGRAVITY] = AgentUpdateInfo(current, latest)
                }
                .onFailure {
                    failed += com.vayro.app.model.AgentKind.ANTIGRAVITY
                }
        }
        installed[com.vayro.app.model.AgentKind.CODEX]?.let { current ->
            runCatching {
                JSONObject(fetchText("https://registry.npmjs.org/@openai/codex/latest")).getString("version")
            }.onSuccess { latest ->
                if (isVersionNewer(latest, current)) updates[com.vayro.app.model.AgentKind.CODEX] = AgentUpdateInfo(current, latest)
            }.onFailure {
                failed += com.vayro.app.model.AgentKind.CODEX
            }
        }
        return AgentUpdateCheckResult(updates, failed)
    }

    suspend fun updateAgent(
        agent: com.vayro.app.model.AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            com.vayro.app.model.AgentKind.CLAUDE_CODE -> updateClaude(runtime, expectedVersion, onProgress)
            com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS -> updateDsh(runtime, expectedVersion, onProgress)
            com.vayro.app.model.AgentKind.ANTIGRAVITY -> updateAgy(runtime, expectedVersion, onProgress)
            com.vayro.app.model.AgentKind.CODEX -> updateCodex(runtime, expectedVersion, onProgress)
            // Custom has no binary to update.
            com.vayro.app.model.AgentKind.CUSTOM -> Unit
        }
        onProgress(RuntimeInstallProgress("${agent.title} $expectedVersion is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    private suspend fun updateClaude(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
        check(latest == expectedVersion) { "A newer Claude Code release appeared. Check again before updating." }
        val base = "https://downloads.claude.ai/claude-code-releases/$latest"
        val manifest = JSONObject(fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val downloaded = File(downloads, "claude-$latest")
        downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading Claude Code $latest", ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        claude.parentFile?.mkdirs()
        val staged = File(claude.parentFile, ".claude-$latest.installing")
        downloaded.copyTo(staged, overwrite = true)
        Os.chmod(staged.absolutePath, 0b111101101)
        // Back up the live binary BEFORE replacing it: if verification fails
        // after the swap, the previous binary is restored instead of leaving
        // a broken agent behind.
        val backup = File(claude.parentFile, ".claude.previous")
        if (claude.exists()) claude.copyTo(backup, overwrite = true)
        try {
            Os.rename(staged.absolutePath, claude.absolutePath)
            downloaded.delete()
            verifyGuest(runtime.proot, "$CLAUDE_GUEST_PATH --version", "Claude Code update verification failed")
            backup.delete()
        } catch (e: Exception) {
            if (backup.exists()) runCatching { backup.copyTo(claude, overwrite = true) }
            throw e
        }
        claudeMarker.writeText(latest)
    }

    private suspend fun updateAgy(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val manifest = fetchAgyManifest()
        val latest = manifest.getString("version")
        check(latest == expectedVersion) { "A newer Antigravity release appeared. Check again before updating." }
        val downloaded = File(downloads, "antigravity-$latest-linux-arm64.tar.gz")
        downloadVerified(manifest.getString("url"), downloaded, manifest.getString("sha512"), algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading Antigravity CLI $latest", ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val destination = File(rootfs, AGY_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        var backup: File? = null
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                    val staged = File(destination.parentFile, ".agy-$latest.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    // Back up the live binary BEFORE replacing it (same
                    // reason as updateClaude: a failed verify must restore).
                    backup = File(destination.parentFile, ".agy.previous").also {
                        if (destination.exists()) destination.copyTo(it, overwrite = true)
                    }
                    try {
                        Os.rename(staged.absolutePath, destination.absolutePath)
                    } catch (e: Exception) {
                        val b = backup
                        if (b != null && b.exists()) runCatching { b.copyTo(destination, overwrite = true) }
                        throw e
                    }
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        downloaded.delete()
        check(found) { "Antigravity update archive is incomplete" }
        try {
            verifyGuest(runtime.proot, "$AGY_GUEST_PATH --version", "Antigravity update verification failed")
            backup?.delete()
        } catch (e: Exception) {
            val b = backup
            if (b != null && b.exists()) runCatching { b.copyTo(destination, overwrite = true) }
            throw e
        }
        agyMarker.writeText(latest)
    }

    private suspend fun updateDsh(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        // DeepSeek Harness is PINNED to the release this Kodex build is
        // verified against: the Android compatibility patch rewrites two
        // exact source files of that release, and the bridge's SDK protocol
        // is verified against it. "Updating" the harness therefore means
        // (re)installing the pinned bundle — never chasing npm latest.
        // (Field failure this prevents: npm latest moved to a refactored
        // release whose sources no longer match the patch; the old code
        // installed it, rewrote the version marker, and only then failed
        // the compatibility check — bricking every Work session with no
        // update left to offer, because the marker already claimed the
        // newest release.)
        check(expectedVersion == DSH_VERSION) {
            "DeepSeek Harness $expectedVersion is not supported by this Kodex build. " +
                "This build uses DeepSeek Harness $DSH_VERSION; harness updates arrive with Kodex app updates. " +
                "The installed version was kept."
        }
        reinstallPinnedDsh(runtime.proot, 0.05f, 0.95f, onProgress)
    }

    private suspend fun updateCodex(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@openai/codex/latest")).getString("version")
        check(latest == expectedVersion) { "A newer Codex CLI release appeared. Check again before updating." }
        val quotedVersion = latest.replace(Regex("[^0-9A-Za-z.+-]"), "")
        check(quotedVersion == latest) { "Invalid Codex CLI version" }
        // npm replaces in place: a failed install or a failed verify does NOT
        // preserve the old version, so remember it for an explicit rollback.
        val previousVersion = runCatching { installedCodexVersion(runtime.proot) }.getOrNull().orEmpty()
        try {
            runGuestCommand(
                proot = runtime.proot,
                command = "npm install -g @openai/codex@$quotedVersion",
                displayCommand = "npm install -g @openai/codex@$quotedVersion",
                fraction = 0.55f,
                timeoutMs = 20 * 60 * 1_000L,
                onProgress = onProgress,
                failureMessage = "Codex CLI update failed",
            )
            verifyGuest(runtime.proot, "$CODEX_GUEST_PATH --version", "Codex CLI update verification failed")
        } catch (e: Exception) {
            if (previousVersion.isNotBlank()) {
                // Best effort: the original error is what surfaces even if
                // the rollback itself fails.
                runCatching {
                    runGuestCommand(
                        proot = runtime.proot,
                        command = "npm install -g @openai/codex@$previousVersion",
                        displayCommand = "npm install -g @openai/codex@$previousVersion",
                        fraction = 0.9f,
                        timeoutMs = 20 * 60 * 1_000L,
                        onProgress = onProgress,
                        failureMessage = "Codex CLI rollback failed",
                    )
                }
            }
            throw e
        }
        codexMarker.writeText(installedCodexVersion(runtime.proot))
    }

    private fun fetchAgyManifest(): JSONObject = JSONObject(
        fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
    )
    private data class PublishedRuntimeAsset(
        val version: String,
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    private suspend fun latestRuntimeBundle(id: String, expectedVersion: String? = null): RuntimeBundle? {
        val fallbackBundle = runCatching {
            val manifest = context.assets.open("runtime/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            val item = manifest.optJSONObject("bundles")?.optJSONObject(id) ?: manifest.optJSONObject(id)
            if (item != null) {
                RuntimeBundle(
                    label = when (id) {
                        "core" -> "Core"
                        "python" -> "Python"
                        "android" -> "Android"
                        else -> id
                    },
                    fileName = item.getString("file"),
                    sha256 = item.getString("sha256"),
                    compressedBytes = item.optLong("compressedBytes", item.optLong("sizeBytes", 0L)),
                )
            } else null
        }.getOrNull() ?: when (id) {
            "python" -> PYTHON_BUNDLE
            "android" -> ANDROID_BUNDLE
            "core" -> CORE_BUNDLE
            else -> null
        }

        val cleanExpected = expectedVersion?.substringBefore(" ")?.trim()

        if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            return if (cleanExpected == null || fallbackBundle?.version == cleanExpected) fallbackBundle else null
        }

        val prefix = when (id) {
            "core" -> "conzyx-core-arm64-"
            "python" -> "conzyx-python-arm64-"
            "android" -> "conzyx-android-arm64-"
            else -> return null
        }

        val releases = runCatching {
            JSONArray(fetchText("https://api.github.com/repos/mazharmnzoor4227-beep/vayro/releases?per_page=30"))
        }.getOrNull()

        if (releases != null) {
            val assets = buildList {
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                    val array = release.optJSONArray("assets") ?: continue
                    for (j in 0 until array.length()) {
                        val asset = array.getJSONObject(j)
                        val name = asset.optString("name")
                        if (!name.startsWith(prefix) || !name.endsWith(".tar.zst")) continue
                        val version = name.removePrefix(prefix).removeSuffix(".tar.zst")
                        val digest = asset.optString("digest").removePrefix("sha256:")
                        if (version.isNotBlank() && digest.matches(Regex("[0-9a-fA-F]{64}"))) {
                            add(PublishedRuntimeAsset(version, name, asset.optString("browser_download_url"), digest, asset.optLong("size", 0L)))
                        }
                    }
                }
            }
            val selected = if (cleanExpected != null) {
                assets.firstOrNull { it.version == cleanExpected }
            } else {
                assets.maxWithOrNull(Comparator { left, right -> compareVersions(left.version, right.version) })
            }
            if (selected != null) {
                return RuntimeBundle(
                    label = when (id) {
                        "core" -> "Core"
                        "python" -> "Python"
                        else -> "Android"
                    },
                    fileName = selected.fileName,
                    sha256 = selected.sha256,
                    compressedBytes = selected.sizeBytes,
                    remoteUrl = selected.url,
                )
            }
        }

        return if (cleanExpected == null || fallbackBundle?.version == cleanExpected) fallbackBundle else null
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = versionParts(left)
        val b = versionParts(right)
        repeat(maxOf(a.size, b.size)) { index ->
            val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return left.compareTo(right)
    }

    private fun versionParts(value: String): List<Int> =
        Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()


    private fun isVersionNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val left = parts(candidate)
        val right = parts(current)
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return candidate != current && !candidate.contains("alpha", true) && !candidate.contains("rc", true)
    }

    /**
     * Older Core bundles stored Claude Code and its version in Core-owned markers.
     * Preserve that verified installation when upgrading the app, while all fresh
     * installs use the independent Claude overlay and marker.
     */
    private fun migrateLegacyClaudeMarker() {
        if (claudeMarker.isFile) return
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        if (!claude.isFile) return
        val legacyVersion = sequenceOf(
            coreReadyMarker.readTextOrNull(),
            bundledClaudeMarker.readTextOrNull(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.matches(CLAUDE_VERSION_PATTERN) } ?: return
        claudeMarker.writeText(legacyVersion)
    }

    private fun isSupportedCoreToolsVersion(): Boolean = coreToolsMarker.readTextOrNull() in setOf(
        CORE_TOOLS_VERSION,
        LEGACY_CORE_TOOLS_VERSION,
    )

    private suspend fun ensureClaudeInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        migrateLegacyClaudeMarker()
        if (isAgentInstalled(com.vayro.app.model.AgentKind.CLAUDE_CODE)) return
        installRuntimeOverlay(
            bundle = CLAUDE_BUNDLE,
            message = "Installing Claude Code $CLAUDE_BUNDLED_VERSION",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        verifyGuest(proot, "$CLAUDE_GUEST_PATH --version", "Claude Code verification failed")
        claudeMarker.writeText(CLAUDE_BUNDLED_VERSION)
        require(isAgentInstalled(com.vayro.app.model.AgentKind.CLAUDE_CODE)) {
            "The Claude Code runtime bundle is incomplete"
        }
    }

    private suspend fun ensureAgyInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.vayro.app.model.AgentKind.ANTIGRAVITY)) return
        installRuntimeOverlay(
            bundle = AGY_BUNDLE,
            message = "Installing Antigravity CLI $AGY_VERSION",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
            forceEmbedded = true,
        )
        verifyGuest(proot, "$AGY_GUEST_PATH --version", "Antigravity CLI verification failed")
        agyMarker.writeText(AGY_VERSION)
        require(isAgentInstalled(com.vayro.app.model.AgentKind.ANTIGRAVITY)) {
            "Antigravity CLI installation is incomplete"
        }
    }

    private suspend fun ensureDshInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS)) {
            if (dshCompatibilityAssessment().isReady(DSH_VERSION)) {
                ensureDshAndroidCompatibility()
            } else {
                // A tree is installed but it is not the pinned release this
                // build is verified with (e.g. an npm-latest install whose
                // sources the Android patch cannot verify). Restore the
                // pinned bundle instead of failing every session.
                reinstallPinnedDsh(proot, fraction, 0.995f, onProgress)
            }
            return
        }
        installRuntimeOverlay(
            bundle = DSH_BUNDLE,
            message = "Installing DeepSeek Harness $DSH_VERSION",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        ensureDshAndroidCompatibility()
        verifyGuest(proot, "/usr/local/bin/dsh --profile headless --help", "DeepSeek Harness verification failed")
        require(isAgentInstalled(com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS)) {
            "The DeepSeek Harness runtime bundle is incomplete"
        }
    }

    /**
     * Installs the official Codex CLI with npm. Node.js ships in the Core
     * runtime, so npm is always available when an agent install runs. npm's
     * global bin dir for the tarball Node install is /usr/local/lib/nodejs/bin,
     * so the codex binary is symlinked to the stable [CODEX_GUEST_PATH].
     */
    private suspend fun ensureCodexInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.vayro.app.model.AgentKind.CODEX)) return
        runGuestCommand(
            proot = proot,
            command = "npm install -g @openai/codex",
            displayCommand = "npm install -g @openai/codex",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Codex CLI installation failed",
        )
        val npmBin = File(rootfs, "usr/local/lib/nodejs/bin/codex")
        check(npmBin.isFile) { "Codex CLI installation did not produce a codex binary" }
        val destination = File(rootfs, CODEX_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        if (destination.exists() || java.nio.file.Files.isSymbolicLink(destination.toPath())) destination.delete()
        Os.symlink("../lib/nodejs/bin/codex", destination.absolutePath)
        verifyGuest(proot, "$CODEX_GUEST_PATH --version", "Codex CLI verification failed")
        codexMarker.writeText(installedCodexVersion(proot))
        require(isAgentInstalled(com.vayro.app.model.AgentKind.CODEX)) {
            "Codex CLI installation is incomplete"
        }
    }

    /** Reads the actually installed @openai/codex version from npm, never the registry latest. */
    private suspend fun installedCodexVersion(proot: File): String {
        val output = runGuest(proot, "npm list -g @openai/codex --depth=0 --json")
        return JSONObject(output)
            .getJSONObject("dependencies")
            .getJSONObject("@openai/codex")
            .getString("version")
    }

    /**
     * DSH uses POSIX hard links for no-clobber publication of new session and
     * workspace files. Android blocks that syscall inside PRoot. PRoot's
     * `--link2symlink` workaround is unsuitable here because DSH immediately
     * deletes its staging file, leaving the published symlink dangling.
     *
     * The bundled, pinned DSH build can use COPYFILE_EXCL for the same
     * no-clobber guarantee. Existing-file edits continue to use atomic rename.
     */
    fun ensureDshAndroidCompatibility() {
        if (!isAgentInstalled(com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS)) return
        patchDshHardLinkPublication(DshCompatibility.PERSISTENCE_SPEC)
        patchDshHardLinkPublication(DshCompatibility.FS_LOCAL_SPEC)
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    /**
     * Classifies the installed harness tree against the pinned release this
     * build is verified with (see [DshCompatibility]). Pure file inspection:
     * no guest process, no network — safe to run before every session launch.
     */
    fun dshCompatibilityAssessment(): DshCompatibilityAssessment = DshCompatibility.assess(
        installedVersion = dshMarker.readTextOrNull(),
        persistenceSource = File(rootfs, DshCompatibility.PERSISTENCE_SPEC.guestPath).readTextOrNull(),
        fsLocalSource = File(rootfs, DshCompatibility.FS_LOCAL_SPEC.guestPath).readTextOrNull(),
    )

    /**
     * Restores the pinned DeepSeek Harness from its verified bundle,
     * replacing whatever tree is currently installed (a foreign npm install,
     * a partial update, a damaged tree). The bundle carries its own version
     * marker, so a successful repair always lands on the pinned release with
     * the Android compatibility patch applied. DSH home data (settings,
     * sessions) lives outside the program tree and is preserved.
     */
    suspend fun repairDsh(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        val runtime = installedRuntime()
        reinstallPinnedDsh(runtime.proot, 0.05f, 0.95f, onProgress)
    }

    private suspend fun reinstallPinnedDsh(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        // Wipe the old program tree first: a bundle overlay extract alone
        // would leave foreign files behind (e.g. packages the pinned
        // release does not ship).
        File(rootfs, "usr/local/lib/dsh").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.updating").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        installRuntimeOverlay(
            bundle = DSH_BUNDLE,
            message = "Restoring DeepSeek Harness $DSH_VERSION",
            from = from,
            to = to,
            onProgress = onProgress,
        )
        ensureDshAndroidCompatibility()
        verifyGuest(proot, "/usr/local/bin/dsh --profile headless --help", "DeepSeek Harness repair verification failed")
        require(isAgentInstalled(com.vayro.app.model.AgentKind.DEEPSEEK_HARNESS)) {
            "The DeepSeek Harness repair is incomplete"
        }
    }

    private fun patchDshHardLinkPublication(spec: DshFilePatchSpec) {
        val file = File(rootfs, spec.guestPath)
        check(file.isFile) { "DeepSeek Harness compatibility file is missing: ${file.name}" }
        var source = file.readText()
        when (DshCompatibility.patchStateOf(source, spec)) {
            DshPatchState.PATCHED -> return
            DshPatchState.PATCHABLE -> Unit
            else -> throw IllegalStateException(
                "DeepSeek Harness $DSH_VERSION is not compatible with this Kodex build"
            )
        }
        source = source.replace(spec.importBefore, spec.importAfter).replace(spec.callBefore, spec.callAfter)
        file.writeText(source)
    }

    /**
     * Installs one optional development stack inside Ubuntu. Safe to call again:
     * already-installed stacks return immediately without network access.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        if (isStackInstalled(stack)) return
        applyStack(runtime.proot, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress("${stack.label} tools are ready", 1f))
    }

    /**
     * Updates an installed optional development stack in place.
     * Bundled stacks use the verified runtime asset; package-backed stacks
     * refresh APT metadata and install the current packages without removing
     * the existing toolchain first.
     */
    suspend fun updateStack(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(stack != DevStack.WEB) { "Web tools are part of the core runtime and cannot be updated separately" }
        check(isStackInstalled(stack)) { stack.label + " is not installed" }
        val runtime = installedRuntime()
        when (stack) {
            DevStack.PYTHON -> {
                onProgress(RuntimeInstallProgress("Checking and updating Python environment", 0.1f))
                val targetVersion = latestRuntimeBundle("python")?.version ?: ENV_PYTHON_VERSION
                val currentVer = File(rootfs, ".pocket-python-tools-version").readTextOrNull()?.trim()
                val currentClean = currentVer?.substringBefore(" ")?.trim()
                if (currentClean != targetVersion) {
                    val bundle = latestRuntimeBundle("python", targetVersion)
                    if (bundle != null) {
                        installRuntimeOverlay(bundle, "Installing Python runtime", 0.1f, 0.6f, onProgress)
                    }
                }
                onProgress(RuntimeInstallProgress("Updating pip, setuptools, and wheel from official PyPI", 0.65f))
                val pipUpgradeOk = runCatching {
                    runGuestExitCode(runtime.proot, "python3 -m pip install --upgrade --no-warn-script-location pip setuptools wheel", 120_000L) == 0
                }.getOrDefault(false)
                verifyGuest(runtime.proot, "python3 --version && pip3 --version", "Python tools could not be verified")
                val pipVer = runCatching {
                    runGuest(runtime.proot, "pip3 --version").split(" ").getOrNull(1)
                }.getOrNull()?.takeIf { it.isNotBlank() }
                // QA Medium: the installed pip version used to be computed
                // then discarded. Same "(pip …)" format as install above.
                val finalVer = if (pipVer != null) "${targetVersion.substringBefore(" ").trim()} (pip $pipVer)" else targetVersion
                if (pipUpgradeOk) {
                    File(rootfs, ".pocket-python-tools-version").writeText(finalVer)
                } else {
                    onProgress(RuntimeInstallProgress("pip upgrade did not complete; keeping the previous version marker so the update is retried later", 0.65f))
                }
                onProgress(RuntimeInstallProgress("Python tools updated ($finalVer)", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            DevStack.ANDROID -> {
                val targetVersion = latestRuntimeBundle("android")?.version ?: ENV_ANDROID_VERSION
                val currentVer = File(rootfs, "root/.pocket-android-tools-version").readTextOrNull()?.trim()
                val currentClean = currentVer?.substringBefore(" ")?.trim()
                if (currentClean != targetVersion) {
                    installEnvironmentStack("android", targetVersion, onProgress)
                } else {
                    onProgress(RuntimeInstallProgress("Android tools are up to date ($targetVersion)", 1f, event = RuntimeInstallEvent.COMPLETED))
                }
            }
            DevStack.CPP -> {
                onProgress(RuntimeInstallProgress("Updating C/C++ compilers and build tools from Ubuntu repositories", 0.1f))
                aptInstall(
                    runtime.proot,
                    listOf("build-essential", "cmake", "gdb", "gcc", "g++", "make"),
                    "Updating C/C++ compilers and build tools",
                    0.2f,
                    onProgress,
                )
                verifyGuest(runtime.proot, "gcc --version && g++ --version && make --version && cmake --version", "C/C++ tools could not be verified")
                val gccVer = runCatching {
                    runGuest(runtime.proot, "gcc -dumpversion")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "10.2"
                File(rootfs, ".pocket-cpp-tools-version").writeText(gccVer)
                onProgress(RuntimeInstallProgress("C/C++ tools updated (GCC $gccVer)", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            DevStack.PHP -> {
                onProgress(RuntimeInstallProgress("Updating PHP environment from official repositories", 0.1f))
                runCatching { preparePhpRepository(runtime.proot, onProgress) }.onFailure {
                    File(rootfs, "etc/apt/sources.list.d/ondrej-ubuntu-php-focal.list").delete()
                }
                val ppaFile = File(rootfs, "etc/apt/sources.list.d/ondrej-ubuntu-php-focal.list")
                val hasPpa = ppaFile.isFile
                val packages = if (hasPpa) {
                    listOf("php8.4-cli", "php8.4-mbstring", "php8.4-xml", "php8.4-curl", "php8.4-zip", "unzip")
                } else {
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip")
                }
                val installResult = runCatching {
                    aptInstall(
                        runtime.proot,
                        packages,
                        "Updating PHP and common extensions",
                        0.2f,
                        onProgress,
                    )
                }
                if (installResult.isFailure && hasPpa) {
                    ppaFile.delete()
                    aptInstall(
                        runtime.proot,
                        listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                        "Updating PHP (Ubuntu repository)",
                        0.2f,
                        onProgress,
                    )
                } else if (installResult.isFailure) {
                    throw installResult.exceptionOrNull() ?: IllegalStateException("Could not update PHP")
                }
                runCatching { installComposer(runtime.proot, 0.70f, onProgress, force = true) }
                onProgress(RuntimeInstallProgress("Self-updating Composer to official latest release", 0.85f))
                runCatching {
                    runGuest(runtime.proot, "composer self-update --2 --no-interaction || true", 120_000L)
                }
                verifyGuest(runtime.proot, "php --version || php8.4 --version || php7.4 --version", "PHP tools could not be verified")
                val composerVer = runCatching {
                    runGuest(runtime.proot, "composer --version").split(" ").getOrNull(2)
                }.getOrNull()
                val phpVer = if (composerVer != null) "8.4 (Composer $composerVer)" else "8.4"
                File(rootfs, ".pocket-php-tools-version").writeText(phpVer)
                onProgress(RuntimeInstallProgress("PHP and Composer updated to latest ($phpVer)", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            DevStack.BROWSER -> {
                onProgress(RuntimeInstallProgress("Updating Chromium and Puppeteer tools", 0.1f))
                aptInstall(
                    runtime.proot,
                    listOf("chromium-browser", "fonts-liberation", "fonts-noto-color-emoji"),
                    "Updating Chromium browser packages",
                    0.2f,
                    onProgress,
                )
                onProgress(RuntimeInstallProgress("Updating Puppeteer automation core via npm", 0.6f))
                runCatching {
                    runGuest(runtime.proot, "npm install -g puppeteer-core@latest", 180_000L)
                }
                installPocketBrowserScript()
                val puppetVer = runCatching {
                    runGuest(runtime.proot, "node -e 'try { console.log(require(require(\"child_process\").execSync(\"npm root -g\").toString().trim() + \"/puppeteer-core/package.json\").version); } catch { console.log(\"latest\"); }'")
                }.getOrNull()?.trim() ?: "latest"
                File(rootfs, ".pocket-browser-tools-version").writeText(puppetVer)
                onProgress(RuntimeInstallProgress("Browser automation tools updated (v$puppetVer)", 1f, event = RuntimeInstallEvent.COMPLETED))
            }
            DevStack.WEB -> error("Web tools are part of the core runtime")
        }
    }

    /**
     * Removes an optional development stack without touching projects or the core
     * Node.js/Git runtime. Package-backed stacks are purged through dpkg; bundled
     * stacks remove only their dedicated SDK/language directories.
     */
    suspend fun removeStack(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(stack != DevStack.WEB) { "Web tools are part of the core runtime and cannot be removed" }
        val runtime = installedRuntime()
        if (!isStackInstalled(stack)) return

        onProgress(RuntimeInstallProgress("Removing ${stack.label} tools", 0.1f, indeterminate = true))
        when (stack) {
            DevStack.WEB -> Unit
            DevStack.PYTHON -> removePythonStack()
            DevStack.ANDROID -> removeAndroidStack()
            DevStack.CPP -> aptRemove(
                runtime.proot,
                listOf("build-essential", "gcc", "g++", "make", "cmake", "gdb"),
                0.45f,
                onProgress,
            )
            DevStack.PHP -> {
                aptRemove(
                    runtime.proot,
                    listOf("php8.4-cli", "php8.4-mbstring", "php8.4-xml", "php8.4-curl", "php8.4-zip"),
                    0.45f,
                    onProgress,
                )
                removePath(File(rootfs, "usr/local/bin/composer"))
                removePath(File(rootfs, "root/.cache/composer"))
                removePath(File(rootfs, "root/.composer"))
            }
            DevStack.BROWSER -> {
                runCatching { runGuest(runtime.proot, "npm uninstall -g puppeteer-core || true", 60_000L) }
                aptRemove(
                    runtime.proot,
                    listOf("chromium-browser", "fonts-liberation", "fonts-noto-color-emoji"),
                    0.45f,
                    onProgress,
                )
                removePath(File(rootfs, "usr/local/bin/pocket-browser"))
                removePath(File(rootfs, ".pocket-browser-tools-version"))
            }
        }

        writeDevStackState(readDevStackState().apply { put(stack.name, false) })
        onProgress(RuntimeInstallProgress("${stack.label} removed", 1f))
    }

    private fun removePythonStack() {
        listOf(
            ".pocket-python-tools-version",
            "usr/bin/python3",
            "usr/bin/python3.8",
            "usr/bin/pip",
            "usr/bin/pip3",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1.0",
            "usr/lib/python3",
            "usr/lib/python3.8",
            "usr/local/lib/python3.8",
            "usr/share/python3",
            "usr/share/python-wheels",
            "root/.cache/pip",
        ).forEach { removePath(File(rootfs, it)) }
    }

    private fun removeAndroidStack() {
        listOf(
            "root/android-sdk",
            "root/maven",
            "root/.pocket-android-tools-version",
            "root/.gradle/caches",
            "root/.gradle/daemon",
            "root/.gradle/native",
            "root/.gradle/notifications",
            "root/.gradle/wrapper/dists",
            "root/.gradle/init.d/pocketdev-android.gradle",
            "opt/gradle",
            "opt/jdk-17.0.20.1+1",
            "usr/local/bin/jar",
            "usr/local/bin/jarsigner",
            "usr/local/bin/java",
            "usr/local/bin/javac",
            "usr/local/bin/javadoc",
            "usr/local/bin/keytool",
        ).forEach { removePath(File(rootfs, it)) }
        removeAndroidGradleProperty()
    }

    private fun removeAndroidGradleProperty() {
        val properties = File(rootfs, "root/.gradle/gradle.properties")
        if (!properties.isFile) return
        val propertyPattern = Regex("^\\s*${Regex.escape(ANDROID_AAPT2_PROPERTY)}\\s*[:=].*$")
        val remaining = properties.readLines().filterNot { propertyPattern.matches(it) }
        if (remaining.isEmpty()) {
            properties.delete()
        } else {
            properties.writeText(remaining.joinToString("\n").trimEnd() + "\n")
        }
    }

    private fun removePath(file: File) {
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            check(file.deleteRecursively()) { "Could not remove ${file.name}" }
        } else if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            check(file.delete()) { "Could not remove ${file.name}" }
        }
    }

    private suspend fun applyStack(
        proot: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        var verified = true
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress("Checking Node.js and npm", from))
                verifyGuest(proot, "node --version && npm --version", "Node.js tools could not be verified")
            }
            DevStack.PYTHON -> {
                installRuntimeOverlay(PYTHON_BUNDLE, "Installing Python, pip, and venv", from, to, onProgress)
                runCatching {
                    verifyGuest(proot, "python3 --version && pip3 --version", "Python tools could not be verified")
                }.onFailure { error ->
                    verified = false
                    onProgress(
                        RuntimeInstallProgress(
                            "Python verify failed (will retry on next launch): ${error.message?.take(120)}",
                            to,
                        ),
                    )
                }
                if (verified) File(rootfs, ".pocket-python-tools-version").writeText(PYTHON_BUNDLE.version)
            }
            DevStack.ANDROID -> {
                installAndroidToolchain(proot, from, to, onProgress)
            }
            DevStack.CPP -> {
                if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
                    onProgress(RuntimeInstallProgress("C/C++ tools are online-only (skipped in offline APK)", to))
                    return
                }
                aptInstall(
                    proot,
                    listOf("build-essential", "cmake", "gdb"),
                    "Installing C/C++ compilers and build tools",
                    from,
                    onProgress,
                )
                verifyGuest(
                    proot,
                    "gcc --version && g++ --version && make --version && cmake --version",
                    "C/C++ tools could not be verified",
                )
                if (verified) File(rootfs, ".pocket-cpp-tools-version").writeText("10.2")
            }
            DevStack.PHP -> {
                if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
                    onProgress(RuntimeInstallProgress("PHP tools are online-only (skipped in offline APK)", to))
                    return
                }
                runCatching { preparePhpRepository(proot, onProgress) }.onFailure {
                    File(rootfs, "etc/apt/sources.list.d/ondrej-ubuntu-php-focal.list").delete()
                }
                val ppaFile = File(rootfs, "etc/apt/sources.list.d/ondrej-ubuntu-php-focal.list")
                val hasPpa = ppaFile.isFile
                val packages = if (hasPpa) {
                    listOf("php8.4-cli", "php8.4-mbstring", "php8.4-xml", "php8.4-curl", "php8.4-zip", "unzip")
                } else {
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip")
                }
                val installResult = runCatching {
                    aptInstall(
                        proot,
                        packages,
                        "Installing PHP and common extensions",
                        from,
                        onProgress,
                    )
                }
                if (installResult.isFailure && hasPpa) {
                    ppaFile.delete()
                    aptInstall(
                        proot,
                        listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                        "Installing PHP (Ubuntu repository)",
                        from,
                        onProgress,
                    )
                } else if (installResult.isFailure) {
                    throw installResult.exceptionOrNull() ?: IllegalStateException("Could not install PHP")
                }
                runCatching { installComposer(proot, from, onProgress) }
                verifyGuest(proot, "php --version || php8.4 --version || php7.4 --version", "PHP tools could not be verified")
                if (verified) File(rootfs, ".pocket-php-tools-version").writeText("8.4")
            }
            DevStack.BROWSER -> {
                if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
                    onProgress(RuntimeInstallProgress("Browser tools are online-only (skipped in offline APK)", to))
                    return
                }
                aptInstall(
                    proot,
                    listOf("chromium-browser", "fonts-liberation", "fonts-noto-color-emoji"),
                    "Installing Chromium browser and fonts",
                    from,
                    onProgress,
                )
                onProgress(RuntimeInstallProgress("Installing Puppeteer automation core", (from + to) / 2f))
                runCatching {
                    runGuest(proot, "npm install -g puppeteer-core", 180_000L)
                }
                installPocketBrowserScript()
                verifyGuest(
                    proot,
                    "chromium-browser --version || chromium --version || test -f /usr/local/bin/pocket-browser",
                    "Chromium tools could not be verified",
                )
                val puppetVer = runCatching {
                    runGuest(proot, "node -e 'try { console.log(require(require(\"child_process\").execSync(\"npm root -g\").toString().trim() + \"/puppeteer-core/package.json\").version); } catch { console.log(\"1.0\"); }'")
                }.getOrNull()?.trim() ?: "1.0"
                if (verified) File(rootfs, ".pocket-browser-tools-version").writeText(puppetVer)
            }
        }
        if (!verified) return
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress("${stack.label} installed", to))
    }

    private suspend fun preparePhpRepository(
        proot: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val listFile = File(rootfs, "etc/apt/sources.list.d/ondrej-ubuntu-php-focal.list")
        if (listFile.isFile) return
        onProgress(RuntimeInstallProgress("Configuring PHP repository", 0.15f))
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends software-properties-common ca-certificates gnupg curl && " +
            "mkdir -p /etc/apt/trusted.gpg.d && " +
            "(curl -fsSL --connect-timeout 15 'https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0x14AA40EC0831756756D7F66C4F4EA0AAE5267A6C' | gpg --dearmor -o /etc/apt/trusted.gpg.d/ondrej-php.gpg 2>/dev/null || " +
            "add-apt-repository -y ppa:ondrej/php 2>/dev/null || true); " +
            "if [ -f /etc/apt/trusted.gpg.d/ondrej-php.gpg ]; then " +
            "echo 'deb https://ppa.launchpad.net/ondrej/php/ubuntu focal main' > /etc/apt/sources.list.d/ondrej-ubuntu-php-focal.list && " +
            "apt-get -o DPkg::Lock::Timeout=120 update; fi"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "Configuring PHP repository and updating package list",
            fraction = 0.15f,
            timeoutMs = 15 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not configure PHP package repository",
        )
    }

    /**
     * Installs Composer into Ubuntu from the official latest-stable release,
     * verified against getcomposer.org's published SHA-256 checksum.
     */
    private suspend fun installComposer(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        force: Boolean = false,
    ) {
        val composer = File(rootfs, "usr/local/bin/composer")
        if (composer.isFile && !force) return
        onProgress(RuntimeInstallProgress("Downloading Composer", fraction))
        downloads.mkdirs()
        val staged = File(downloads, "composer.phar")
        val checksumResult = runCatching {
            fetchText("https://getcomposer.org/download/latest-stable/composer.phar.sha256sum")
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.substringBefore(' ')
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        }
        val checksum = checksumResult.getOrNull()
        if (checksum == null) {
            if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) return
            error("Composer checksum was not found")
        }
        downloadVerified(
            "https://getcomposer.org/download/latest-stable/composer.phar",
            staged,
            checksum,
        ) { _, _ -> }
        composer.parentFile?.mkdirs()
        if (composer.exists()) composer.delete()
        // cacheDir and filesDir can live on different mounts: copy instead of rename.
        staged.inputStream().use { input -> FileOutputStream(composer).use { input.copyTo(it) } }
        staged.delete()
        Os.chmod(composer.absolutePath, 0b111101101)
        onProgress(RuntimeInstallProgress("Installing Composer", fraction))
    }

    private suspend fun installAndroidToolchain(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        bundleOverride: RuntimeBundle? = null,
    ) {
        val marker = File(rootfs, "root/.pocket-android-tools-version")
        val androidHome = File(rootfs, "root/android-sdk")
        val gradleHome = File(rootfs, "opt/gradle")
        val localMaven = File(rootfs, "root/maven/localMvnRepository")
        val expectedBundleVersion = bundleOverride?.version ?: ANDROID_TOOLS_VERSION
        if (marker.readTextOrNull() != expectedBundleVersion ||
            !hasAndroidToolchain(androidHome, gradleHome, localMaven)) {
            installRuntimeOverlay(
                bundleOverride ?: ANDROID_BUNDLE,
                "Installing the Android development tools",
                from,
                to,
                onProgress,
            )
            makeAndroidToolsExecutable(androidHome, gradleHome)
            marker.parentFile?.mkdirs()
            marker.writeText(expectedBundleVersion)
        }
        // Keep this outside the download/install branch so app updates repair
        // existing Android toolchains without downloading the bundles again.
        writeAndroidGradleConfiguration(rootfs)

        verifyGuest(
            proot,
            "java -version 2>&1 | grep -E '\"17\\.|version 17' && " +
                "gradle --version && aapt2 version && test -f \"${'$'}ANDROID_HOME/platforms/android-36/android.jar\"",
            "Android SDK, Gradle, or Java could not be verified",
        )
    }

    private suspend fun installRuntimeOverlay(
        bundle: RuntimeBundle,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        forceEmbedded: Boolean = false,
    ) {
        // Stack overlays honor the same offline/online flavor as the Core bundle:
        // the offline APK ships every stack bundle inside its assets, while the
        // online APK fetches each one from the release URL on demand.
        val archive = obtainRuntimeBundle(bundle, preferEmbedded = forceEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES, from, to * 0.8f + from * 0.2f, onProgress)
        onProgress(RuntimeInstallProgress(message, to * 0.8f + from * 0.2f, indeterminate = true))
        withTimeout(EXTRACTION_TIMEOUT_MS) { extractZstdTar(archive, rootfs) }
        stripMacosMetadataArtifacts(rootfs)
        if (archive.parentFile == downloads) archive.delete()
        onProgress(RuntimeInstallProgress("${bundle.label} tools installed", to))
    }

    /**
     * Removes AppleDouble-style metadata files (`._*`) that some on-device
     * tar builders and macOS tarballs embed alongside the real files. They
     * are harmless to most tools, but Python's site module reads every
     * `.pth` file in site-packages and crashes when one of them is a
     * binary metadata blob.
     */
    private fun stripMacosMetadataArtifacts(root: File) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(root)
        var removed = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith("._")) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                    removed++
                } else if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    queue.add(child)
                }
            }
        }
        if (removed > 0) {
            android.util.Log.i("RuntimeInstaller", "Stripped $removed macOS metadata artifacts")
        }
    }

    private suspend fun obtainRuntimeBundle(
        bundle: RuntimeBundle,
        preferEmbedded: Boolean,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): File {
        downloads.mkdirs()
        val destination = File(downloads, bundle.fileName)
        val embeddedAvailable = runCatching {
            context.assets.open("runtime/" + bundle.fileName).use { }
            true
        }.getOrDefault(false)
        val requestedEmbedded = preferEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES
        if (requestedEmbedded && !embeddedAvailable && BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            error(bundle.label + " " + bundle.fileName + " is not bundled in this offline APK")
        }
        val useEmbedded = requestedEmbedded && embeddedAvailable
        if (useEmbedded) {
            onProgress(RuntimeInstallProgress("Loading ${bundle.label} bundle", from, 0, bundle.compressedBytes))
            val temporary = File(downloads, "${bundle.fileName}.part")
            context.assets.open("runtime/${bundle.fileName}").use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        check(copied <= MAX_RUNTIME_ARCHIVE_BYTES) { "Embedded bundle exceeds the maximum archive size" }
                        val ratio = (copied.toFloat() / bundle.compressedBytes).coerceIn(0f, 1f)
                        onProgress(RuntimeInstallProgress("Loading ${bundle.label} bundle", from + ratio * (to - from), copied, bundle.compressedBytes))
                    }
                }
            }
            require(digest(temporary, "SHA-256").equals(bundle.sha256, ignoreCase = true)) {
                "${bundle.label} bundle checksum mismatch"
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Could not stage the ${bundle.label} bundle" }
            return destination
        }

        val url = bundle.remoteUrl ?: "${BuildConfig.RUNTIME_RELEASE_BASE_URL}/${bundle.fileName}"
        downloadVerified(url, destination, bundle.sha256) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading ${bundle.label} bundle", from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        return destination
    }


    private fun extractZipArchive(archive: File, destination: File) {
        checkArchiveSize(archive)
        checkDiskSpaceForExtraction(destination, archive)
        val budget = RuntimeExtractionBudget()
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                budget.checkEntry()
                val name = entry.name.removePrefix("./").trimStart('/')
                if (name.isNotBlank()) {
                    val target = safeChild(destination, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> budget.copyBounded(zip, output) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun latestVersionedDirectory(parent: File, prefix: String, required: (File) -> Boolean): File? =
        parent.listFiles { file -> file.isDirectory && file.name.startsWith(prefix) }
            .orEmpty()
            .filter(required)
            .maxWithOrNull(Comparator { left, right ->
                val l = versionParts(left.name.removePrefix(prefix))
                val r = versionParts(right.name.removePrefix(prefix))
                repeat(maxOf(l.size, r.size)) { index ->
                    val c = (l.getOrElse(index) { 0 }).compareTo(r.getOrElse(index) { 0 })
                    if (c != 0) return@Comparator c
                }
                left.name.compareTo(right.name)
            })

    private fun latestBuildToolsDir(androidHome: File): File? =
        latestVersionedDirectory(File(androidHome, "build-tools"), "", { File(it, "aapt2").isFile })

    private fun latestGradleDir(gradleHome: File): File? =
        latestVersionedDirectory(gradleHome, "gradle-", { File(it, "bin/gradle").isFile })

    private fun hasAndroidToolchain(
        androidHome: File,
        gradleHome: File,
        localMaven: File,
    ): Boolean {
        val platformReady = File(androidHome, "platforms").listFiles { file ->
            file.isDirectory && file.name.startsWith("android-") && File(file, "android.jar").isFile
        }.orEmpty().isNotEmpty()
        return platformReady &&
            latestBuildToolsDir(androidHome) != null &&
            latestGradleDir(gradleHome) != null &&
            localMaven.isDirectory &&
            localMaven.list().orEmpty().isNotEmpty()
    }

    private fun makeAndroidToolsExecutable(androidHome: File, gradleHome: File) {
        val buildTools = latestBuildToolsDir(androidHome)
        val gradleDir = latestGradleDir(gradleHome)
        val tools = buildTools?.let {
            listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
                .map { command -> File(it, command) }
        }.orEmpty() + listOfNotNull(gradleDir?.let { File(it, "bin/gradle") })
        tools.filter(File::isFile).forEach { Os.chmod(it.absolutePath, 0b111101101) }
    }

    private fun writeAndroidGradleInitScript(runtimeRootfs: File, aapt2GuestPath: String) {
        val script = File(runtimeRootfs, "root/.gradle/init.d/pocketdev-android.gradle")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            def pocketMaven = uri('/root/maven/localMvnRepository')
            beforeSettings { settings ->
                settings.pluginManagement.repositories {
                    maven { url = pocketMaven }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            settingsEvaluated { settings ->
                settings.dependencyResolutionManagement.repositories {
                    maven { url = pocketMaven }
                }
            }
            gradle.beforeProject { project ->
                project.extensions.extraProperties.set(
                    'android.aapt2FromMavenOverride',
                    '$aapt2GuestPath',
                )
                project.buildscript.repositories {
                    maven { url = pocketMaven }
                }
            }
            """.trimIndent() + "\n",
        )
    }

    @Synchronized
    private fun writeAndroidGradleConfiguration(runtimeRootfs: File) {
        val androidHome = File(runtimeRootfs, "root/android-sdk")
        val buildTools = latestBuildToolsDir(androidHome) ?: return
        val aapt2 = File(buildTools, "aapt2")
        if (!aapt2.isFile) return
        val aapt2GuestPath = "/root/android-sdk/build-tools/" + buildTools.name + "/aapt2"

        writeAndroidGradleInitScript(runtimeRootfs, aapt2GuestPath)
        val gradleDir = File(runtimeRootfs, "root/.gradle").apply { mkdirs() }
        val properties = File(gradleDir, "gradle.properties")
        val propertyPattern = Regex("^\\s*${Regex.escape(ANDROID_AAPT2_PROPERTY)}\\s*[:=].*$")
        val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
        val expectedLine = "$ANDROID_AAPT2_PROPERTY=$aapt2GuestPath"
        val updatedLines = existingLines.filterNot { propertyPattern.matches(it) } + expectedLine
        if (existingLines == updatedLines) return

        val temporary = File(gradleDir, "gradle.properties.pocketdev.tmp")
        temporary.writeText(updatedLines.joinToString("\n").trimEnd() + "\n")
        Os.rename(temporary.absolutePath, properties.absolutePath)
    }

    /**
     * One-time upgrade path from the old single-bundle layout: devices that already
     * installed every tool keep all stacks without re-downloading anything.
     */
    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        if (coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION) coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }

    fun installedStacks(): Set<DevStack> {
        val state = readDevStackState()
        var changed = false
        DevStack.entries.forEach { stack ->
            if (hasStackBinaries(stack) && state[stack.name] != true) {
                state[stack.name] = true
                changed = true
            }
        }
        if (changed) writeDevStackState(state)
        return state
            .filterValues { it }
            .keys
            .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
            .filter(::isStackInstalled)
            .toSet()
    }

    fun isStackInstalled(stack: DevStack): Boolean {
        if (hasStackBinaries(stack)) return true
        if (readDevStackState()[stack.name] != true) return false
        if (stack != DevStack.ANDROID) return true
        return File(rootfs, "root/.pocket-android-tools-version").readTextOrNull()?.isNotBlank() == true &&
            hasAndroidToolchain(
                File(rootfs, "root/android-sdk"),
                File(rootfs, "opt/gradle"),
                File(rootfs, "root/maven/localMvnRepository"),
            ) &&
            File(rootfs, "root/.gradle/init.d/pocketdev-android.gradle").isFile
    }

    fun hasStackBinaries(stack: DevStack): Boolean = when (stack) {
        DevStack.WEB -> guestExecutableFile("/usr/bin/node") != null ||
            guestExecutableFile("/usr/local/bin/node") != null ||
            File(rootfs, "usr/bin/node").exists() ||
            File(rootfs, "usr/local/bin/node").exists()
        DevStack.PYTHON -> guestExecutableFile("/usr/bin/python3") != null ||
            guestExecutableFile("/usr/local/bin/python3") != null ||
            File(rootfs, "usr/bin/python3").exists() ||
            File(rootfs, "usr/local/bin/python3").exists()
        DevStack.ANDROID -> File(rootfs, "root/.pocket-android-tools-version").readTextOrNull()?.isNotBlank() == true ||
            hasAndroidToolchain(
                File(rootfs, "root/android-sdk"),
                File(rootfs, "opt/gradle"),
                File(rootfs, "root/maven/localMvnRepository"),
            )
        DevStack.CPP -> guestExecutableFile("/usr/bin/gcc") != null ||
            guestExecutableFile("/usr/bin/g++") != null ||
            File(rootfs, "usr/bin/gcc").exists() ||
            File(rootfs, "usr/bin/g++").exists()
        DevStack.PHP -> guestExecutableFile("/usr/bin/php") != null ||
            guestExecutableFile("/usr/bin/php8.4") != null ||
            guestExecutableFile("/usr/bin/php7.4") != null ||
            File(rootfs, "usr/bin/php").exists() ||
            File(rootfs, "usr/bin/php8.4").exists() ||
            File(rootfs, "usr/bin/php7.4").exists() ||
            File(rootfs, "usr/local/bin/composer").isFile ||
            File(rootfs, "usr/bin/composer").isFile ||
            File(rootfs, "usr/local/bin/php").exists()
        DevStack.BROWSER -> guestExecutableFile("/usr/bin/chromium-browser") != null ||
            guestExecutableFile("/usr/bin/chromium") != null ||
            File(rootfs, "usr/bin/chromium-browser").exists() ||
            File(rootfs, "usr/bin/chromium").exists() ||
            File(rootfs, "usr/local/bin/pocket-browser").exists()
    }

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    if (obj.has(stack.name)) put(stack.name, obj.optBoolean(stack.name))
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        val obj = JSONObject()
        state.forEach { (name, value) -> obj.put(name, value) }
        devStacksFile.writeText(obj.toString())
    }

    private suspend fun installNodeIfNeeded(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        check(!BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            "Bundled offline runtime is missing Node.js; reinstall the latest offline APK"
        }
        onProgress(RuntimeInstallProgress("Downloading Node.js $NODE_VERSION LTS", from))
        downloads.mkdirs()
        val nodeFileName = "node-$NODE_VERSION-linux-arm64.tar.gz"
        val nodeBaseUrl = "https://nodejs.org/dist/$NODE_VERSION"
        val checksum = fetchText("$nodeBaseUrl/SHASUMS256.txt")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith("  $nodeFileName") }
            ?.substringBefore(' ')
            ?: error("Node.js checksum was not found")
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    "Downloading Node.js $NODE_VERSION LTS",
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing Node.js and npm", to))
        val nodeStaging = File(runtimeDir, "node.installing")
        nodeStaging.deleteRecursively()
        nodeStaging.mkdirs()
        withTimeout(EXTRACTION_TIMEOUT_MS) { extractNodeArchive(nodeArchive, nodeStaging) }
        val nodeHome = File(rootfs, "usr/local/lib/nodejs")
        nodeHome.deleteRecursively()
        nodeHome.parentFile?.mkdirs()
        check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        listOf("node", "npm", "npx", "corepack").forEach { command ->
            val link = File(localBin, command)
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
        }
        nodeArchive.delete()
    }

    private suspend fun aptInstall(
        proot: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(proot, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        proot: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = "Updating the private Ubuntu environment",
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Ubuntu maintenance could not be completed",
        )
    }

    private suspend fun aptInstallInternal(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install: $packageNames",
        )
    }

    private suspend fun aptRemove(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o DPkg::Lock::Timeout=120 purge -y $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "apt-get purge -y $packageNames",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not remove: $packageNames",
        )
    }

    private suspend fun runGuestCommand(
        proot: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@pocket:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            val available = (input.length() - offset).coerceAtMost(256 * 1024).toInt()
                            val bytes = ByteArray(available)
                            input.readFully(bytes)
                            offset += available
                            pending += bytes.toString(Charsets.UTF_8).replace('\r', '\n')
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) "Command completed" else "Command failed (exit $exit)",
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { actionableProcessError(collected.toString(), failureMessage) }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(proot: File, command: String, failureMessage: String) {
        val verify = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        withTimeout(60_000L) {
            while (verify.isAlive) delay(50)
        }
        val exit = verify.waitFor()
        val output = (verify as? NativeSpawnProcess)?.outputFile
            ?.let(::readProcessOutputSafely)
            .orEmpty()
            .trim()
        check(exit == 0) { actionableProcessError(output, failureMessage) }
    }

    private suspend fun runGuest(proot: File, command: String, timeoutMs: Long = 60_000L): String {
        val proc = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        try {
            withTimeout(timeoutMs) {
                while (proc.isAlive) delay(50)
            }
        } finally {
            if (proc.isAlive) proc.destroy()
        }
        proc.waitFor()
        return (proc as? NativeSpawnProcess)?.outputFile
            ?.let(::readProcessOutputSafely)
            .orEmpty()
            .trim()
    }

    private suspend fun runGuestExitCode(root: File, command: String, timeoutMs: Long = 60_000L): Int {
        val proc = process(
            proot = root,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        try {
            withTimeout(timeoutMs) {
                while (proc.isAlive) delay(50)
            }
        } finally {
            if (proc.isAlive) proc.destroy()
        }
        return proc.waitFor()
    }

    /**
     * Ubuntu 20.04 uses a merged-/usr layout. A partially extracted or upgraded
     * runtime can lose these top-level links while all readiness markers remain,
     * making every ELF executable misleadingly fail with ENOENT. Restore only
     * the known Ubuntu compatibility links and never replace real directories.
     */
    private fun ensureRootfsCompatibilityLinks(): Boolean {
        if (!rootfs.isDirectory) return false
        val links = mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "sbin" to "usr/sbin",
        )
        return runCatching {
            links.forEach { (name, destination) ->
                val link = File(rootfs, name)
                val path = link.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    if (java.nio.file.Files.readSymbolicLink(path).toString() != destination) {
                        java.nio.file.Files.delete(path)
                        Os.symlink(destination, link.absolutePath)
                    }
                } else if (link.exists()) {
                    check(link.isDirectory) { "Linux /$name is not a directory or symbolic link" }
                } else {
                    Os.symlink(destination, link.absolutePath)
                }
            }
            File(rootfs, "usr/bin/env").canExecute() &&
                File(rootfs, "usr/bin/bash").canExecute() &&
                File(rootfs, "lib/ld-linux-aarch64.so.1").exists()
        }.onFailure {
            android.util.Log.e("RuntimeInstaller", "Could not repair Linux compatibility links", it)
        }.getOrDefault(false)
    }

    private fun actionableProcessError(output: String, fallback: String): String {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val primaryProotError = lines.firstOrNull {
            it.startsWith("proot error:") && !it.contains("can't chmod")
        } ?: lines.firstOrNull { it.startsWith("proot error:") }
        return primaryProotError ?: output.trim().takeLast(1_000).ifBlank { fallback }
    }

    /**
     * Reads a captured PRoot process output file as UTF-8 with replacement,
     * so a stray non-UTF-8 byte in the bundled runtime (for example a binary
     * .pth file from a tarball built on a non-UTF-8 filesystem) does not
     * abort setup.
     */
    private fun readProcessOutputSafely(file: File): String = runCatching {
        file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
    }.getOrElse { error ->
        android.util.Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress("Checking private runtime files", 0.15f))
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing the Android runtime bridge", 0.42f))
        check(File(rootfs, "usr/local/bin/node").canExecute()) { "Core runtime is missing Node.js" }
        check(File(rootfs, "usr/bin/git").canExecute()) { "Core runtime is missing Git" }
        onProgress(RuntimeInstallProgress("Private runtime is ready", 1f))
        return installed
    }

    fun process(
        proot: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): Process {
        check(ensureRootfsCompatibilityLinks()) { "Core runtime has an invalid Linux filesystem layout" }
        ensureGuestCredentialsMigrated()
        require(
            guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        workspace.mkdirs()
        File(rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        // v3.2.50 (Item 3): refresh the guest resolver from the CURRENTLY
        // active network on every spawn. writeResolver() otherwise runs
        // only at install/maintenance time, so the guest kept the DNS of
        // whatever network was active back then (e.g. the home Wi-Fi
        // router's LAN resolver); after the phone switched to mobile
        // data that resolver was unreachable and every guest connection
        // stalled — the "works only on Wi-Fi" failure.
        runCatching { writeResolver(rootfs) }
        // Self-heal devices whose Android tools were installed by an older app
        // version before the global AAPT2 override was persisted.
        writeAndroidGradleConfiguration(rootfs)
        ensureWorkspaceTrust(guestWorkspacePath)
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        val args = buildList {
            add(proot.absolutePath)
            if (emulateHardLinks) add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            // ARM64 Android build tools (notably aapt2) use Bionic's
            // /system/bin/linker64 and, on newer releases, APEX libraries.
            listOf("/system", "/apex", "/vendor", "/product").forEach { hostPath ->
                if (File(hostPath).exists()) {
                    File(rootfs, hostPath.removePrefix("/")).mkdirs()
                    add("-b")
                    add(hostPath)
                }
            }
            add("-b")
            add("${workspace.absolutePath}:$guestWorkspacePath")
            add("-b")
            add("${bridge.absolutePath}:/pocket-bridge")
            extraBinds.forEach { (hostPath, guestPath) ->
                File(hostPath).mkdirs()
                File(rootfs, guestPath.removePrefix("/")).mkdirs()
                add("-b")
                add("$hostPath:$guestPath")
            }
            add("-w")
            add(guestWorkspacePath)
            addAll(guestCommand)
        }
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        return NativeSpawnProcess.start(
            argv = args,
            environment = buildMap {
                put("HOME", "/root")
                val androidHome = File(rootfs, "root/android-sdk")
                val gradleHome = File(rootfs, "opt/gradle")
                val localMaven = File(rootfs, "root/maven/localMvnRepository")
                val buildTools = latestBuildToolsDir(androidHome)
                val gradleDir = latestGradleDir(gradleHome)
                val androidReady = File(rootfs, "root/.pocket-android-tools-version").readTextOrNull()?.isNotBlank() == true &&
                    hasAndroidToolchain(androidHome, gradleHome, localMaven) &&
                    buildTools != null && gradleDir != null
                val basePath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                if (androidReady) {
                    val aapt2Path = "/root/android-sdk/build-tools/" + buildTools.name + "/aapt2"
                    put("ANDROID_HOME", "/root/android-sdk")
                    put("ANDROID_SDK_ROOT", "/root/android-sdk")
                    put("GRADLE_HOME", "/opt/gradle/" + gradleDir.name)
                    put("GRADLE_USER_HOME", "/root/.gradle")
                    put("ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride", aapt2Path)
                    put("PATH", "/opt/gradle/" + gradleDir.name + "/bin:/root/android-sdk/build-tools/" + buildTools.name + ":/root/android-sdk/cmdline-tools/latest/bin:$basePath")
                } else {
                    put("PATH", basePath)
                }
                put("LANG", "C.UTF-8")
                put("TERM", "xterm-256color")
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", prootTemp.absolutePath)
                put("PROOT_LOADER", File(context.applicationInfo.nativeLibraryDir, "libprootloader.so").absolutePath)
                // Also protects any glibc helper Claude starts later.
                put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
                putAll(environment)
            },
            cwd = context.filesDir.absolutePath,
            outputFile = outputFile,
            pseudoTerminal = pseudoTerminal,
            ptyRows = ptyRows,
            ptyColumns = ptyColumns,
        )
    }

    fun ensureSettingsAndHooks() {
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        hook.writeText(
            """#!/bin/sh
REQ_DIR="/pocket-bridge"
REQ_ID="claude-${'$'}${'$'}-${'$'}(date +%s%N 2>/dev/null || date +%s)"
REQ_FILE="${'$'}REQ_DIR/${'$'}REQ_ID.request"
RESP_FILE="${'$'}REQ_DIR/${'$'}REQ_ID.response"
mkdir -p "${'$'}REQ_DIR"
cat > "${'$'}REQ_FILE.tmp"
mv -f "${'$'}REQ_FILE.tmp" "${'$'}REQ_FILE"
for i in ${'$'}(seq 1 1200); do
  if [ -f "${'$'}RESP_FILE" ]; then break; fi
  sleep 0.1
done
DECISION="deny"
if [ -f "${'$'}RESP_FILE" ]; then
  case "${'$'}(head -c 16 "${'$'}RESP_FILE" 2>/dev/null)" in
    allow*) DECISION="allow" ;;
  esac
  rm -f "${'$'}REQ_FILE" "${'$'}RESP_FILE"
else
  rm -f "${'$'}REQ_FILE"
fi
printf '%s\n' "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\",\"decision\":{\"behavior\":\"${'$'}DECISION\"}}}"
""",
        )
        Os.chmod(hook.absolutePath, 0b111101101)

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeReadOnlyToolRules())
                    .put("defaultMode", "default"),
            )
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                org.json.JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            target.parentFile?.mkdirs()
            target.writeText(settingsContent)
        }
        ensureWorkspaceTrust("/workspace")
        runCatching { installPocketBrowserScript() }
    }

    private fun ensureWorkspaceTrust(workspacePath: String) {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        // Older alpha builds incorrectly wrote settings into Claude's state file.
        // Keep Claude's generated state, but remove only those stale settings keys.
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach(state::remove)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        stateFile.writeText(state.toString())
    }

    private fun claudeReadOnlyToolRules() = org.json.JSONArray().apply {
        put("Read")
        put("Glob")
        put("Grep")
    }

    private fun writeResolver(target: File? = null) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        File(target ?: rootfs, "etc/resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun checkArchiveSize(archive: File) {
        require(archive.length() <= MAX_RUNTIME_ARCHIVE_BYTES) { "Runtime archive exceeds the 2 GB size limit" }
    }

    private fun checkDiskSpaceForExtraction(destination: File, archive: File) {
        val available = StatFs(destination.absolutePath).availableBytes
        val required = maxOf(archive.length() * 3, 256L * 1024L * 1024L)
        check(available >= required) { "Not enough free space to extract the runtime archive" }
    }


    private fun extractZstdTar(archive: File, destination: File) {
        checkArchiveSize(archive)
        checkDiskSpaceForExtraction(destination, archive)
        val budget = RuntimeExtractionBudget()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(
            ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                budget.checkEntry()
                val cleanName = entry.name.removePrefix("./")
                if (cleanName.isNotBlank()) {
                    val target = safeChild(destination, cleanName)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            validateArchiveSymlinkTarget(destination, target, entry.linkName)
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { budget.copyBounded(input, it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> budget.copyBounded(tar, output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { budget.copyBounded(input, it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractNodeArchive(archive: File, destination: File) {
        checkArchiveSize(archive)
        checkDiskSpaceForExtraction(destination, archive)
        val budget = RuntimeExtractionBudget()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                budget.checkEntry()
                val relative = entry.name.removePrefix("./").substringAfter('/', "")
                if (relative.isNotBlank()) {
                    val target = safeChild(destination, relative)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            val relativeLink = entry.linkName.removePrefix("./").substringAfter('/', "")
                            val linkTarget = safeChild(destination, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { budget.copyBounded(input, it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> budget.copyBounded(tar, output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Node.js archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { budget.copyBounded(input, it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe archive path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        return file
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        maxBytes: Long = MAX_RUNTIME_ARCHIVE_BYTES,
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
        check(connection.responseCode in 200..299) { "Download failed with HTTP ${connection.responseCode}" }
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        if (!resumed) {
            temporary.delete()
            existing = 0L
        }
        val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
        activeDownloadConnections.add(connection)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(temporary, resumed).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existing
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        check(downloaded <= maxBytes) { "Download exceeds the maximum archive size" }
                        onBytes(downloaded, total)
                    }
                }
            }
        } finally {
            activeDownloadConnections.remove(connection)
            connection.disconnect()
        }
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty(
            "Accept",
            if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/json",
        )
        connection.setRequestProperty("User-Agent", "Kodex")
        if (url.startsWith("https://api.github.com/")) {
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        check(connection.responseCode in 200..299) { "Request failed with HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun installPocketBrowserScript() {
        val target = File(rootfs, "usr/local/bin/pocket-browser")
        target.parentFile?.mkdirs()
        target.writeText(pocketBrowserScriptContent())
        runCatching { Os.chmod(target.absolutePath, 0b111101101) }
    }

    private fun pocketBrowserScriptContent(): String = """#!/usr/bin/env node
const http = require('http');
const net = require('net');
const { spawn, execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const PORT = 9222;
const PID_FILE = '/tmp/pocket-chromium.pid';
const ATTACH_FILE = '/tmp/pocket-browser-attach.json';
const WEBVIEW_FILE = '/tmp/pocket-browser-webview.json';
const BRIDGE_PORT = 9223;
const AGENT_LOG = '/tmp/pocket-browser-agent.log';
const DISCOVERY_MAX_AGE_MS = 60000;

function getPuppeteer() {
  try {
    return require('puppeteer-core');
  } catch (e) {
    try {
      const globalRoot = execSync('npm root -g').toString().trim();
      return require(path.join(globalRoot, 'puppeteer-core'));
    } catch (err) {
      console.error('Error: puppeteer-core is not installed. Run: npm install -g puppeteer-core');
      process.exit(1);
    }
  }
}

function isPortOpen(port) {
  return new Promise((resolve) => {
    const req = http.get(`http://127.0.0.1:${'$'}{port}/json/version`, (res) => {
      resolve(res.statusCode === 200);
    });
    req.on('error', () => resolve(false));
    req.setTimeout(1500, () => {
      req.destroy();
      resolve(false);
    });
  });
}

function readJsonFile(p) {
  try {
    if (!fs.existsSync(p)) return null;
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) { return null; }
}

function agentLog(cmd, detail) {
  const line = `[agent-browser] ${'$'}{new Date().toISOString()} ${'$'}{cmd}${'$'}{detail ? ' ' + detail : ''}`;
  console.log(line);
  try { fs.appendFileSync(AGENT_LOG, line + '\n'); } catch (e) {}
}

function rewriteWsUrl(wsUrl, port) {
  return String(wsUrl).replace(/^ws:\/\/127\.0\.0\.1:\d+/, `ws://127.0.0.1:${'$'}{port}`);
}

function sleepMs(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function getAttachState() {
  const st = readJsonFile(ATTACH_FILE);
  if (!st || st.mode !== 'attached' || !st.webSocketDebuggerUrl) return null;
  return st;
}

function clearAttachState() {
  try { if (fs.existsSync(ATTACH_FILE)) fs.unlinkSync(ATTACH_FILE); } catch (e) {}
  try { execSync('pkill -f "pocket-browser __bridge" 2>/dev/null || true'); } catch (e) {}
}

async function ensureBridge(socketName, port) {
  if (await isPortOpen(port)) return;
  const child = spawn(process.execPath, [process.argv[1], '__bridge', socketName, String(port)], {
    detached: true,
    stdio: 'ignore'
  });
  child.unref();
  for (let i = 0; i < 20; i++) {
    await sleepMs(250);
    if (await isPortOpen(port)) return;
  }
  throw new Error('DevTools bridge failed to start on port ' + port);
}

function findChromium() {
  const candidates = [
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    'chromium-browser',
    'chromium',
    '/snap/bin/chromium'
  ];
  for (const c of candidates) {
    try {
      if (fs.existsSync(c)) return c;
      const which = execSync(`which ${'$'}{c} 2>/dev/null`).toString().trim();
      if (which) return which;
    } catch (e) {}
  }
  return 'chromium-browser';
}

async function ensureChromium() {
  const running = await isPortOpen(PORT);
  if (running) return;

  const extDir = ['/workspace/extensions', '/workspace/.extensions', '/root/.extensions'].find(p => fs.existsSync(p));
  const extArgs = extDir
    ? [`--load-extension=${'$'}{extDir}`, `--disable-extensions-except=${'$'}{extDir}`]
    : [];

  const bin = findChromium();
  const args = [
    '--headless',
    '--no-sandbox',
    '--disable-setuid-sandbox',
    '--disable-dev-shm-usage',
    '--disable-gpu',
    `--remote-debugging-port=${'$'}{PORT}`,
    '--remote-debugging-address=127.0.0.1',
    '--window-size=1280,800',
    ...extArgs,
    'about:blank'
  ];

  const child = spawn(bin, args, {
    detached: true,
    stdio: 'ignore'
  });
  child.unref();

  if (child.pid) {
    try { fs.writeFileSync(PID_FILE, String(child.pid)); } catch (e) {}
  }

  for (let i = 0; i < 30; i++) {
    await new Promise((r) => setTimeout(r, 500));
    if (await isPortOpen(PORT)) return;
  }
  throw new Error('Chromium failed to start on port ' + PORT);
}

async function getBrowserAndPage() {
  const attached = getAttachState();
  if (attached) {
    const puppeteer = getPuppeteer();
    if (attached.devtoolsSocket) {
      try { await ensureBridge(attached.devtoolsSocket, BRIDGE_PORT); }
      catch (e) { clearAttachState(); throw new Error('Lost the bridge to the browser tab. Run pocket-browser attach again.'); }
    }
    try {
      const browser = await puppeteer.connect({ browserWSEndpoint: attached.webSocketDebuggerUrl, defaultViewport: null });
      const pages = await browser.pages();
      const page = pages[0];
      if (!page) throw new Error('no page target');
      return { browser, page, attached: true };
    } catch (e) {
      clearAttachState();
      throw new Error('Lost connection to the browser tab. Run pocket-browser attach again.');
    }
  }
  await ensureChromium();
  const puppeteer = getPuppeteer();
  const browser = await puppeteer.connect({
    browserURL: `http://127.0.0.1:${'$'}{PORT}`,
    defaultViewport: { width: 1280, height: 800 }
  });
  const pages = await browser.pages();
  const page = pages.length > 0 ? pages[0] : await browser.newPage();
  return { browser, page, attached: false };
}

async function main() {
  const [,, cmd, ...args] = process.argv;

  switch (cmd) {
    case 'start':
    case 'launch': {
      await ensureChromium();
      console.log(`Chromium running on http://127.0.0.1:${'$'}{PORT}`);
      process.exit(0);
      break;
    }

    case 'attach': {
      const cdpFlag = args.indexOf('--cdp');
      const cdpOverride = cdpFlag >= 0 ? args[cdpFlag + 1] : null;
      let wsUrl;
      let devtoolsSocket = null;
      let tabUrl = '';
      let tabTitle = '';
      if (cdpOverride) {
        wsUrl = cdpOverride;
      } else {
        const disc = readJsonFile(WEBVIEW_FILE);
        if (!disc || !disc.webSocketDebuggerUrl || !disc.devtoolsSocket) {
          console.error('No browser tab found. Open a page in the app browser tab first, then run attach again.');
          process.exit(1);
        }
        if (Date.now() - (disc.discoveredAt || 0) > DISCOVERY_MAX_AGE_MS) {
          console.error('Browser tab discovery is stale. Bring the app browser tab to the foreground and run attach again.');
          process.exit(1);
        }
        devtoolsSocket = disc.devtoolsSocket;
        tabUrl = disc.tabUrl || '';
        tabTitle = disc.tabTitle || '';
        try { await ensureBridge(devtoolsSocket, BRIDGE_PORT); }
        catch (e) { console.error('PocketBrowser error:', e.message || e); process.exit(1); }
        wsUrl = rewriteWsUrl(disc.webSocketDebuggerUrl, BRIDGE_PORT);
      }
      const puppeteer = getPuppeteer();
      try {
        const browser = await puppeteer.connect({ browserWSEndpoint: wsUrl, defaultViewport: null });
        const pages = await browser.pages();
        if (!pages[0]) throw new Error('no page target');
        if (!tabUrl) tabUrl = pages[0].url();
        try { if (!tabTitle) tabTitle = await pages[0].title(); } catch (e) {}
        browser.disconnect();
      } catch (e) {
        console.error('Could not attach to the browser tab:', e.message || e);
        process.exit(1);
      }
      fs.writeFileSync(ATTACH_FILE, JSON.stringify({
        mode: 'attached',
        webSocketDebuggerUrl: wsUrl,
        devtoolsSocket: devtoolsSocket,
        tabUrl: tabUrl,
        tabTitle: tabTitle,
        attachedAt: Date.now()
      }, null, 2));
      agentLog('attach', tabUrl || wsUrl);
      console.log(JSON.stringify({ status: 'attached', url: tabUrl, title: tabTitle }));
      console.log('The user is watching live in the browser tab and can take over anytime.');
      process.exit(0);
      break;
    }

    case 'detach': {
      clearAttachState();
      agentLog('detach', '');
      console.log('Detached from the browser tab. The user has full control again.');
      process.exit(0);
      break;
    }

    case '__bridge': {
      const socketName = args[0];
      const port = parseInt(args[1] || String(BRIDGE_PORT), 10);
      if (!socketName) {
        console.error('Usage: pocket-browser __bridge <socket> <port>');
        process.exit(1);
      }
      const server = net.createServer((tcp) => {
        let unix = null;
        const cleanup = () => {
          try { tcp.destroy(); } catch (e) {}
          try { if (unix) unix.destroy(); } catch (e) {}
        };
        try {
          unix = net.createConnection('\0' + socketName);
        } catch (e) { tcp.destroy(); return; }
        tcp.on('error', cleanup);
        unix.on('error', cleanup);
        tcp.on('close', cleanup);
        unix.on('close', cleanup);
        tcp.pipe(unix);
        unix.pipe(tcp);
      });
      server.on('error', (e) => { console.error('Bridge error:', e.message); process.exit(1); });
      server.listen(port, '127.0.0.1', () => {});
      setInterval(() => {}, 3600000);
      break;
    }

    case 'stop':
    case 'close': {
      clearAttachState();
      try {
        if (fs.existsSync(PID_FILE)) {
          const pid = parseInt(fs.readFileSync(PID_FILE, 'utf8').trim(), 10);
          if (pid) process.kill(pid);
          fs.unlinkSync(PID_FILE);
        }
      } catch (e) {}
      try { execSync('pkill -f "remote-debugging-port=9222" || true'); } catch (e) {}
      console.log('Chromium stopped');
      process.exit(0);
      break;
    }

    case 'status': {
      const attached = getAttachState();
      if (attached) {
        try {
          const conn = await getBrowserAndPage();
          const url = conn.page.url();
          const title = await conn.page.title().catch(() => '');
          console.log(JSON.stringify({ status: 'running', mode: 'attached', url, title }));
          conn.browser.disconnect();
        } catch (e) {
          console.log(JSON.stringify({ status: 'detached', reason: e.message || String(e) }));
        }
        process.exit(0);
        break;
      }
      const running = await isPortOpen(PORT);
      if (!running) {
        console.log(JSON.stringify({ status: 'stopped', port: PORT }));
      } else {
        const { browser, page } = await getBrowserAndPage();
        const url = page.url();
        const title = await page.title();
        const pages = await browser.pages();
        console.log(JSON.stringify({ status: 'running', mode: 'headless', port: PORT, url, title, tabCount: pages.length }));
        browser.disconnect();
      }
      process.exit(0);
      break;
    }

    case 'tabs':
    case 'list-tabs': {
      await ensureChromium();
      const puppeteer = getPuppeteer();
      const browser = await puppeteer.connect({
        browserURL: `http://127.0.0.1:${'$'}{PORT}`
      });
      const pages = await browser.pages();
      const tabsInfo = await Promise.all(pages.map(async (p, idx) => ({
        index: idx,
        url: p.url(),
        title: await p.title()
      })));
      console.log(JSON.stringify(tabsInfo, null, 2));
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'new-tab':
    case 'open-tab': {
      if (getAttachState()) {
        console.error('Tab management is disabled while attached to the user browser tab.');
        process.exit(1);
      }
      const url = args[0] || 'about:blank';
      const targetUrl = url.includes('://') ? url : (url === 'about:blank' ? url : 'http://' + url);
      await ensureChromium();
      const puppeteer = getPuppeteer();
      const browser = await puppeteer.connect({
        browserURL: `http://127.0.0.1:${'$'}{PORT}`
      });
      const page = await browser.newPage();
      if (targetUrl !== 'about:blank') {
        await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });
      }
      const pages = await browser.pages();
      const index = pages.indexOf(page);
      console.log(JSON.stringify({ status: 'created', index, url: page.url(), title: await page.title() }));
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'switch-tab': {
      if (getAttachState()) {
        console.error('Tab management is disabled while attached to the user browser tab.');
        process.exit(1);
      }
      const idx = parseInt(args[0] || '0', 10);
      await ensureChromium();
      const puppeteer = getPuppeteer();
      const browser = await puppeteer.connect({
        browserURL: `http://127.0.0.1:${'$'}{PORT}`
      });
      const pages = await browser.pages();
      if (idx >= 0 && idx < pages.length) {
        await pages[idx].bringToFront();
        console.log(JSON.stringify({ status: 'switched', index: idx, url: pages[idx].url(), title: await pages[idx].title() }));
      } else {
        console.error(`Invalid tab index: ${'$'}{idx}. Total open: ${'$'}{pages.length}`);
      }
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'close-tab': {
      if (getAttachState()) {
        console.error('Tab management is disabled while attached to the user browser tab.');
        process.exit(1);
      }
      const idx = parseInt(args[0] || '0', 10);
      await ensureChromium();
      const puppeteer = getPuppeteer();
      const browser = await puppeteer.connect({
        browserURL: `http://127.0.0.1:${'$'}{PORT}`
      });
      const pages = await browser.pages();
      if (idx >= 0 && idx < pages.length) {
        await pages[idx].close();
        console.log(`Closed tab at index ${'$'}{idx}`);
      } else {
        console.error(`Invalid tab index: ${'$'}{idx}. Total open: ${'$'}{pages.length}`);
      }
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'open':
    case 'goto': {
      const url = args[0];
      if (!url) {
        console.error('Usage: pocket-browser open <url>');
        process.exit(1);
      }
      const targetUrl = url.includes('://') ? url : 'http://' + url;
      const { browser, page } = await getBrowserAndPage();
      await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });
      const current = page.url();
      const title = await page.title();
      console.log(`Navigated to: ${'$'}{current} ("${'$'}{title}")`);
      agentLog('open', targetUrl);
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'click': {
      const selector = args[0];
      if (!selector) {
        console.error('Usage: pocket-browser click <selector>');
        process.exit(1);
      }
      const { browser, page } = await getBrowserAndPage();
      await page.waitForSelector(selector, { timeout: 10000 });
      await page.click(selector);
      console.log(`Clicked: ${'$'}{selector}`);
      agentLog('click', selector);
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'type':
    case 'fill': {
      const [selector, ...textParts] = args;
      const text = textParts.join(' ');
      if (!selector || text === undefined) {
        console.error('Usage: pocket-browser type <selector> <text>');
        process.exit(1);
      }
      const { browser, page } = await getBrowserAndPage();
      await page.waitForSelector(selector, { timeout: 10000 });
      await page.click(selector, { clickCount: 3 }).catch(() => {});
      await page.type(selector, text);
      console.log(`Typed into ${'$'}{selector}: "${'$'}{text}"`);
      agentLog('type', selector);
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'press': {
      const key = args[0] || 'Enter';
      const { browser, page } = await getBrowserAndPage();
      await page.keyboard.press(key);
      console.log(`Pressed key: ${'$'}{key}`);
      agentLog('press', key);
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'screenshot': {
      const outPath = args[0] || 'screenshot.png';
      const absPath = path.resolve(process.cwd(), outPath);
      const { browser, page } = await getBrowserAndPage();
      await page.screenshot({ path: absPath, fullPage: false });
      console.log(`Screenshot saved to: ${'$'}{absPath}`);
      agentLog('screenshot', absPath);
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'get-text':
    case 'snapshot':
    case 'content': {
      const selector = args[0] || 'body';
      const { browser, page } = await getBrowserAndPage();
      const text = await page.${'$'}${'$'}eval(selector, el => el.innerText || el.textContent).catch(async () => {
        return await page.content();
      });
      console.log(text ? text.trim() : '');
      agentLog('get-text', selector);
      browser.disconnect();
      process.exit(0);
      break;
    }

    case 'eval':
    case 'inject': {
      const code = args.join(' ');
      if (!code) {
        console.error('Usage: pocket-browser eval <code>');
        process.exit(1);
      }
      const { browser, page } = await getBrowserAndPage();
      const result = await page.evaluate(code);
      console.log(JSON.stringify(result, null, 2));
      agentLog('eval', code.slice(0, 80));
      browser.disconnect();
      process.exit(0);
      break;
    }

    default:
      console.log(`PocketBrowser CLI - Automated Chromium & Puppeteer control

Commands:
  start                     Start headless Chromium on port ${'$'}{PORT}
  stop                      Stop Chromium process (also detaches from browser tab)
  attach [--cdp <wsUrl>]    Attach to the visible in-app browser tab (user watches live)
  detach                    Release the browser tab back to the user
  status                    Show active page URL, title, tab count, mode, and port
  tabs                      List all open tabs / pages
  new-tab [url]             Open a new tab (optionally with URL)
  switch-tab <index>        Switch active page to tab index
  close-tab <index>         Close tab at index
  open <url>                Navigate to URL
  click <selector>          Click element by CSS selector
  type <selector> <text>    Type text into input element
  press <key>               Press keyboard key (e.g. Enter, Tab)
  screenshot [path]         Capture viewport screenshot to file
  get-text [selector]       Extract text content from selector
  eval <code>               Execute JavaScript expression in page
  inject <code>             Alias for eval
`);
      process.exit(0);
  }
}

main().catch((err) => {
  console.error('PocketBrowser error:', err.message || err);
  process.exit(1);
});
""".trimIndent() + "\n"

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        const val ENVIRONMENT_VERSION_CACHE_TTL_MS = 15 * 60 * 1000L
        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        /** Codex CLI binary, symlinked from npm's global bin dir after install. */
        const val CODEX_GUEST_PATH = "/usr/local/bin/codex"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        const val GUEST_GITHUB_CONFIG_DIR = "/root/.config/gh"
        const val GUEST_ANTIGRAVITY_CONFIG_DIR = "/root/.gemini/antigravity-cli"
        private const val AGY_VERSION = "1.1.27"
        private const val AGY_RELEASE_URL = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz"
        private const val AGY_RELEASE_SHA512 = "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9"
        private const val GITHUB_CLI_VERSION = "2.100.0"
        private const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_arm64.tar.gz"
        private const val GITHUB_CLI_RELEASE_SHA256 = "ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961"
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        private const val ROOTFS_FILE = "ubuntu-base-20.04.5-base-arm64.tar.gz"
        private const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/$ROOTFS_FILE"
        private const val ROOTFS_SHA256 = "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52"
        private const val NODE_VERSION = "v24.19.0"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        private const val CORE_TOOLS_VERSION = "core-bundle-2026.09.5"
        private const val LEGACY_CORE_TOOLS_VERSION = "core-bundle-2026.09.4"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private const val ANDROID_TOOLS_VERSION = "sdk36-build-tools35-gradle8.14.3-maven-2026.09"
        private const val ENV_PYTHON_VERSION = "2026.09.2"
        private const val ENV_ANDROID_VERSION = "2026.09.1"
        private const val ANDROID_ASSET_BASE = "https://appdevforall.org/dev-assets/debug"
        private const val ANDROID_SDK_URL = "$ANDROID_ASSET_BASE/android-sdk-arm64-v8a.zip"
        private const val ANDROID_SDK_SHA256 = "bfe5bc940a7ede14735817a40962256666ce4152b9f3135f34a4ab9bccb87c3f"
        private const val ANDROID_GRADLE_URL = "$ANDROID_ASSET_BASE/gradle-8.14.3-bin.zip"
        private const val ANDROID_GRADLE_SHA256 = "8e228b640319a7c739c0a93d002facdeb08e8f3ba394d57d74ee355a5e93072c"
        private const val ANDROID_MAVEN_URL = "$ANDROID_ASSET_BASE/localMvnRepository.zip"
        private const val ANDROID_MAVEN_SHA256 = "3ba89892b43377d60743568d1b9004f172c2eab75dac059064f82dec497819f9"
        private const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        private const val ANDROID_AAPT2_GUEST_PATH = "/root/android-sdk/build-tools/35.0.0/aapt2"
        private const val ANDROID_AAPT2_HOST_PATH = "root/android-sdk/build-tools/35.0.0/aapt2"
        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        /** Pinned DeepSeek Harness release installed via npm inside the guest (verified 2026-09-06). */
        const val DSH_VERSION = "0.1.2-rc.1"
        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v1"
        private val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "conzyx-core-arm64-2026.09.5.tar.zst",
            sha256 = "df0cf7251c74f82d424231e3804114a4ca66b16130eea9abab11e220dc7ac012",
            compressedBytes = 72_185_773L,
        )
        private const val CLAUDE_BUNDLED_VERSION = "2.1.263"
        private const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        private val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "conzyx-claude-arm64-2026.09.1.tar.zst",
            sha256 = "0f68e15630e8c0fc941afe3f61ab5a3eb4407b334018de6dbdabfa5eca627724",
            compressedBytes = 75_289_800L,
        )
        private val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "conzyx-python-arm64-2026.09.2.tar.zst",
            sha256 = "6b3f56f7743fec142bc045db3ea561ee83fe89af87c2799165ef60351164ef85",
            compressedBytes = 55_419_626L,
        )
        private val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "conzyx-android-arm64-2026.09.1.tar.zst",
            sha256 = "01bea058ebcb17416d1eb08c0211b3782da3228eb3c8348eb3719f2d61dd3ec6",
            compressedBytes = 569_652_007L,
        )
        private val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek Harness",
            fileName = "conzyx-dsh-arm64-2026.09.1.tar.zst",
            sha256 = "88e6a23ba74e1cd74a2c923b7e0d6bd78ba4b7e5f8a9b649f12bf4ffe158cce5",
            compressedBytes = 27_752_194L,
        )
        private val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "conzyx-agy-arm64-2026.09.1.tar.zst",
            sha256 = "a659ab9188956fc4721ca86fb21b5118e0e489f47a5e02ae6b4f2fb423659d78",
            compressedBytes = 41_870_025L,
        )
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private const val MAX_PROCESS_OUTPUT_BYTES = 256 * 1024
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    }
}

/**
 * Activates a staged directory as [target] without a destroy-the-target
 * window: the live target is first moved to [backup], then staging takes
 * its place. If activation fails, the backup is restored and the error
 * propagates. Top-level and pure so it stays unit-testable.
 */
internal fun activateStagingDir(staging: File, target: File, backup: File) {
    backup.deleteRecursively()
    val hadTarget = target.exists()
    if (hadTarget) check(target.renameTo(backup)) { "Could not back up the existing directory" }
    try {
        check(staging.renameTo(target)) { "Could not activate the new directory" }
    } catch (e: Throwable) {
        if (hadTarget) runCatching { backup.renameTo(target) }
        throw e
    }
    backup.deleteRecursively()
}

internal fun relocateCredentialFiles(legacyDir: File, secureDir: File) {
    secureDir.mkdirs()
    if (!legacyDir.exists()) return
    val files = legacyDir.walkTopDown().filter { it.isFile }.toList()
    for (source in files) {
        val dest = File(secureDir, source.relativeTo(legacyDir).path)
        if (dest.exists() && dest.lastModified() >= source.lastModified()) {
            runCatching { source.delete() }
            continue
        }
        dest.parentFile?.mkdirs()
        if (!source.renameTo(dest)) {
            runCatching {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
        }
    }
    runCatching { legacyDir.deleteRecursively() }
}

internal fun validateArchiveSymlinkTarget(root: File, linkFile: File, linkName: String) {
    require(linkName.isNotBlank()) { "Archive symlink has an empty target" }
    // The archive is a guest root filesystem image: once installed, the
    // extraction root IS "/" for everything that runs inside it, and the
    // guest kernel resolves every symlink within that root (".." at the
    // root simply stays at the root). Validation therefore resolves the
    // target purely lexically — exactly like the guest will — and clamps
    // any climb at the root boundary instead of rejecting the link.
    //
    // It must NOT use File.canonicalFile: canonical resolution consults
    // the host disk, so the verdict depends on which links an earlier
    // extraction (or an earlier entry of this one) already created there.
    // That state-dependence falsely rejected valid rootfs links on real
    // devices ("Archive symlink target escapes the extraction root" on
    // bundles whose links never actually leave the image — verified by
    // simulating every link of the shipped Core bundle lexically).
    //
    // Creating a symlink with a literal target cannot write outside the
    // root; bundle integrity is enforced separately by the SHA-256 check
    // that runs before unpacking.
    val resolved = resolveSymlinkTargetLexically(root, linkFile, linkName)
    val rootPath = root.absoluteFile.toPath().toString().trimEnd('/')
    require(resolved == rootPath || resolved.startsWith("$rootPath/")) {
        "Archive symlink target escapes the extraction root"
    }
}

/**
 * Resolves [linkName] (the target of the symlink at [linkFile]) against the
 * extraction [root] using only string segment math: no disk access, no
 * symlink following. Absolute targets are root-relative; relative targets
 * resolve against the link's parent directory inside the image. "." and
 * empty segments are dropped; ".." pops one segment but never climbs above
 * the root (mirroring path resolution inside a chroot). The returned
 * absolute path is therefore always inside the root.
 */
internal fun resolveSymlinkTargetLexically(root: File, linkFile: File, linkName: String): String {
    val rootSegments = root.absoluteFile.toPath().toString()
        .split('/').filter { it.isNotEmpty() }
    val segments = ArrayDeque(rootSegments)
    if (!linkName.startsWith("/")) {
        // Relative target: start from the link's parent directory, expressed
        // relative to the root (the link file always lives under the root).
        val rootPrefix = root.absoluteFile.toPath().toString().trimEnd('/') + "/"
        val linkAbsolute = linkFile.absoluteFile.toPath().toString()
        val parentRelative = linkAbsolute.removePrefix(rootPrefix)
            .substringBeforeLast('/', missingDelimiterValue = "")
        if (parentRelative.isNotEmpty()) {
            parentRelative.split('/').filter { it.isNotEmpty() }.forEach { segments.addLast(it) }
        }
    }
    for (segment in linkName.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.size > rootSegments.size) segments.removeLast() else Unit
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}
