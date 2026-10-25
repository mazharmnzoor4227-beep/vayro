package com.vayro.app.ui.coordinators.github

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.vayro.app.data.AppPreferences
import com.vayro.app.model.Project
import com.vayro.app.model.ProjectChat
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.generateQuickChatIdentity
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubRepository
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.runtime.readTailText
import com.vayro.app.ui.coordinators.CoordinatorHost
import com.vayro.app.ui.GitHubAuthStatus
import com.vayro.app.ui.sanitizeTerminalOutput
import com.vayro.app.workspace.GitDomainController
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GitHub authentication + repository-clone coordinator (Milestone 1).
 *
 * Owns the GitHub device-flow sign-in state machine and repository cloning.
 * The official `gh` CLI runs inside the Linux runtime; credentials stay in
 * the app's private Linux home (never exported). Part of the Git domain.
 */
class GitHubAuthCoordinator(
    private val host: CoordinatorHost,
    private val gitHubClient: GitHubClient,
    private val installer: RuntimeInstaller,
    private val preferences: AppPreferences,
    private val workspaceRootFor: (Project) -> File,
    private val openProject: (Project) -> Unit,
) {
    @Volatile private var githubAuthProcess: Process? = null
    private var githubAuthJob: Job? = null

    // In-flight `git clone` / `gh repo clone` handles. Kept so the user can
    // cancel a stalled clone from the dialogs instead of being trapped by a
    // blocked dismiss (the clone keeps running detached when a dialog is
    // merely dismissed — dismissing never cancels).
    @Volatile private var cloneProcess: Process? = null
    private var cloneJob: Job? = null
    @Volatile private var cloneCancelled = false

    fun startGitHubLogin() {
        if (host.state().githubAuthStatus == GitHubAuthStatus.STARTING || host.state().githubAuthStatus == GitHubAuthStatus.AWAITING_USER) return
        host.updateState { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Preparing official GitHub sign-in…") }
        startGitHubForegroundOperation()
        githubAuthJob = host.scope.launch {
            try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.ensureGitHubCliInstalled { progress ->
                        host.updateState { it.copy(githubMessage = progress.message) }
                    }
                    val runtime = installer.installedRuntime()
                    val outputFile = File(host.appContext.cacheDir, "github-auth-${System.nanoTime()}.log")
                    val workspace = File(host.appContext.filesDir, "workspaces/github-auth").apply { mkdirs() }
                    val process = installer.process(
                        runtime.proot,
                        runtime.rootfs,
                        workspace,
                        githubCliEnvironment(),
                        listOf(
                            RuntimeInstaller.GITHUB_CLI_GUEST_PATH,
                            "auth", "login",
                            "--hostname", "github.com",
                            "--git-protocol", "https",
                            "--web",
                            "--insecure-storage",
                        ),
                        guestWorkspacePath = "/workspace/github-auth",
                        outputFile = outputFile,
                        extraBinds = installer.githubCredentialBinds(),
                    )
                    githubAuthProcess = process
                    var offset = 0L
                    val captured = StringBuilder()
                    var browserOpened = false
                    try {
                        while (process.isAlive || outputFile.length() > offset) {
                            if (outputFile.length() > offset) {
                                val count = (outputFile.length() - offset).coerceAtMost(16L * 1024).toInt()
                                val bytes = ByteArray(count)
                                RandomAccessFile(outputFile, "r").use { file -> file.seek(offset); file.readFully(bytes) }
                                offset += count
                                captured.append(bytes.toString(Charsets.UTF_8))
                                val clean = sanitizeTerminalOutput(captured.toString()).takeLast(20_000)
                                val code = GITHUB_DEVICE_CODE.find(clean)?.value
                                if (code != null && !browserOpened) {
                                    browserOpened = true
                                    host.updateState {
                                        it.copy(
                                            githubAuthStatus = GitHubAuthStatus.AWAITING_USER,
                                            githubUserCode = code,
                                            githubVerificationUri = GITHUB_DEVICE_URL,
                                            githubMessage = "Enter this one-time code on GitHub",
                                        )
                                    }
                                    openExternalUrl(GITHUB_DEVICE_URL)
                                }
                            } else {
                                delay(100)
                            }
                        }
                        val exit = process.waitFor()
                        check(exit == 0) {
                            sanitizeTerminalOutput(captured.toString()).lineSequence().lastOrNull { it.isNotBlank() }
                                ?: "GitHub sign-in failed (exit $exit)"
                        }
                    } finally {
                        githubAuthProcess = null
                        outputFile.delete()
                    }
                    githubAccountLogin() ?: error("GitHub connected, but the account could not be identified")
                }
            }
            result.onSuccess { login ->
                preferences.githubLogin = login
                host.updateState {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.CONNECTED,
                        githubLogin = login,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = "Connected as @$login",
                    )
                }
                refreshGitHubRepositories()
            }.onFailure { error ->
                host.updateState {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = error.message?.take(240) ?: "GitHub sign-in failed",
                    )
                }
            }
            } finally {
                stopGitHubForegroundOperation()
                githubAuthJob = null
            }
        }
    }

    fun generateNewGitHubCode() {
        githubAuthProcess?.destroy()
        githubAuthJob?.cancel()
        githubAuthProcess = null
        githubAuthJob = null
        stopGitHubForegroundOperation()
        host.updateState {
            it.copy(
                githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                githubUserCode = null,
                githubVerificationUri = null,
                githubMessage = "Generating a new GitHub code…",
            )
        }
        startGitHubLogin()
    }

    fun refreshGitHubRepositories() {
        if (host.state().githubAuthStatus != GitHubAuthStatus.CONNECTED) return
        if (host.state().githubRepositoriesLoading) return
        host.updateState { it.copy(githubRepositoriesLoading = true, githubMessage = "Loading repositories…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { githubRepositoriesFromCli() } }
            result.onSuccess { repositories ->
                host.updateState {
                    it.copy(
                        githubRepositories = repositories,
                        githubRepositoriesLoading = false,
                        githubMessage = "${repositories.size} repositories available",
                    )
                }
            }.onFailure { error ->
                host.updateState {
                    it.copy(
                        githubRepositoriesLoading = false,
                        githubMessage = error.message ?: "Could not load GitHub repositories",
                    )
                }
            }
        }
    }

    fun disconnectGitHub() {        if (host.state().githubAuthStatus == GitHubAuthStatus.STARTING) return
        val login = host.state().githubLogin
        host.updateState { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Signing out of GitHub…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val command = buildList {
                        addAll(listOf("auth", "logout", "--hostname", "github.com"))
                        login?.takeIf(String::isNotBlank)?.let { addAll(listOf("--user", it)) }
                    }
                    val output = runGitHubCli(command)
                    check(output.first == 0) { output.second.lineSequence().lastOrNull { it.isNotBlank() } ?: "GitHub logout failed" }
                }
            }
            result.onSuccess {
                preferences.githubLogin = ""
                host.updateState {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                        githubLogin = null,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubRepositories = emptyList(),
                        githubMessage = "Signed out",
                    )
                }
            }.onFailure { error ->
                host.updateState { it.copy(githubAuthStatus = GitHubAuthStatus.ERROR, githubMessage = error.message ?: "Could not sign out") }
            }
        }
    }

    suspend fun refreshGitHubConnection() = withContext(Dispatchers.IO) {
        if (!installer.isGitHubCliInstalled()) return@withContext
        val login = runCatching { githubAccountLogin() }.getOrNull()
        if (login.isNullOrBlank()) {
            preferences.githubLogin = ""
            host.updateState { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubLogin = null) }
        } else {
            preferences.githubLogin = login
            host.updateState {
                it.copy(
                    githubAuthStatus = GitHubAuthStatus.CONNECTED,
                    githubLogin = login,
                    githubMessage = "Connected as @$login",
                )
            }
        }
    }

    fun clonePublicGitRepository(url: String) {
        cloneGitRepository(url = url, repositoryName = null, branch = null, useGitHubCli = false)
    }

    fun cloneGitHubRepository(repository: GitHubRepository) {
        if (host.state().githubAuthStatus != GitHubAuthStatus.CONNECTED) {
            host.updateState { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubMessage = "Connect GitHub again") }
            return
        }
        cloneGitRepository(repository.cloneUrl, repository.fullName, repository.defaultBranch, useGitHubCli = true)
    }

    /**
     * Cancels an in-flight repository clone, if any. Destroys the git/gh
     * child process and cancels the clone coroutine; the clone's failure
     * path then reports "Clone cancelled" and deletes the partial
     * workspace. No-op when no clone is running.
     */
    fun cancelGitClone() {
        if (!host.state().gitCloneRunning) return
        cloneCancelled = true
        cloneProcess?.destroyForcibly()
        cloneJob?.cancel()
    }

    private fun cloneGitRepository(url: String, repositoryName: String?, branch: String?, useGitHubCli: Boolean) {
        if (host.state().gitCloneRunning || host.state().projectImporting || host.state().isRunning || host.state().projectTerminalRunning) return
        val normalized = runCatching { validateGitUrl(url) }.getOrElse { error ->
            host.updateState { it.copy(toastMessage = error.message ?: "Enter a valid public HTTPS Git URL") }
            return
        }
        cloneCancelled = false
        host.updateState { it.copy(gitCloneRunning = true, gitCloneMessage = "Connecting to Git repository…") }
        cloneJob = host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val identity = generateQuickChatIdentity(host.state().projects.mapTo(mutableSetOf()) { it.slug })
                    val projectId = UUID.randomUUID().toString()
                    val workspace = File(host.appContext.filesDir, "workspaces/$projectId").apply { mkdirs() }
                    val output = File(host.appContext.cacheDir, "git-clone-${System.nanoTime()}.log")
                    try {
                        val environment = mutableMapOf(
                            "GIT_TERMINAL_PROMPT" to "0",
                            "GIT_LFS_SKIP_SMUDGE" to "1",
                            "GH_PROMPT_DISABLED" to "1",
                            "GH_NO_UPDATE_NOTIFIER" to "1",
                        )
                        val installed = installer.installedRuntime()
                        val command = if (useGitHubCli && repositoryName != null) {
                            buildList {
                                addAll(listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH, "repo", "clone", repositoryName, ".", "--", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                            }
                        } else {
                            buildList {
                                addAll(listOf("git", "clone", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                                add(normalized)
                                add(".")
                            }
                        }
                        host.updateState { it.copy(gitCloneMessage = "Cloning ${repositoryName ?: normalized.substringAfterLast('/').removeSuffix(".git")}…") }
                        val process = installer.process(
                            installed.proot,
                            installed.rootfs,
                            workspace,
                            environment,
                            command,
                            guestWorkspacePath = "/workspace/${identity.slug}",
                            outputFile = output,
                            extraBinds = if (useGitHubCli && repositoryName != null) installer.githubCredentialBinds() else emptyList(),
                        )
                        cloneProcess = process
                        val exit = process.waitFor()
                        val details = output.readText().trim()
                        check(exit == 0) { details.takeLast(600).ifBlank { "Git clone failed with exit code $exit" } }
                        val metadata = detectImportedProjectMetadata(workspace)
                        Project(
                            id = projectId,
                            name = identity.displayName,
                            description = repositoryName?.let { "GitHub · $it" } ?: "Imported Git repository",
                            language = metadata.second,
                            slug = identity.slug,
                            kind = ProjectKind.QUICK_PROJECT,
                        )
                    } catch (error: Throwable) {
                        workspace.deleteRecursively()
                        throw error
                    } finally {
                        cloneProcess = null
                        cloneJob = null
                        output.delete()
                    }
                }
            }
            result.onSuccess { project ->
                val chat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(chat))
                host.updateState { current -> current.copy(projects = listOf(project) + current.projects) }
                preferences.saveProjects(host.state().projects)
                openProject(project)
                host.updateState { it.copy(gitCloneRunning = false, gitCloneMessage = null, toastMessage = "Repository cloned successfully") }
            }.onFailure { error ->
                // runCatching swallows CancellationException, so the flag —
                // not the exception type — is what tells us the user cancelled.
                val wasCancelled = cloneCancelled
                cloneCancelled = false
                host.updateState {
                    it.copy(
                        gitCloneRunning = false,
                        gitCloneMessage = null,
                        toastMessage = if (wasCancelled) "Clone cancelled" else "Clone failed: ${error.message?.lineSequence()?.lastOrNull()?.take(180) ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    private fun validateGitUrl(value: String): String {
        val clean = value.trim()
        val uri = URI(clean)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS Git URLs are supported" }
        require(uri.userInfo == null && uri.fragment == null && uri.host?.isNotBlank() == true) { "Enter a valid HTTPS Git URL without credentials" }
        require(uri.host != "localhost" && uri.host != "127.0.0.1" && uri.host != "::1") { "Local Git URLs are not supported" }
        require(uri.path.count { it == '/' } >= 2) { "The URL must identify a Git repository" }
        return uri.toASCIIString()
    }

    private fun detectImportedProjectMetadata(root: File): Pair<String, String> {
        val names = root.walkTopDown().maxDepth(3).filter(File::isFile).map { it.name.lowercase() }.toSet()
        return when {
            names.any { it == "settings.gradle.kts" || it == "build.gradle.kts" } -> "Imported Gradle project" to "Kotlin"
            names.any { it == "settings.gradle" || it == "build.gradle" } -> "Imported Gradle project" to "Java"
            "package.json" in names && names.any { it == "tsconfig.json" || it.endsWith(".ts") || it.endsWith(".tsx") } -> "Imported web project" to "TypeScript"
            "package.json" in names -> "Imported web project" to "JavaScript"
            names.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") } -> "Imported Python project" to "Python"
            names.any { it == "cargo.toml" || it.endsWith(".rs") } -> "Imported Rust project" to "Rust"
            names.any { it == "go.mod" || it.endsWith(".go") } -> "Imported Go project" to "Go"
            else -> "Imported ZIP project" to "General"
        }
    }

    private fun githubCliEnvironment(): Map<String, String> = mapOf(
        "GH_PROMPT_DISABLED" to "1",
        "GH_NO_UPDATE_NOTIFIER" to "1",
        // Android PRoot has no Secret Service. The gh-owned credential file is
        // kept in app-private credential storage and bind-mounted only into
        // this coordinator's own gh invocations, never into agent sessions.
        "BROWSER" to "/bin/false",
    )

    /** Runs the official gh CLI inside the Linux runtime; public for the autonomous workflow's token resolution. */
    fun runGitHubCli(arguments: List<String>): Pair<Int, String> {
        check(installer.isGitHubCliInstalled()) { "GitHub CLI is not installed" }
        // Crashlytics fix: this call sat outside the try below, so a core
        // that vanished after the check threw out of this public call. Return
        // a graceful failure instead, matching the setup guidance elsewhere.
        val runtime = installer.installedRuntimeOrNull()
            ?: return 1 to "Linux runtime is not set up yet. Open the terminal once so setup can finish, then try again."
        val outputFile = File(host.appContext.cacheDir, "github-cli-${System.nanoTime()}.log")
        val workspace = File(host.appContext.filesDir, "workspaces/github-auth").apply { mkdirs() }
        return try {
            val process = installer.process(
                runtime.proot,
                runtime.rootfs,
                workspace,
                githubCliEnvironment(),
                listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH) + arguments,
                guestWorkspacePath = "/workspace/github-auth",
                outputFile = outputFile,
                extraBinds = installer.githubCredentialBinds(),
            )
            // QA Medium: waitFor() used to wait forever — a hung gh hung
            // refresh indefinitely. 120s is far above any API/auth call
            // this helper runs (clones use the cancellable clone path).
            val finished = process.waitFor(120L, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw java.util.concurrent.TimeoutException(
                    "gh ${arguments.firstOrNull() ?: "command"} timed out after 120s"
                )
            }
            val exit = process.exitValue()
            exit to sanitizeTerminalOutput(outputFile.readTailText(MAX_PROCESS_OUTPUT_BYTES)).trim()
        } finally {
            outputFile.delete()
        }
    }

    private fun githubAccountLogin(): String? {
        val (exit, output) = runGitHubCli(listOf("api", "user", "--jq", ".login"))
        return output.lineSequence().lastOrNull { it.isNotBlank() }?.trim().takeIf { exit == 0 && !it.isNullOrBlank() }
    }

    private fun githubRepositoriesFromCli(): List<GitHubRepository> {
        // Keep the GitHub CLI response line-oriented instead of parsing the
        // entire paginated response as one JSONArray. This is more resilient
        // to GitHub API/CLI response-shape changes and avoids nested JSON
        // conversion failures while loading the repository picker.
        val endpoint = "user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100"
        val (exit, output) = runGitHubCli(
            listOf(
                "api",
                "--paginate",
                endpoint,
                "--jq",
                ".[] | [.full_name, .clone_url, (.private | tostring), .default_branch, .description, .updated_at, .owner.login, .owner.type] | @tsv",
            ),
        )
        check(exit == 0) {
            output.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "Could not load GitHub repositories"
        }

        val repositories = LinkedHashMap<String, GitHubRepository>()
        output.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val fields = line.split("\t", limit = 8)
                if (fields.size < 6) return@forEach
                val fullName = fields[0].trim().takeIf(String::isNotBlank) ?: return@forEach
                repositories[fullName] = GitHubRepository(
                    fullName = fullName,
                    cloneUrl = fields[1].trim().ifBlank { "https://github.com/$fullName.git" },
                    private = fields[2].trim().equals("true", ignoreCase = true),
                    defaultBranch = fields[3].trim().ifBlank { "main" },
                    description = fields[4],
                    updatedAt = fields[5].trim(),
                    ownerLogin = fields.getOrElse(6) { "" }.trim(),
                    ownerType = fields.getOrElse(7) { "" }.trim(),
                )
            }
        return repositories.values.toList()
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            host.appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            host.updateState { state -> state.copy(toastMessage = "Could not open the browser. Copy the URL instead.") }
        }
    }

    private fun startGitHubForegroundOperation() {
        ContextCompat.startForegroundService(
            host.appContext,
            Intent(host.appContext, com.vayro.app.runtime.RuntimeExecutionService::class.java)
                .setAction(com.vayro.app.runtime.RuntimeExecutionService.ACTION_START)
                .putExtra(com.vayro.app.runtime.RuntimeExecutionService.EXTRA_PROJECT_NAME, "GitHub sign-in")
                .putExtra(com.vayro.app.runtime.RuntimeExecutionService.EXTRA_TITLE, "Connecting GitHub")
                .putExtra(com.vayro.app.runtime.RuntimeExecutionService.EXTRA_CAN_STOP, false),
        )
    }

    private fun stopGitHubForegroundOperation() {
        runCatching {
            (host.appContext as Application).startService(
                Intent(host.appContext, com.vayro.app.runtime.RuntimeExecutionService::class.java)
                    .setAction(com.vayro.app.runtime.RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            (host.appContext as Application).stopService(
                Intent(host.appContext, com.vayro.app.runtime.RuntimeExecutionService::class.java),
            )
        }
    }

    companion object {
        private const val GITHUB_DEVICE_URL = "https://github.com/login/device"
        private val GITHUB_DEVICE_CODE = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b")
        private const val MAX_PROCESS_OUTPUT_BYTES = 512 * 1024
    }

    /** Destroys any in-flight auth process; called from the ViewModel's onCleared. */
    fun onCleared() {
        runCatching {
            githubAuthProcess?.destroy()
            githubAuthJob?.cancel()
        }
    }
}
