package com.vayro.app.ui.coordinators

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vayro.app.BuildConfig
import com.vayro.app.data.ApiKeyVault
import com.vayro.app.data.AppPreferences
import com.vayro.app.model.AgentKind
import com.vayro.app.model.DevStack
import com.vayro.app.model.Project
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.runtime.AndroidAppInstaller
import com.vayro.app.runtime.AntigravityAuthStatus
import com.vayro.app.runtime.RuntimeInstallProgress
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.runtime.RuntimeSetupController
import com.vayro.app.runtime.RuntimeSetupService
import com.vayro.app.runtime.RuntimeSetupSnapshot
import com.vayro.app.runtime.RuntimeSetupStatus
import com.vayro.app.runtime.supportsArm64Runtime
import com.vayro.app.ui.AppUpdateStatus
import com.vayro.app.ui.StartupStage
import com.vayro.app.update.AppUpdater
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runtime domain coordinator (Milestone 1).
 *
 * Explicit owner of the runtime state machine: first-launch bootstrap, the
 * [RuntimeSetupService] lifecycle and its [RuntimeSetupController] snapshots,
 * runtime initialization, in-app update checks/installs, onboarding and
 * provider-profile completion, and dev-stack install/update/remove. All work
 * goes through the real [RuntimeInstaller], [AppUpdater] and
 * [RuntimeSetupController]; no simulated state.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged. `buildAndRunAndroidApp`,
 * `startAutonomousIssueWorkflow`, `runAgentPromptAwaitingResult` and
 * `runRealGradleBuildAndTest` stay in the ViewModel — they orchestrate
 * execution rather than runtime setup.
 */
class RuntimeCoordinator(
    private val host: CoordinatorHost,
    private val installer: RuntimeInstaller,
    private val preferences: AppPreferences,
    private val vault: ApiKeyVault,
    private val appUpdater: () -> AppUpdater,
    private val pingApi: () -> Unit,
    private val autoUpdateToolsAndSkills: () -> Unit,
    private val openProject: (Project) -> Unit,
    private val selectAgent: (AgentKind) -> Unit,
    private val refreshActiveApiKey: (ProviderKind) -> Unit,
) {
    @Volatile private var setupCompletionHandled: Boolean = false
    private var appUpdateDownloadJob: Job? = null
    @Volatile private var activeAppUpdater: AppUpdater? = null

    /**
     * Called from the ViewModel's init block; starts the startup state machine.
     */
    fun startBootstrap() {
        host.scope.launch { bootstrap() }
    }

    /**
     * Called from the ViewModel's init block; keeps the startup UI in sync with
     * [RuntimeSetupController] snapshots (including a resumed setup service).
     */
    fun startSetupSnapshotCollection() {
        host.scope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
    }

    private suspend fun bootstrap() {
        if (!supportsArm64Runtime(android.os.Build.SUPPORTED_ABIS, System.getProperty("os.arch"))) {
            host.updateState {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "ARM64 device required",
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            return
        }
        // Milestone 1: surface low-RAM / low-storage conditions early with an
        // actionable message instead of failing mysteriously later. Non-blocking:
        // the install flow reports its own errors; this is an early warning.
        val resourceCheck = com.vayro.app.core.resources.ResourceGate()
            .checkDevice(com.vayro.app.core.resources.AndroidResourceProbe(host.appContext))
        if (!resourceCheck.isSuccess) {
            host.updateState { it.copy(toastMessage = resourceCheck.error) }
        }
        val setupSnapshot = RuntimeSetupController.snapshot.value
        if (setupSnapshot.status == RuntimeSetupStatus.RUNNING) {
            onSetupSnapshot(setupSnapshot)
            resumeRuntimeSetupService()
            return
        }
        val isWarmBoot = preferences.runtimeSetupComplete && preferences.onboardingComplete
        val installed = withContext(Dispatchers.IO) {
            // Upgrades from the old single-bundle layout keep every already-installed tool.
            installer.migrateLegacyToolMarkers()
            installer.isInstalled().also { ready ->
                if (ready) installer.cleanupLegacyWorkspaceScaffolding()
            }
        }
        host.updateState { current ->
            current.copy(
                installedDevStacks = if (installed) installer.installedStacks() else current.installedDevStacks,
                installedAgentVersions = if (installed) installer.installedAgentVersions() else emptyMap(),
            )
        }
        when {
            !installed && setupSnapshot.status == RuntimeSetupStatus.ERROR -> onSetupSnapshot(setupSnapshot)
            !installed -> host.updateState { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupProgress = 0f) }
            !preferences.onboardingComplete -> {
                preferences.runtimeSetupComplete = true
                host.updateState { it.copy(startupStage = StartupStage.MODEL_SETUP, startupProgress = 1f) }
            }
            else -> initializeRuntime(isWarmBoot = isWarmBoot)
        }
    }

    fun startRuntimeSetup() {
        if (host.state().startupStage == StartupStage.INSTALLING) return
        setupCompletionHandled = false
        host.updateState {
            it.copy(
                startupStage = StartupStage.INSTALLING,
                startupProgress = 0.01f,
                startupMessage = "Preparing your private coding workspace",
                startupBytes = null,
                startupLogs = listOf("\$ Preparing your private coding workspace"),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
                showDetailedSetupProgress = true,
            )
        }
        resumeRuntimeSetupService()
    }

    fun cancelRuntimeSetup() {
        if (host.state().startupStage != StartupStage.INSTALLING) return
        ContextCompat.startForegroundService(
            host.appContext,
            Intent(host.appContext, RuntimeSetupService::class.java).setAction(RuntimeSetupService.ACTION_STOP),
        )
    }

    fun retryStartup() {
        if (installer.isInstalled()) host.scope.launch { initializeRuntime() } else {
            host.updateState { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupError = null) }
            startRuntimeSetup()
        }
    }

    fun backToSetup() {
        host.updateState {
            it.copy(
                startupStage = StartupStage.SETUP_REQUIRED,
                startupError = null,
                startupErrorIsOffline = false,
                startupMessage = "Select tools and start setup",
                startupProgress = 0f,
                showDetailedSetupProgress = false,
            )
        }
    }

    private fun resumeRuntimeSetupService() {
        val stacks = host.state().selectedDevStacks.joinToString(",") { it.name }
        ContextCompat.startForegroundService(
            host.appContext,
            Intent(host.appContext, RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks)
                .putExtra(RuntimeSetupService.EXTRA_AGENT, host.state().agentKind.name),
        )
    }

    private fun onSetupSnapshot(snapshot: RuntimeSetupSnapshot) {
        when (snapshot.status) {
            RuntimeSetupStatus.RUNNING -> host.updateState {
                it.copy(
                    startupStage = StartupStage.INSTALLING,
                    startupProgress = snapshot.progress,
                    startupMessage = snapshot.message,
                    startupBytes = snapshot.totalBytes?.let { total -> (snapshot.downloadedBytes ?: 0L) to total },
                    startupLogs = snapshot.logs,
                    startupIndeterminate = snapshot.indeterminate,
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            RuntimeSetupStatus.COMPLETE -> {
                if (setupCompletionHandled) return
                setupCompletionHandled = true
                preferences.runtimeSetupComplete = true
                host.scope.launch {
                    val installedStacks = withContext(Dispatchers.IO) { installer.installedStacks() }
                    host.updateState { it.copy(installedDevStacks = installedStacks) }
                    if (preferences.onboardingComplete) {
                        initializeRuntime()
                    } else {
                        host.updateState {
                            it.copy(
                                startupStage = StartupStage.MODEL_SETUP,
                                startupProgress = 1f,
                                startupBytes = null,
                                startupIndeterminate = false,
                            )
                        }
                    }
                }
            }
            RuntimeSetupStatus.ERROR -> host.updateState {
                it.copy(
                    startupStage = StartupStage.ERROR,
                    startupMessage = snapshot.message,
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                    startupError = snapshot.errorMessage,
                    startupErrorIsOffline = snapshot.offline,
                )
            }
            RuntimeSetupStatus.CANCELLED -> host.updateState {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "Setup paused",
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                )
            }
            RuntimeSetupStatus.IDLE -> Unit
        }
    }

    private suspend fun initializeRuntime(isWarmBoot: Boolean = false) {
        val startedAt = SystemClock.elapsedRealtime()
        if (!isWarmBoot) {
            host.updateState {
                it.copy(
                    startupStage = StartupStage.INITIALIZING,
                    startupProgress = 0.05f,
                    startupMessage = "Opening your private workspace",
                    startupBytes = null,
                    startupLogs = listOf("\$ Opening your private workspace"),
                    startupIndeterminate = false,
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                installer.initializeExisting { progress ->
                    if (!isWarmBoot) {
                        host.updateState { current ->
                            current.copy(
                                startupProgress = 0.05f + progress.fraction * 0.95f,
                                startupMessage = progress.message,
                                startupBytes = null,
                                startupLogs = mergeStartupLog(current.startupLogs, progress),
                            )
                        }
                    }
                }
            }
        }
        if (result.isSuccess) {
            // The real version probe can finish in a fraction of a second on fast phones.
            // Keep the successful loading state visible long enough to be understandable during initial setup.
            if (!isWarmBoot) {
                val remaining = MINIMUM_INITIALIZATION_SCREEN_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) delay(remaining)
            }
            host.updateState {
                it.copy(
                    startupStage = StartupStage.READY,
                    startupProgress = 1f,
                    installedDevStacks = installer.installedStacks(),
                    installedAgentVersions = installer.installedAgentVersions(),
                )
            }
            pingApi()
            checkForAppUpdate()
            autoUpdateToolsAndSkills()
            if (!isWarmBoot || host.state().activeProject == null) {
                preferences.lastActiveProjectId?.let { lastId ->
                    host.state().projects.firstOrNull { it.id == lastId }?.let { lastProject ->
                        openProject(lastProject)
                    }
                }
            }
        } else {
            showStartupError(result.exceptionOrNull() ?: IllegalStateException("Claude Code initialization failed"))
        }
    }

    fun checkForAppUpdate(force: Boolean = false) {
        // Play builds never self-update: Google Play owns updates, so no
        // side-channel check is ever made and no update is ever offered.
        if (BuildConfig.IS_PLAY_BUILD) {
            if (force) {
                host.updateState {
                    it.copy(toastMessage = "App updates are managed by Google Play on this build.")
                }
            }
            return
        }
        if (!force && System.currentTimeMillis() - preferences.lastAppUpdateCheckMillis < 24L * 60L * 60L * 1000L) return
        host.scope.launch(Dispatchers.IO) {
            val result = runCatching { appUpdater().check() }.getOrElse {
                AppUpdater.AppUpdateCheckResult.Failed(it.message?.take(200) ?: "Update check failed")
            }
            when (result) {
                is AppUpdater.AppUpdateCheckResult.Available -> {
                    preferences.lastAppUpdateCheckMillis = System.currentTimeMillis()
                    host.updateState {
                        it.copy(
                            appUpdate = result.info,
                            appUpdateStatus = AppUpdateStatus.AVAILABLE,
                            appUpdateError = null,
                            // A manual check must ALWAYS answer visibly: bump
                            // the prompt nonce so the update dialog re-opens
                            // even when this exact update was already in
                            // state, and say so in a toast as well.
                            appUpdatePromptNonce = if (force) System.currentTimeMillis() else it.appUpdatePromptNonce,
                            toastMessage = if (force) "Kodex v${result.info.versionName} update available" else it.toastMessage,
                        )
                    }
                }
                is AppUpdater.AppUpdateCheckResult.UpToDate -> {
                    preferences.lastAppUpdateCheckMillis = System.currentTimeMillis()
                    if (force) {
                        host.updateState {
                            it.copy(toastMessage = "Kodex is up to date (${BuildConfig.APP_VARIANT} · v${BuildConfig.VERSION_NAME})")
                        }
                    }
                }
                is AppUpdater.AppUpdateCheckResult.Failed -> {
                    // QA Medium: a failed check used to be recorded as
                    // checked for 24h AND reported "up to date" on a forced
                    // check. Neither happens now: the timestamp is left
                    // alone so the next check retries, and a forced check
                    // says the check failed.
                    if (force) {
                        host.updateState {
                            it.copy(toastMessage = "Could not check for updates: ${result.error}")
                        }
                    }
                }
            }
        }
    }

    /** Set or update the in-app update channel / manifest URL. */
    fun setUpdateManifestUrl(url: String) {
        preferences.updateManifestUrlOverride = url.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Reset the in-app update manifest URL to the default release channel. */
    fun clearUpdateManifestUrl() {
        preferences.updateManifestUrlOverride = ""
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Set or update the private GitHub Personal Access Token or Deploy Token. */
    fun setUpdateAuthToken(token: String) {
        preferences.updateAuthToken = token.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    fun updateManifestUrl(): String = preferences.updateManifestUrlOverride
    fun updateAuthToken(): String = preferences.updateAuthToken

    /** Debug builds only: persist a manifest URL override and re-check immediately. */
    fun setDebugUpdateManifestUrl(url: String) {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = url.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: clear the manifest URL override and re-check the default channel. */
    fun clearDebugUpdateManifestUrl() {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = ""
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: the currently-active manifest URL override (empty = default). */
    fun debugUpdateManifestUrl(): String = if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else ""

    fun installAppUpdate() {
        // Defense in depth: appUpdate state can never be set on a Play build
        // (checkForAppUpdate returns early), but never install a side-channel
        // APK from a Play-distributed build even if state is stale.
        if (BuildConfig.IS_PLAY_BUILD) return
        val info = host.state().appUpdate ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !host.appContext.packageManager.canRequestPackageInstalls()) {
            host.updateState { it.copy(appUpdateStatus = AppUpdateStatus.PERMISSION_REQUIRED) }
            return
        }
        if (host.state().appUpdateStatus == AppUpdateStatus.DOWNLOADING) return
        // Installing relaunches the process — an in-flight agent task
        // would be killed with no warning. The offer stays; the user can
        // install after stopping the task.
        if (host.state().isRunning) {
            host.updateState {
                it.copy(
                    appUpdateStatus = AppUpdateStatus.ERROR,
                    appUpdateError = "An agent task is running — installing the update now would kill it. Stop the task first, then install.",
                )
            }
            return
        }
        host.updateState {
            it.copy(appUpdateStatus = AppUpdateStatus.DOWNLOADING, appUpdateDownloadedBytes = 0L, appUpdateTotalBytes = info.sizeBytes, appUpdateError = null)
        }
        appUpdateDownloadJob?.cancel()
        val updater = appUpdater()
        activeAppUpdater = updater
        val job = host.scope.launch(Dispatchers.IO) {
            val result = runCatching {
                updater.download(info) { downloaded, total ->
                    host.updateState { current -> current.copy(appUpdateDownloadedBytes = downloaded, appUpdateTotalBytes = total) }
                }
            }
            activeAppUpdater = null
            if (!isActive) return@launch
            result.onSuccess { apk ->
                host.updateState { it.copy(appUpdateStatus = AppUpdateStatus.INSTALLING) }
                runCatching { AndroidAppInstaller.install(host.appContext, apk) }.onFailure { error ->
                    host.updateState { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Could not start the Android installer") }
                }
            }.onFailure { error ->
                host.updateState { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Update download failed") }
            }
        }
        appUpdateDownloadJob = job
        job.invokeOnCompletion { if (appUpdateDownloadJob === job) appUpdateDownloadJob = null }
    }

    fun cancelAppUpdateDownload() {
        if (host.state().appUpdateStatus != AppUpdateStatus.DOWNLOADING) return
        appUpdateDownloadJob?.cancel(CancellationException("App update download cancelled"))
        appUpdateDownloadJob = null
        activeAppUpdater?.cancelDownload()
        activeAppUpdater = null
        host.updateState {
            it.copy(appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null, appUpdateDownloadedBytes = 0L)
        }
    }

    fun dismissAppUpdateError() {
        // Dismissing a FAILED update withdraws the offer entirely: resetting
        // to AVAILABLE (the old behavior) re-offered the same update that
        // just failed verification/download, looping the user back into
        // the same failure. The offer returns on the next update check.
        host.updateState { it.copy(appUpdate = null, appUpdateStatus = null, appUpdateError = null) }
    }

    private fun mergeStartupLog(
        existing: List<String>,
        progress: RuntimeInstallProgress,
    ): List<String> {
        val prefix = "\$ ${progress.message}"
        val bytes = progress.totalBytes?.let { total ->
            val downloaded = progress.downloadedBytes ?: 0L
            " — %.1f / %.1f MB".format(downloaded / 1_048_576.0, total / 1_048_576.0)
        }.orEmpty()
        val nextLine = prefix + bytes
        val updated = if (existing.lastOrNull()?.startsWith(prefix) == true) {
            existing.dropLast(1) + nextLine
        } else {
            existing + nextLine
        }
        return updated.takeLast(80)
    }

    private fun showStartupError(error: Throwable) {
        val isOffline = generateSequence(error as Throwable?) { it.cause }
            .any { cause ->
                cause is UnknownHostException ||
                    cause.message.orEmpty().contains("unable to resolve host", ignoreCase = true) ||
                    cause.message.orEmpty().contains("no address associated with hostname", ignoreCase = true)
            }
        val message = if (isOffline) {
            "Connect to Wi-Fi or mobile data, then try again. Internet is required to finish the first-time setup."
        } else {
            error.message?.take(300) ?: "Something went wrong while preparing Kodex. Please try again."
        }
        host.updateState {
            it.copy(
                startupStage = StartupStage.ERROR,
                startupError = message,
                startupErrorIsOffline = isOffline,
            )
        }
    }

    fun finishOnboarding(profile: ProviderProfile, secret: String) {
        vault.put(profile.kind.name, secret)
        val saved = profile.copy(
            hasSecret = secret.isNotBlank() || vault.contains(profile.kind.name),
        )
        preferences.saveProvider(saved, host.state().agentKind)
        preferences.onboardingComplete = true
        host.updateState { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }
        refreshActiveApiKey(profile.kind)
        pingApi()
        autoUpdateToolsAndSkills()
    }

    fun finishAntigravityOnboarding() {
        check(host.state().antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
            "Sign in to Antigravity first"
        }
        preferences.onboardingComplete = true
        host.updateState { it.copy(onboardingComplete = true, startupStage = StartupStage.READY) }
        autoUpdateToolsAndSkills()
    }

    /** Lets first-run users escape a provider/login failure without losing saved credentials. */
    fun chooseOnboardingAgent(kind: AgentKind) {
        selectAgent(kind)
        host.updateState {
            it.copy(
                startupStage = if (installer.isAgentInstalled(kind)) {
                    StartupStage.MODEL_SETUP
                } else {
                    StartupStage.SETUP_REQUIRED
                },
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
    }

    fun updateProvider(profile: ProviderProfile, secret: String) = finishOnboarding(profile, secret)

    fun finishBackgroundSetup() {
        preferences.backgroundSetupComplete = true
        host.updateState {
            it.copy(
                backgroundSetupComplete = true,
                showAppTour = !preferences.appTourCompleted,
            )
        }
    }

    fun finishAppTour() {
        preferences.appTourCompleted = true
        host.updateState { it.copy(appTourCompleted = true, showAppTour = false) }
    }

    fun replayAppTour() {
        host.updateState { it.copy(showAppTour = true) }
    }

    /** Called from the first-launch tool picker; persists the choice for setup and Settings. */
    fun toggleDevStack(stack: DevStack) {
        if (stack == DevStack.WEB) return
        val updated = host.state().selectedDevStacks.toMutableSet().apply {
            if (!add(stack)) remove(stack)
        }
        preferences.selectedDevStacks = updated.map { it.name }.toSet()
        host.updateState { it.copy(selectedDevStacks = updated) }
    }

    /** Installs one development stack on demand (Settings) with live progress. */
    fun installDevStack(stack: DevStack) {
        if (host.state().devStackInstalling != null) return
        host.updateState {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = false,
                devStackMessage = "Preparing ${stack.label}…",
                devStackProgress = 0f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        host.scope.launch {
            var sampleBytes = 0L
            var sampleTime = android.os.SystemClock.elapsedRealtime()
            var latestSpeed: Long? = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.ensureStackInstalled(stack) { progress ->
                        val transfer = progress.totalBytes?.let { total ->
                            (progress.downloadedBytes ?: 0L) to total
                        }
                        if (transfer != null) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            val elapsed = now - sampleTime
                            val delta = transfer.first - sampleBytes
                            if (delta < 0L) {
                                sampleBytes = transfer.first
                                sampleTime = now
                                latestSpeed = null
                            } else if (elapsed >= 500L) {
                                latestSpeed = (delta * 1_000L / elapsed).coerceAtLeast(0L)
                                sampleBytes = transfer.first
                                sampleTime = now
                            }
                        } else {
                            sampleBytes = 0L
                            sampleTime = android.os.SystemClock.elapsedRealtime()
                            latestSpeed = null
                        }
                        host.updateState { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                                devStackBytes = transfer,
                                devStackBytesPerSecond = latestSpeed,
                            )
                        }
                    }
                }
            }
            host.updateState { current ->
                current.copy(
                    devStackInstalling = null,
                    devStackRemoving = false,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks + stack else current.installedDevStacks,
                    devStackProgress = 0f,
                    devStackBytes = null,
                    devStackBytesPerSecond = null,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} tools are ready" },
                        onFailure = { error -> error.message?.take(200) ?: "Could not install ${stack.label}" },
                    ),
                    toastMessage = result.fold(
                        onSuccess = { "${stack.label} tools are ready" },
                        onFailure = { error -> error.message?.take(200) ?: "Could not install ${stack.label}" },
                    ),
                )
            }
        }
    }

    /** Updates an installed optional toolchain in place. */
    fun updateDevStack(stack: DevStack) {
        if (host.state().devStackInstalling != null || stack == DevStack.WEB) return
        if (stack !in host.state().installedDevStacks) return
        if (host.state().isRunning || host.state().projectTerminalRunning) {
            host.updateState { it.copy(toastMessage = "Stop running tasks and terminal commands before updating tools") }
            return
        }
        host.updateState {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = false,
                devStackMessage = "Updating ${stack.label}…",
                devStackProgress = 0f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        host.scope.launch {
            var sampleBytes = 0L
            var sampleTime = android.os.SystemClock.elapsedRealtime()
            var latestSpeed: Long? = null
            var wasAlreadyUpToDate = false
            var finalProgressMessage: String? = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.updateStack(stack) { progress ->
                        if (progress.message.contains("up to date", ignoreCase = true)) {
                            wasAlreadyUpToDate = true
                        }
                        finalProgressMessage = progress.message
                        val transfer = progress.totalBytes?.let { total ->
                            (progress.downloadedBytes ?: 0L) to total
                        }
                        if (transfer != null) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            val elapsed = now - sampleTime
                            val delta = transfer.first - sampleBytes
                            if (delta < 0L) {
                                sampleBytes = transfer.first
                                sampleTime = now
                                latestSpeed = null
                            } else if (elapsed >= 500L) {
                                latestSpeed = (delta * 1_000L / elapsed).coerceAtLeast(0L)
                                sampleBytes = transfer.first
                                sampleTime = now
                            }
                        }
                        host.updateState { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                                devStackBytes = transfer,
                                devStackBytesPerSecond = latestSpeed,
                            )
                        }
                    }
                }
            }
            host.updateState { current ->
                val finalMsg = result.fold(
                    onSuccess = {
                        finalProgressMessage?.takeIf { it.isNotBlank() }
                            ?: if (wasAlreadyUpToDate) "${stack.label} is already up to date" else "${stack.label} updated successfully"
                    },
                    onFailure = { error -> error.message?.take(200) ?: "Could not update ${stack.label}" },
                )
                current.copy(
                    devStackInstalling = null,
                    devStackRemoving = false,
                    devStackProgress = 0f,
                    devStackBytes = null,
                    devStackBytesPerSecond = null,
                    devStackMessage = finalMsg,
                    toastMessage = result.fold(
                        onSuccess = {
                            if (wasAlreadyUpToDate) "${stack.label} is already up to date" else "${stack.label} updated"
                        },
                        onFailure = { "Could not update ${stack.label}" },
                    ),
                    installedDevStacks = installer.installedStacks(),
                )
            }
        }
    }

    /** Removes an optional toolchain after the Settings confirmation dialog. */
    fun removeDevStack(stack: DevStack) {
        if (host.state().devStackInstalling != null || stack == DevStack.WEB) return
        if (host.state().isRunning || host.state().projectTerminalRunning) {
            host.updateState { it.copy(toastMessage = "Stop running tasks and terminal commands before removing tools") }
            return
        }
        host.updateState {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = true,
                devStackMessage = "Removing ${stack.label}…",
                devStackProgress = 0.1f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        host.scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.removeStack(stack) { progress ->
                        host.updateState { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            if (result.isSuccess) {
                val selected = host.state().selectedDevStacks - stack
                preferences.selectedDevStacks = selected.map { it.name }.toSet()
            }
            host.updateState { current ->
                current.copy(
                    selectedDevStacks = if (result.isSuccess) current.selectedDevStacks - stack else current.selectedDevStacks,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks - stack else current.installedDevStacks,
                    devStackInstalling = null,
                    devStackRemoving = false,
                    devStackProgress = 0f,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} removed" },
                        onFailure = { result.exceptionOrNull()?.message?.take(200) ?: "Could not remove ${stack.label}" },
                    ),
                    toastMessage = result.fold(
                        onSuccess = { "${stack.label} removed" },
                        onFailure = { "Could not remove ${stack.label}" },
                    ),
                )
            }
        }
    }

    companion object {
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
    }
}
