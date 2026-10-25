package com.vayro.app.ui.coordinators

import com.vayro.app.data.AppPreferences
import com.vayro.app.factory.blueprint.AppFactoryBlueprintEngine
import com.vayro.app.factory.build.AppBuildOrchestrator
import com.vayro.app.factory.build.GitHubCiBuildRunner
import com.vayro.app.factory.build.PlayStorePreparationEngine
import com.vayro.app.factory.build.SigningAndVersionManager
import com.vayro.app.factory.generators.AndroidComposeGenerator
import com.vayro.app.factory.generators.ApiIntegrationGenerator
import com.vayro.app.factory.generators.BackendServiceGenerator
import com.vayro.app.factory.generators.DatabaseSetupEngine
import com.vayro.app.factory.generators.FlutterAppGenerator
import com.vayro.app.factory.generators.ReactNativeAppGenerator
import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.AppFramework
import com.vayro.app.factory.model.BuildArtifactType
import com.vayro.app.factory.model.BuildMode
import com.vayro.app.factory.model.BuildResult
import com.vayro.app.factory.model.VersionBumpType
import com.vayro.app.model.Project
import com.vayro.app.network.GitHubClient
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.ui.GitHubAuthStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App Factory domain coordinator (Milestone 1).
 *
 * Explicit owner of the app-factory state machine: blueprint generation,
 * project scaffolding, real builds (via [AppBuildOrchestrator] with genuine
 * exit codes — Milestone 0), APK installation, release keystore creation,
 * version bumps and Play Store listing preparation. Failures surface as
 * honest messages, never fake success.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class AppFactoryCoordinator(
    private val host: CoordinatorHost,
    private val workspaceRootFor: (Project) -> File,
    private val refreshProjectFiles: () -> Unit,
    private val installer: RuntimeInstaller,
    private val gitHubClient: GitHubClient,
    private val preferences: AppPreferences,
    private val gitHubTokenProvider: () -> String,
) {
    fun generateAppFactoryBlueprint(
        prompt: String,
        framework: AppFramework = AppFramework.ANDROID_COMPOSE,
        buildMode: BuildMode = BuildMode.ON_DEVICE,
        archetype: AppFactoryBlueprintEngine.Archetype? = null,
    ) {
        host.scope.launch {
            host.updateState { it.copy(appFactoryGeneratingBlueprint = true, appFactoryMessage = "Synthesizing software blueprint...") }
            try {
                val blueprint = AppFactoryBlueprintEngine.createBlueprint(
                    prompt = prompt,
                    framework = framework,
                    buildMode = buildMode,
                    forcedArchetype = archetype,
                )
                host.updateState {
                    it.copy(
                        appFactoryBlueprint = blueprint,
                        appFactoryGeneratingBlueprint = false,
                        appFactoryMessage = "Blueprint '${blueprint.name}' generated successfully."
                    )
                }
            } catch (e: Exception) {
                host.updateState {
                    it.copy(
                        appFactoryGeneratingBlueprint = false,
                        appFactoryMessage = "Blueprint generation failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun scaffoldAppFactoryProject(
        targetDirectory: File? = null,
        blueprint: AppBlueprint? = host.state().appFactoryBlueprint
    ) {
        val bp = blueprint ?: return
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        host.scope.launch {
            host.updateState { it.copy(appFactoryScaffolding = true, appFactoryMessage = "Scaffolding ${bp.framework.displayName} files...") }
            try {
                val files = mutableListOf<File>()
                when (bp.framework) {
                    AppFramework.ANDROID_COMPOSE -> {
                        files += AndroidComposeGenerator.generateProject(projectDir, bp)
                    }
                    AppFramework.FLUTTER -> {
                        files += FlutterAppGenerator.generateProject(projectDir, bp)
                    }
                    AppFramework.REACT_NATIVE -> {
                        files += ReactNativeAppGenerator.generateProject(projectDir, bp)
                    }
                    AppFramework.FULLSTACK_BACKEND -> {
                        files += BackendServiceGenerator.generateProject(projectDir, bp)
                    }
                }
                files += DatabaseSetupEngine.generateDatabaseBundle(projectDir, bp)
                files += ApiIntegrationGenerator.generateApiClientBundle(projectDir, bp)

                host.updateState {
                    it.copy(
                        appFactoryScaffolding = false,
                        appFactoryScaffoldFiles = files,
                        appFactoryMessage = "Scaffolded ${files.size} project files in ${projectDir.name}."
                    )
                }
                refreshProjectFiles()
            } catch (e: Exception) {
                host.updateState {
                    it.copy(
                        appFactoryScaffolding = false,
                        appFactoryMessage = "Scaffolding failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun buildAppFactoryApp(
        artifactType: BuildArtifactType = BuildArtifactType.APK_DEBUG,
        buildMode: BuildMode = BuildMode.ON_DEVICE,
        targetDirectory: File? = null
    ) {
        val bp = host.state().appFactoryBlueprint ?: return
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        host.scope.launch {
            host.updateState {
                it.copy(
                    appFactoryBuilding = true,
                    appFactoryLogs = listOf("Starting build: ${artifactType.displayName} via ${buildMode.displayName}..."),
                    appFactoryMessage = "Building ${artifactType.displayName}..."
                )
            }
            try {
                val result = if (buildMode == BuildMode.GITHUB_ACTIONS) {
                    runGitHubCiBuild(bp, artifactType, projectDir)
                } else {
                    AppBuildOrchestrator.runBuild(
                        projectDir = projectDir,
                        blueprint = bp,
                        artifactType = artifactType,
                        buildMode = buildMode
                    )
                }
                host.updateState {
                    it.copy(
                        appFactoryBuilding = false,
                        appFactoryBuildResult = result,
                        appFactoryLogs = result.logs,
                        appFactoryMessage = if (result.success) "Build completed successfully!" else "Build failed: ${result.errorMessage}"
                    )
                }
            } catch (e: Exception) {
                host.updateState {
                    it.copy(
                        appFactoryBuilding = false,
                        appFactoryMessage = "Build error: ${e.message}",
                        appFactoryLogs = it.appFactoryLogs + "Error: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * One-tap GitHub CI build: verifies the GitHub connection, then runs the
     * full push → Actions run → artifact download pipeline in
     * [GitHubCiBuildRunner]. Without a connected GitHub account the build
     * stops immediately with one clear instruction.
     */
    private suspend fun runGitHubCiBuild(
        bp: AppBlueprint,
        artifactType: BuildArtifactType,
        projectDir: File,
    ): BuildResult {
        val login = host.state().githubLogin?.takeIf(String::isNotBlank) ?: preferences.githubLogin
        val connected = host.state().githubAuthStatus == GitHubAuthStatus.CONNECTED && login.isNotBlank()
        val token = if (connected) withContext(Dispatchers.IO) { gitHubTokenProvider() } else ""
        if (!connected || token.isBlank()) {
            val message = "GitHub is not connected. Connect GitHub first (Home → GitHub → Connect GitHub), then tap Build with GitHub CI again."
            return BuildResult(
                success = false,
                artifactType = artifactType,
                buildMode = BuildMode.GITHUB_ACTIONS,
                logs = listOf("Starting build: ${artifactType.displayName} via GitHub Actions CI...", "ERROR: $message"),
                errorMessage = message,
            )
        }
        val runner = GitHubCiBuildRunner(
            installer = installer,
            gitHubClient = gitHubClient,
            cacheDir = host.appContext.cacheDir,
        )
        return withContext(Dispatchers.IO) {
            runner.build(
                projectDir = projectDir,
                blueprint = bp,
                artifactType = artifactType,
                login = login,
                token = token,
                onLog = { line ->
                    host.updateState { it.copy(appFactoryLogs = it.appFactoryLogs + line) }
                },
            )
        }
    }

    fun installBuiltApk(apkFile: File) {
        try {
            AppBuildOrchestrator.installApk(host.appContext, apkFile)
            host.updateState { it.copy(appFactoryMessage = "Launched package installer for ${apkFile.name}") }
        } catch (e: Exception) {
            host.updateState { it.copy(appFactoryMessage = "Installation error: ${e.message}") }
        }
    }

    fun generateReleaseKeystore(
        targetDirectory: File? = null,
        alias: String = "releaseKey",
        pass: String
    ) {
        val bp = host.state().appFactoryBlueprint
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp?.name?.lowercase()?.replace(" ", "_") ?: "app"}")

        try {
            val config = SigningAndVersionManager.createKeystore(
                projectDir = projectDir,
                keyAlias = alias,
                storePassword = pass,
                keyPassword = pass
            )
            host.updateState {
                it.copy(
                    appFactoryKeystoreConfig = config,
                    appFactoryMessage = "2048-bit Keystore generated: ${config.keystoreFile.name}"
                )
            }
        } catch (e: Exception) {
            host.updateState { it.copy(appFactoryMessage = "Keystore generation failed: ${e.message}") }
        }
    }

    fun bumpAppVersion(
        targetDirectory: File? = null,
        bumpType: VersionBumpType = VersionBumpType.PATCH
    ) {
        val bp = host.state().appFactoryBlueprint
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp?.name?.lowercase()?.replace(" ", "_") ?: "app"}")

        // Crashlytics ENOENT fix: a missing/unwritable project folder used to
        // throw out of this click handler uncaught. Surface it as a message.
        try {
            val (newCode, newName) = SigningAndVersionManager.bumpVersion(projectDir, bumpType)
            host.updateState { state ->
                val updatedBp = state.appFactoryBlueprint?.let {
                    it.copy(buildConfig = it.buildConfig.copy(versionCode = newCode, versionName = newName))
                }
                state.copy(
                    appFactoryBlueprint = updatedBp,
                    appFactoryMessage = "Bumped version to $newName (code $newCode)"
                )
            }
        } catch (e: Exception) {
            host.updateState {
                it.copy(appFactoryMessage = "Could not save version: project folder is missing or unavailable (${e.message}). Create or open the project and try again.")
            }
        }
    }

    fun preparePlayStoreListing(
        targetDirectory: File? = null,
        blueprint: AppBlueprint? = host.state().appFactoryBlueprint
    ) {
        val bp = blueprint ?: return
        val projectDir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: File(host.appContext.filesDir, "projects/${bp.name.lowercase().replace(" ", "_")}")

        try {
            val bundle = PlayStorePreparationEngine.prepareListingBundle(projectDir, bp)
            host.updateState {
                it.copy(
                    appFactoryStoreBundle = bundle,
                    appFactoryMessage = "Play Store listing metadata bundle exported to ${bundle.listingDir.name}"
                )
            }
        } catch (e: Exception) {
            host.updateState { it.copy(appFactoryMessage = "Play Store preparation failed: ${e.message}") }
        }
    }
}
