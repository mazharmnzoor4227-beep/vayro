package com.vayro.app.network

data class GitHubIssueComment(
    val id: Long,
    val body: String,
    val user: String,
    val createdAt: String
)

data class GitHubReview(
    val id: Long,
    val user: String,
    val state: String, // APPROVED, CHANGES_REQUESTED, COMMENTED, PENDING
    val body: String,
    val submittedAt: String
)


data class GitHubWorkflowRun(
    val id: Long,
    val name: String,
    val status: String, // queued, in_progress, completed
    val conclusion: String?, // success, failure, cancelled, etc.
    val htmlUrl: String,
    val headBranch: String,
    val event: String,
    val createdAt: String,
    val runNumber: Int
)

data class GitHubReleaseAsset(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val contentType: String
)

data class GitHubRelease(
    val id: Long,
    val tagName: String,
    val name: String,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val createdAt: String,
    val publishedAt: String,
    val htmlUrl: String,
    val assets: List<GitHubReleaseAsset> = emptyList(),
    val uploadUrl: String = "",
)

enum class PullRequestMergeMethod(val value: String) {
    MERGE("merge"),
    SQUASH("squash"),
    REBASE("rebase")
}

data class MergeResult(
    val merged: Boolean,
    val message: String,
    val sha: String? = null
)

/**
 * Detailed pull-request view for automation decisions. `mergeable` is null while
 * GitHub is still computing mergeability — automation must treat null as unknown,
 * never as mergeable.
 */
data class GitHubPullRequestDetail(
    val number: Int,
    val title: String,
    val state: String,
    val user: String,
    val headBranch: String,
    val baseBranch: String,
    val headSha: String,
    val htmlUrl: String,
    val body: String = "",
    val draft: Boolean = false,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    val mergeableState: String = "unknown",
)

data class GitHubLabel(
    val name: String,
    val color: String = "",
    val description: String = "",
)

/**
 * Combined commit status (the classic Status API rollup). `state` is one of
 * "success", "failure", "error", "pending".
 */
data class CombinedStatus(
    val state: String,
    val totalCount: Int,
    val commitSha: String,
)

/** A downloadable artifact uploaded by a workflow run (e.g. the built APK zip). */
data class GitHubArtifact(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val archiveDownloadUrl: String,
    val expired: Boolean,
)

data class GitHubWorkflowJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
)
