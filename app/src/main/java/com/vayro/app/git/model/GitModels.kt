package com.vayro.app.git.model

/**
 * Status of an individual file in Git index or worktree.
 */
enum class GitFileStatusCode(val code: String, val label: String) {
    UNTRACKED("??", "Untracked"),
    MODIFIED("M", "Modified"),
    ADDED("A", "Added"),
    DELETED("D", "Deleted"),
    RENAMED("R", "Renamed"),
    COPIED("C", "Copied"),
    CONFLICT("U", "Conflict"),
    UNCHANGED(" ", "Unchanged")
}

/**
 * File change description in Git.
 */
data class GitFileStatus(
    val path: String,
    val oldPath: String? = null,
    val indexStatus: GitFileStatusCode = GitFileStatusCode.UNCHANGED,
    val worktreeStatus: GitFileStatusCode = GitFileStatusCode.UNCHANGED,
    val isStaged: Boolean = false,
    val isConflict: Boolean = false
)

/**
 * High-level status of the entire working repository.
 */
data class GitStatusInfo(
    val currentBranch: String = "main",
    val trackingBranch: String? = null,
    val aheadCount: Int = 0,
    val behindCount: Int = 0,
    val stagedFiles: List<GitFileStatus> = emptyList(),
    val unstagedFiles: List<GitFileStatus> = emptyList(),
    val untrackedFiles: List<GitFileStatus> = emptyList(),
    val conflictFiles: List<GitFileStatus> = emptyList(),
    val isClean: Boolean = stagedFiles.isEmpty() && unstagedFiles.isEmpty() && untrackedFiles.isEmpty() && conflictFiles.isEmpty()
)

/**
 * Information regarding a single Git commit.
 */
data class GitCommitInfo(
    val hash: String,
    val shortHash: String = hash.take(7),
    val authorName: String,
    val authorEmail: String,
    val timestampSeconds: Long,
    val formattedDate: String,
    val message: String,
    val parentHashes: List<String> = emptyList()
)

/**
 * Branch metadata.
 */
data class GitBranchInfo(
    val name: String,
    val isCurrent: Boolean = false,
    val isRemote: Boolean = false,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val latestCommitHash: String? = null,
    val latestCommitSubject: String? = null
)

/**
 * Line types in a unified Git diff.
 */
enum class GitDiffLineType {
    CONTEXT,
    ADDITION,
    DELETION,
    HEADER
}

/**
 * A single line in a diff hunk.
 */
data class GitDiffLine(
    val type: GitDiffLineType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
)

/**
 * A hunk in a unified Git diff.
 */
data class GitDiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<GitDiffLine>
)

/**
 * Full diff representation for a single file.
 */
data class GitFileDiff(
    val oldPath: String?,
    val newPath: String?,
    val isBinary: Boolean = false,
    val isNewFile: Boolean = false,
    val isDeletedFile: Boolean = false,
    val hunks: List<GitDiffHunk> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0
)

/**
 * Stash entry metadata.
 */
data class GitStashItem(
    val index: Int,
    val message: String,
    val branch: String,
    val hash: String
)

/**
 * Git reset strategies.
 */
enum class GitResetMode(val flag: String) {
    SOFT("--soft"),
    MIXED("--mixed"),
    HARD("--hard")
}

typealias GitCommandResult = com.vayro.app.workspace.GitCommandResult
