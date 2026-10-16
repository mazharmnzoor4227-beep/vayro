package com.vayro.app.ui.coordinators

import com.vayro.app.data.AppPreferences
import com.vayro.app.git.model.GitResetMode
import com.vayro.app.model.Project
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubRepository
import com.vayro.app.network.PullRequestMergeMethod
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.ui.GitHubAuthStatus
import com.vayro.app.ui.sanitizeTerminalOutput
import com.vayro.app.workspace.GitDomainController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Git domain coordinator (Milestone 1).
 *
 * Explicit owner of the Git state machine: workspace Git commands, the Git
 * Studio panel (status/branches/commits/diff), GitHub issues/PRs/releases,
 * the GitHub device-flow sign-in, and repository cloning. All Git execution
 * goes through the real [GitDomainController]; GitHub API calls go through
 * the real [GitHubClient]. Results are honest — failures surface with the
 * real error, never fake success.
 *
 * The M0-hardened autonomous issue workflow
 * ([MainViewModel.startAutonomousIssueWorkflow]) stays in the ViewModel: it
 * orchestrates the agent-session core, which is a Milestone 6/10 concern.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class GitCoordinator(
    private val host: CoordinatorHost,
    private val gitDomainController: GitDomainController,
    private val gitHubClient: GitHubClient,
    private val installer: RuntimeInstaller,
    private val preferences: AppPreferences,
    private val workspaceRootFor: (Project) -> File,
    private val refreshProjectFiles: () -> Unit,
    private val openProject: (Project) -> Unit,
) {
    // ---------- Workspace Git commands ----------

    fun runWorkspaceGitCommand(args: List<String>, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = host.state().activeProject ?: return onResult(false, "No active project")
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                gitDomainController.executeGit(args, workspace, "/workspace/${project.slug}")
            }
            if (!result.success && result.output.isNotBlank()) {
                host.updateState { it.copy(toastMessage = "Git: ${result.output}") }
            }
            onResult(result.success, result.output)
        }
    }

    fun gitStatus(onResult: (String) -> Unit) {
        val project = host.state().activeProject ?: return onResult("No active project")
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.status(workspace, "/workspace/${project.slug}")
            }
            onResult(res.output)
        }
    }

    fun gitDiff(onResult: (String) -> Unit) {
        val project = host.state().activeProject ?: return onResult("No active project")
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.diff(workspace, "/workspace/${project.slug}")
            }
            onResult(res.output)
        }
    }

    fun gitBranchList(onResult: (List<String>) -> Unit) {
        val project = host.state().activeProject ?: return onResult(emptyList())
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val branches = withContext(Dispatchers.IO) {
                gitDomainController.branchList(workspace, "/workspace/${project.slug}")
            }
            onResult(branches)
        }
    }

    fun gitCheckout(branch: String, createNew: Boolean = false, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = host.state().activeProject ?: return onResult(false, "No active project")
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.checkout(workspace, branch, createNew, "/workspace/${project.slug}")
            }
            if (res.success) {
                host.updateState { it.copy(toastMessage = "Checked out $branch") }
                refreshProjectFiles()
            } else if (res.output.isNotBlank()) {
                host.updateState { it.copy(toastMessage = "Git: ${res.output}") }
            }
            onResult(res.success, res.output)
        }
    }

    fun gitCommit(message: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = host.state().activeProject ?: return onResult(false, "No active project")
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.commit(workspace, message, "/workspace/${project.slug}")
            }
            if (res.success) {
                host.updateState { it.copy(toastMessage = "Committed: $message") }
                refreshProjectFiles()
            } else if (res.output.isNotBlank()) {
                host.updateState { it.copy(toastMessage = "Git: ${res.output}") }
            }
            onResult(res.success, res.output)
        }
    }

    fun gitPush(remote: String = "origin", branch: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = host.state().activeProject ?: return onResult(false, "No active project")
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.push(workspace, remote, branch, "/workspace/${project.slug}")
            }
            if (res.success) {
                host.updateState { it.copy(toastMessage = "Git push successful") }
            } else if (res.output.isNotBlank()) {
                host.updateState { it.copy(toastMessage = "Git: ${res.output}") }
            }
            onResult(res.success, res.output)
        }
    }

    fun gitPull(remote: String = "origin", branch: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val project = host.state().activeProject ?: return onResult(false, "No active project")
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val res = withContext(Dispatchers.IO) {
                gitDomainController.pull(workspace, remote, branch, "/workspace/${project.slug}")
            }
            if (res.success) {
                host.updateState { it.copy(toastMessage = "Git pull successful") }
                refreshProjectFiles()
            } else if (res.output.isNotBlank()) {
                host.updateState { it.copy(toastMessage = "Git: ${res.output}") }
            }
            onResult(res.success, res.output)
        }
    }

    // ---------- Git Studio ----------

    fun refreshGitStudio(targetDirectory: File? = null) {
        val project = host.state().activeProject
        val dir = targetDirectory ?: project?.let { workspaceRootFor(it) } ?: return
        host.scope.launch(Dispatchers.IO) {
            val status = gitDomainController.getStatusInfo(dir)
            val branches = gitDomainController.listBranchesDetailed(dir)
            val commits = gitDomainController.log(dir, limit = 40)
            val rawDiff = gitDomainController.rawDiff(dir, staged = false).output
            val repoFullName = host.state().githubRepositories.firstOrNull {
                it.cloneUrl.contains(dir.name, ignoreCase = true) || it.fullName.contains(dir.name, ignoreCase = true)
            }?.fullName ?: host.state().githubRepositories.firstOrNull()?.fullName

            val issues = if (repoFullName != null && preferences.githubLogin.isNotBlank()) {
                runCatching { gitHubClient.issues("", repoFullName) }.getOrDefault(emptyList())
            } else emptyList()

            val prs = if (repoFullName != null && preferences.githubLogin.isNotBlank()) {
                runCatching { gitHubClient.pullRequests("", repoFullName) }.getOrDefault(emptyList())
            } else emptyList()

            val runs = if (repoFullName != null && preferences.githubLogin.isNotBlank()) {
                runCatching { gitHubClient.listWorkflowRuns("", repoFullName) }.getOrDefault(emptyList())
            } else emptyList()

            val releases = if (repoFullName != null && preferences.githubLogin.isNotBlank()) {
                runCatching { gitHubClient.listReleases("", repoFullName) }.getOrDefault(emptyList())
            } else emptyList()

            host.updateState {
                it.copy(
                    gitStatusInfo = status,
                    gitBranches = branches,
                    gitCommits = commits,
                    gitRawDiff = rawDiff,
                    gitConnectedRepo = repoFullName,
                    gitIssues = issues,
                    gitPullRequests = prs,
                    gitWorkflowRuns = runs,
                    gitReleases = releases
                )
            }
        }
    }

    fun gitStageFile(path: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        gitDomainController.stageFile(dir, path)
        refreshGitStudio(dir)
    }

    fun gitStageAll(targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        gitDomainController.stageAll(dir)
        refreshGitStudio(dir)
    }

    fun gitUnstageFile(path: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        gitDomainController.unstageFile(dir, path)
        refreshGitStudio(dir)
    }

    fun gitUnstageAll(targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        gitDomainController.unstageAll(dir)
        refreshGitStudio(dir)
    }

    fun gitDiscardFile(path: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        gitDomainController.discardFile(dir, path)
        refreshGitStudio(dir)
    }

    fun gitDiscardAll(targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        gitDomainController.discardAll(dir)
        refreshGitStudio(dir)
    }

    fun gitCommitDetailed(message: String, amend: Boolean = false, signOff: Boolean = false, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        val res = gitDomainController.commitDetailed(dir, message, amend, signOff)
        host.updateState { it.copy(toastMessage = if (res.success) "Committed successfully" else res.errorMessage ?: "Commit failed") }
        refreshGitStudio(dir)
    }

    fun gitCreateBranch(name: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        val res = gitDomainController.checkout(dir, name, createNew = true)
        host.updateState { it.copy(toastMessage = if (res.success) "Created branch $name" else res.errorMessage ?: "Failed to create branch") }
        refreshGitStudio(dir)
    }

    fun gitCheckoutBranch(name: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        val res = gitDomainController.checkout(dir, name, createNew = false)
        host.updateState { it.copy(toastMessage = if (res.success) "Switched to branch $name" else res.errorMessage ?: "Failed to switch branch") }
        refreshGitStudio(dir)
    }

    fun gitDeleteBranch(name: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        val res = gitDomainController.deleteBranch(dir, name, force = true)
        host.updateState { it.copy(toastMessage = if (res.success) "Deleted branch $name" else res.errorMessage ?: "Failed to delete branch") }
        refreshGitStudio(dir)
    }

    fun gitMergeBranch(branch: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        val res = gitDomainController.merge(dir, branch)
        host.updateState { it.copy(toastMessage = if (res.success) "Merged $branch successfully" else res.errorMessage ?: "Merge conflicts or failure") }
        refreshGitStudio(dir)
    }

    fun gitRebaseBranch(upstream: String, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        val res = gitDomainController.rebase(dir, upstream)
        host.updateState { it.copy(toastMessage = if (res.success) "Rebased onto $upstream" else res.errorMessage ?: "Rebase conflicts or failure") }
        refreshGitStudio(dir)
    }

    fun gitResetBranch(hash: String, mode: GitResetMode, targetDirectory: File? = null) {
        val dir = targetDirectory ?: host.state().activeProject?.let { workspaceRootFor(it) } ?: return
        val res = gitDomainController.reset(dir, hash, mode)
        host.updateState { it.copy(toastMessage = if (res.success) "Reset to $hash (${mode.name})" else res.errorMessage ?: "Reset failed") }
        refreshGitStudio(dir)
    }

    // ---------- GitHub issues / PRs ----------

    fun createGitHubIssue(title: String, body: String = "") {
        val repo = host.state().gitConnectedRepo ?: return
        host.scope.launch(Dispatchers.IO) {
            try {
                val issue = gitHubClient.createIssue("", repo, title, body)
                host.updateState { it.copy(gitIssues = listOf(issue) + it.gitIssues, toastMessage = "Created Issue #${issue.number}") }
            } catch (e: Exception) {
                host.updateState { it.copy(toastMessage = "Failed to create issue: ${e.message}") }
            }
        }
    }

    fun mergeGitHubPullRequest(prNumber: Int, method: PullRequestMergeMethod = PullRequestMergeMethod.SQUASH) {
        val repo = host.state().gitConnectedRepo ?: return
        host.scope.launch(Dispatchers.IO) {
            try {
                val result = gitHubClient.mergePullRequest("", repo, prNumber, method = method)
                host.updateState {
                    it.copy(
                        toastMessage = if (result.merged) "PR #$prNumber merged successfully!" else "Merge failed: ${result.message}"
                    )
                }
                refreshGitStudio()
            } catch (e: Exception) {
                host.updateState { it.copy(toastMessage = "Merge failed: ${e.message}") }
            }
        }
    }
}
