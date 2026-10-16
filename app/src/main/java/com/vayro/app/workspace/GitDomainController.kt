package com.vayro.app.workspace

import com.vayro.app.git.engine.GitRepositoryEngine
import com.vayro.app.git.model.GitBranchInfo
import com.vayro.app.git.model.GitCommitInfo
import com.vayro.app.git.model.GitResetMode
import com.vayro.app.git.model.GitStashItem
import com.vayro.app.git.model.GitStatusInfo
import java.io.File

/**
 * Result of a Git domain command.
 */
data class GitCommandResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val errorMessage: String? = null,
)

/**
 * Domain controller for Git operations, integrating high-level repository engine capabilities
 * while maintaining backward-compatible method signatures.
 */
class GitDomainController(
    private val processRunner: (args: List<String>, workingDir: File, guestWorkspacePath: String) -> Pair<Int, String> = { _, _, _ -> 0 to "" }
) {

    val engine: GitRepositoryEngine = GitRepositoryEngine { args, dir ->
        processRunner(args, dir, "")
    }

    fun executeGit(
        args: List<String>,
        workingDir: File,
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        if (!workingDir.exists()) {
            return GitCommandResult(
                success = false,
                exitCode = 1,
                output = "",
                errorMessage = "Workspace directory does not exist: ${workingDir.path}",
            )
        }
        return try {
            val (exit, out) = processRunner(args, workingDir, guestWorkspacePath)
            GitCommandResult(
                success = exit == 0,
                exitCode = exit,
                output = out.trim(),
                errorMessage = if (exit != 0) out.trim().ifBlank { "Git command failed with code $exit" } else null,
            )
        } catch (e: Exception) {
            GitCommandResult(
                success = false,
                exitCode = 1,
                output = "",
                errorMessage = e.message ?: "Git execution error",
            )
        }
    }

    fun status(workingDir: File, guestWorkspacePath: String = ""): GitCommandResult {
        return executeGit(listOf("status", "--short"), workingDir, guestWorkspacePath)
    }

    fun getStatusInfo(workingDir: File): GitStatusInfo {
        return engine.getStatus(workingDir)
    }

    fun diff(workingDir: File, guestWorkspacePath: String = ""): GitCommandResult {
        return executeGit(listOf("diff", "--stat"), workingDir, guestWorkspacePath)
    }

    fun rawDiff(workingDir: File, staged: Boolean = false): GitCommandResult {
        val args = if (staged) listOf("diff", "--staged") else listOf("diff")
        return executeGit(args, workingDir)
    }

    fun branchList(workingDir: File, guestWorkspacePath: String = ""): List<String> {
        val res = executeGit(listOf("branch", "--list"), workingDir, guestWorkspacePath)
        if (!res.success) return emptyList()
        return res.output.lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    fun listBranchesDetailed(workingDir: File): List<GitBranchInfo> {
        return engine.listBranches(workingDir)
    }

    fun checkout(
        workingDir: File,
        branch: String,
        createNew: Boolean = false,
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        val args = if (createNew) listOf("checkout", "-b", branch) else listOf("checkout", branch)
        return executeGit(args, workingDir, guestWorkspacePath)
    }

    fun commit(
        workingDir: File,
        message: String,
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        val addRes = executeGit(listOf("add", "-A"), workingDir, guestWorkspacePath)
        if (!addRes.success) {
            return addRes.copy(errorMessage = "Failed to stage files: ${addRes.errorMessage}")
        }
        return executeGit(listOf("commit", "-m", message), workingDir, guestWorkspacePath)
    }

    fun commitDetailed(
        workingDir: File,
        message: String,
        amend: Boolean = false,
        signOff: Boolean = false
    ): GitCommandResult {
        return engine.commit(workingDir, message, amend, signOff)
    }

    fun push(
        workingDir: File,
        remote: String = "origin",
        branch: String = "",
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        val args = if (branch.isNotBlank()) listOf("push", remote, branch) else listOf("push")
        return executeGit(args, workingDir, guestWorkspacePath)
    }

    fun pull(
        workingDir: File,
        remote: String = "origin",
        branch: String = "",
        guestWorkspacePath: String = "",
    ): GitCommandResult {
        val args = if (branch.isNotBlank()) listOf("pull", remote, branch) else listOf("pull")
        return executeGit(args, workingDir, guestWorkspacePath)
    }

    fun stageFile(workingDir: File, path: String): GitCommandResult = engine.stageFile(workingDir, path)

    fun stageAll(workingDir: File): GitCommandResult = engine.stageAll(workingDir)

    fun unstageFile(workingDir: File, path: String): GitCommandResult = engine.unstageFile(workingDir, path)

    fun unstageAll(workingDir: File): GitCommandResult = engine.unstageAll(workingDir)

    fun discardFile(workingDir: File, path: String): GitCommandResult = engine.discardFile(workingDir, path)

    fun discardAll(workingDir: File): GitCommandResult = engine.discardAll(workingDir)

    fun deleteBranch(workingDir: File, branchName: String, force: Boolean = false): GitCommandResult =
        engine.deleteBranch(workingDir, branchName, force)

    fun merge(workingDir: File, branch: String, message: String? = null): GitCommandResult =
        engine.merge(workingDir, branch, message)

    fun rebase(workingDir: File, upstream: String): GitCommandResult =
        engine.rebase(workingDir, upstream)

    fun reset(workingDir: File, target: String, mode: GitResetMode = GitResetMode.MIXED): GitCommandResult =
        engine.reset(workingDir, target, mode)

    fun log(workingDir: File, limit: Int = 40): List<GitCommitInfo> =
        engine.getLog(workingDir, limit)

    fun stashSave(workingDir: File, message: String = ""): GitCommandResult =
        engine.stashSave(workingDir, message)

    fun stashList(workingDir: File): List<GitStashItem> =
        engine.stashList(workingDir)

    fun stashPop(workingDir: File, index: Int = 0): GitCommandResult =
        engine.stashPop(workingDir, index)
}
