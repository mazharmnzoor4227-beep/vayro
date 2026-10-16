package com.vayro.app.git.engine

import com.vayro.app.git.model.GitBranchInfo
import com.vayro.app.git.model.GitCommandResult
import com.vayro.app.git.model.GitCommitInfo
import com.vayro.app.git.model.GitFileStatus
import com.vayro.app.git.model.GitFileStatusCode
import com.vayro.app.git.model.GitResetMode
import com.vayro.app.git.model.GitStashItem
import com.vayro.app.git.model.GitStatusInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-level engine executing and parsing Git repository operations.
 */
class GitRepositoryEngine(
    private val commandRunner: (args: List<String>, workingDir: File) -> Pair<Int, String>
) {

    fun execute(args: List<String>, workingDir: File): GitCommandResult {
        if (!workingDir.exists()) {
            return GitCommandResult(false, 1, "", "Working directory does not exist: ${workingDir.path}")
        }
        return try {
            val (code, out) = commandRunner(args, workingDir)
            GitCommandResult(
                success = code == 0,
                exitCode = code,
                output = out.trim(),
                errorMessage = if (code != 0) out.trim().ifBlank { "Git command failed (exit $code)" } else null
            )
        } catch (e: Exception) {
            GitCommandResult(false, 1, "", e.message ?: "Execution error")
        }
    }

    /**
     * Parses full porcelain status into structured GitStatusInfo.
     */
    fun getStatus(workingDir: File): GitStatusInfo {
        val res = execute(listOf("status", "--porcelain=v1", "-b"), workingDir)
        if (!res.success) {
            return GitStatusInfo()
        }
        return parsePorcelainStatus(res.output)
    }

    /**
     * Parses output of `git status --porcelain=v1 -b`.
     */
    fun parsePorcelainStatus(output: String): GitStatusInfo {
        var currentBranch = "HEAD"
        var trackingBranch: String? = null
        var ahead = 0
        var behind = 0

        val staged = mutableListOf<GitFileStatus>()
        val unstaged = mutableListOf<GitFileStatus>()
        val untracked = mutableListOf<GitFileStatus>()
        val conflicts = mutableListOf<GitFileStatus>()

        val lines = output.lines()
        for (line in lines) {
            if (line.isBlank()) continue

            if (line.startsWith("## ")) {
                val branchHeader = line.removePrefix("## ").trim()
                // Format: main...origin/main [ahead 1, behind 2] or main
                if (branchHeader.contains("...")) {
                    val parts = branchHeader.split("...")
                    currentBranch = parts[0].trim()
                    val remotePart = parts.getOrNull(1)?.trim() ?: ""
                    val remoteTokens = remotePart.split(" ")
                    trackingBranch = remoteTokens.firstOrNull()?.trim()

                    val bracketContent = remotePart.substringAfter("[", "").substringBefore("]", "")
                    if (bracketContent.isNotBlank()) {
                        val aheadMatch = Regex("""ahead\s+(\d+)""").find(bracketContent)
                        if (aheadMatch != null) ahead = aheadMatch.groupValues[1].toIntOrNull() ?: 0

                        val behindMatch = Regex("""behind\s+(\d+)""").find(bracketContent)
                        if (behindMatch != null) behind = behindMatch.groupValues[1].toIntOrNull() ?: 0
                    }
                } else {
                    currentBranch = branchHeader.substringBefore(" ").trim()
                }
                continue
            }

            if (line.length < 3) continue
            val indexChar = line[0]
            val worktreeChar = line[1]
            val pathPart = line.substring(3).trim()

            val oldPath = if (pathPart.contains(" -> ")) pathPart.substringBefore(" -> ") else null
            val newPath = if (pathPart.contains(" -> ")) pathPart.substringAfter(" -> ") else pathPart

            val isConflict = (indexChar == 'U' || worktreeChar == 'U' ||
                    (indexChar == 'A' && worktreeChar == 'A') ||
                    (indexChar == 'D' && worktreeChar == 'D'))

            if (isConflict) {
                conflicts += GitFileStatus(
                    path = newPath,
                    oldPath = oldPath,
                    indexStatus = GitFileStatusCode.CONFLICT,
                    worktreeStatus = GitFileStatusCode.CONFLICT,
                    isStaged = false,
                    isConflict = true
                )
                continue
            }

            if (indexChar == '?' && worktreeChar == '?') {
                untracked += GitFileStatus(
                    path = newPath,
                    indexStatus = GitFileStatusCode.UNTRACKED,
                    worktreeStatus = GitFileStatusCode.UNTRACKED,
                    isStaged = false
                )
                continue
            }

            // Staged file
            if (indexChar != ' ' && indexChar != '?') {
                val code = parseStatusCode(indexChar)
                staged += GitFileStatus(
                    path = newPath,
                    oldPath = oldPath,
                    indexStatus = code,
                    worktreeStatus = GitFileStatusCode.UNCHANGED,
                    isStaged = true
                )
            }

            // Unstaged file in worktree
            if (worktreeChar != ' ' && worktreeChar != '?') {
                val code = parseStatusCode(worktreeChar)
                unstaged += GitFileStatus(
                    path = newPath,
                    oldPath = oldPath,
                    indexStatus = GitFileStatusCode.UNCHANGED,
                    worktreeStatus = code,
                    isStaged = false
                )
            }
        }

        return GitStatusInfo(
            currentBranch = currentBranch,
            trackingBranch = trackingBranch,
            aheadCount = ahead,
            behindCount = behind,
            stagedFiles = staged,
            unstagedFiles = unstaged,
            untrackedFiles = untracked,
            conflictFiles = conflicts
        )
    }

    private fun parseStatusCode(char: Char): GitFileStatusCode = when (char) {
        'M' -> GitFileStatusCode.MODIFIED
        'A' -> GitFileStatusCode.ADDED
        'D' -> GitFileStatusCode.DELETED
        'R' -> GitFileStatusCode.RENAMED
        'C' -> GitFileStatusCode.COPIED
        'U' -> GitFileStatusCode.CONFLICT
        '?' -> GitFileStatusCode.UNTRACKED
        else -> GitFileStatusCode.UNCHANGED
    }

    // --- Staging & Worktree Operations ---

    fun stageFile(workingDir: File, path: String): GitCommandResult =
        execute(listOf("add", path), workingDir)

    fun stageAll(workingDir: File): GitCommandResult =
        execute(listOf("add", "-A"), workingDir)

    fun unstageFile(workingDir: File, path: String): GitCommandResult {
        val res = execute(listOf("restore", "--staged", path), workingDir)
        return if (!res.success) execute(listOf("reset", "HEAD", path), workingDir) else res
    }

    fun unstageAll(workingDir: File): GitCommandResult {
        val res = execute(listOf("restore", "--staged", "."), workingDir)
        return if (!res.success) execute(listOf("reset", "HEAD"), workingDir) else res
    }

    fun discardFile(workingDir: File, path: String): GitCommandResult {
        val res = execute(listOf("restore", path), workingDir)
        if (!res.success) {
            execute(listOf("checkout", "--", path), workingDir)
        }
        return execute(listOf("clean", "-fd", path), workingDir)
    }

    fun discardAll(workingDir: File): GitCommandResult {
        val res = execute(listOf("restore", "."), workingDir)
        if (!res.success) {
            execute(listOf("checkout", "--", "."), workingDir)
        }
        return execute(listOf("clean", "-fd"), workingDir)
    }

    // --- Commit Operations ---

    fun commit(workingDir: File, message: String, amend: Boolean = false, signOff: Boolean = false): GitCommandResult {
        val args = mutableListOf("commit")
        if (amend) args += "--amend"
        if (signOff) args += "-s"
        args += listOf("-m", message)
        return execute(args, workingDir)
    }

    // --- Branch Operations ---

    fun listBranches(workingDir: File): List<GitBranchInfo> {
        val res = execute(listOf("branch", "-a", "-v", "--no-abbrev"), workingDir)
        if (!res.success) return emptyList()

        val list = mutableListOf<GitBranchInfo>()
        for (line in res.output.lines()) {
            if (line.isBlank()) continue
            val isCurrent = line.startsWith("*")
            val clean = line.removePrefix("*").trim()
            val tokens = clean.split(Regex("\\s+"))
            if (tokens.isEmpty()) continue

            val name = tokens[0]
            val isRemote = name.startsWith("remotes/")
            val cleanName = if (isRemote) name.removePrefix("remotes/") else name
            val commitHash = tokens.getOrNull(1)
            val subject = tokens.drop(2).joinToString(" ")

            list += GitBranchInfo(
                name = cleanName,
                isCurrent = isCurrent,
                isRemote = isRemote,
                latestCommitHash = commitHash,
                latestCommitSubject = subject
            )
        }
        return list
    }

    fun createBranch(workingDir: File, branchName: String, startPoint: String? = null): GitCommandResult {
        val args = mutableListOf("checkout", "-b", branchName)
        if (!startPoint.isNullOrBlank()) args += startPoint
        return execute(args, workingDir)
    }

    fun checkoutBranch(workingDir: File, branchName: String): GitCommandResult =
        execute(listOf("checkout", branchName), workingDir)

    fun deleteBranch(workingDir: File, branchName: String, force: Boolean = false): GitCommandResult {
        val flag = if (force) "-D" else "-d"
        return execute(listOf("branch", flag, branchName), workingDir)
    }

    fun renameBranch(workingDir: File, newName: String): GitCommandResult =
        execute(listOf("branch", "-m", newName), workingDir)

    // --- Merge & Rebase ---

    fun merge(workingDir: File, branch: String, message: String? = null): GitCommandResult {
        val args = mutableListOf("merge", branch)
        if (!message.isNullOrBlank()) {
            args += listOf("-m", message)
        }
        return execute(args, workingDir)
    }

    fun abortMerge(workingDir: File): GitCommandResult =
        execute(listOf("merge", "--abort"), workingDir)

    fun rebase(workingDir: File, upstream: String): GitCommandResult =
        execute(listOf("rebase", upstream), workingDir)

    fun abortRebase(workingDir: File): GitCommandResult =
        execute(listOf("rebase", "--abort"), workingDir)

    fun continueRebase(workingDir: File): GitCommandResult =
        execute(listOf("rebase", "--continue"), workingDir)

    // --- Stash Operations ---

    fun stashSave(workingDir: File, message: String = ""): GitCommandResult {
        val args = if (message.isNotBlank()) listOf("stash", "push", "-m", message) else listOf("stash")
        return execute(args, workingDir)
    }

    fun stashList(workingDir: File): List<GitStashItem> {
        val res = execute(listOf("stash", "list"), workingDir)
        if (!res.success) return emptyList()

        val list = mutableListOf<GitStashItem>()
        for (line in res.output.lines()) {
            if (line.isBlank()) continue
            // Format: stash@{0}: WIP on main: 1a2b3c4 Commit msg
            val match = Regex("""stash@\{(\d+)\}:\s*(?:WIP on\s+([^:]+):)?\s*(.*)""").find(line)
            if (match != null) {
                val idx = match.groupValues[1].toIntOrNull() ?: 0
                val branch = match.groupValues[2].ifBlank { "HEAD" }
                val msg = match.groupValues[3].ifBlank { line }
                list += GitStashItem(idx, msg, branch, "")
            }
        }
        return list
    }

    fun stashPop(workingDir: File, index: Int = 0): GitCommandResult =
        execute(listOf("stash", "pop", "stash@{$index}"), workingDir)

    fun stashDrop(workingDir: File, index: Int = 0): GitCommandResult =
        execute(listOf("stash", "drop", "stash@{$index}"), workingDir)

    // --- Commit History (Log) ---

    fun getLog(workingDir: File, limit: Int = 40): List<GitCommitInfo> {
        // Delimit fields with 0x1F (unit separator) and commits with 0x1E (record separator)
        val res = execute(listOf("log", "-n", limit.toString(), "--format=%H%x1f%an%x1f%ae%x1f%at%x1f%P%x1f%B%x1e"), workingDir)
        if (!res.success) return emptyList()
        return parseCommitLog(res.output)
    }

    fun parseCommitLog(rawOutput: String): List<GitCommitInfo> {
        val commits = mutableListOf<GitCommitInfo>()
        val records = rawOutput.split('\u001e')
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        for (record in records) {
            val trimmed = record.trim()
            if (trimmed.isBlank()) continue
            val fields = trimmed.split('\u001f')
            if (fields.size < 5) continue

            val hash = fields[0].trim()
            val authorName = fields[1].trim()
            val authorEmail = fields[2].trim()
            val timeSec = fields[3].trim().toLongOrNull() ?: 0L
            val parents = fields[4].trim().split(" ").filter { it.isNotBlank() }
            val message = fields.getOrNull(5)?.trim() ?: ""

            val formatted = if (timeSec > 0) dateFormat.format(Date(timeSec * 1000L)) else ""

            commits += GitCommitInfo(
                hash = hash,
                authorName = authorName,
                authorEmail = authorEmail,
                timestampSeconds = timeSec,
                formattedDate = formatted,
                message = message,
                parentHashes = parents
            )
        }
        return commits
    }

    // --- Reset Operations ---

    fun reset(workingDir: File, target: String, mode: GitResetMode = GitResetMode.MIXED): GitCommandResult =
        execute(listOf("reset", mode.flag, target), workingDir)
}
