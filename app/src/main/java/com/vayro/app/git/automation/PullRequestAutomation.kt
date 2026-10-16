package com.vayro.app.git.automation

import com.vayro.app.core.result.OperationResult
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.PullRequestMergeMethod

/**
 * Milestone 9 — PR creation/merge automation.
 *
 * Policy-gated auto-merge: a PR is merged only when every configured gate passes.
 * Gates are evaluated against live GitHub API data (PR detail, combined commit
 * status, reviews). Anything unverifiable is a refusal, never an assumption:
 * unknown mergeability, pending CI, or missing approvals all fail the operation
 * honestly instead of merging blindly.
 */
data class AutoMergePolicy(
    /** Require the combined commit status on the head SHA to be "success". */
    val requireCiGreen: Boolean = true,
    /** Minimum number of distinct APPROVED reviews (latest state per reviewer). 0 disables. */
    val requiredApprovals: Int = 0,
    /** Merge method to use when all gates pass. */
    val method: PullRequestMergeMethod = PullRequestMergeMethod.SQUASH,
    /** Whether draft PRs may be merged. */
    val allowDraft: Boolean = false,
    /** Optional commit title/message for the merge commit. */
    val commitTitle: String? = null,
    val commitMessage: String? = null,
)

class PullRequestAutomation(
    private val client: GitHubClient = GitHubClient(),
) {
    /**
     * Evaluates the auto-merge policy against the live PR and merges only if all
     * gates pass. Returns SUCCESS with the merge SHA as evidence, FAILED with the
     * specific gate that refused, or NOT_CONFIGURED when no token is supplied.
     */
    fun evaluateAndMerge(
        token: String,
        repoFullName: String,
        prNumber: Int,
        policy: AutoMergePolicy = AutoMergePolicy(),
    ): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (token.isBlank()) {
            return OperationResult.notConfigured(
                "GitHub token is missing. Refusing to evaluate or merge PR #$prNumber without credentials."
            )
        }
        val logs = mutableListOf<String>()
        return try {
            val detail = client.getPullRequest(token, repoFullName, prNumber)
            logs += "PR #${detail.number} '${detail.title}' state=${detail.state} draft=${detail.draft} merged=${detail.merged}"

            if (detail.merged) {
                return OperationResult.failure(
                    error = "PR #$prNumber is already merged. Nothing to do.",
                    logs = logs,
                    startedAt = startedAt,
                )
            }
            if (detail.state != "open") {
                return OperationResult.failure(
                    error = "PR #$prNumber is not open (state=${detail.state}). Refusing to merge.",
                    logs = logs,
                    startedAt = startedAt,
                )
            }
            if (detail.draft && !policy.allowDraft) {
                return OperationResult.failure(
                    error = "PR #$prNumber is a draft and the policy disallows merging drafts.",
                    logs = logs,
                    startedAt = startedAt,
                )
            }
            // Mergeability: null means GitHub is still computing — unknown, never assumed mergeable.
            when (detail.mergeable) {
                false -> return OperationResult.failure(
                    error = "PR #$prNumber is not mergeable (state=${detail.mergeableState}). Resolve conflicts first.",
                    logs = logs,
                    startedAt = startedAt,
                )
                null -> return OperationResult.failure(
                    error = "PR #$prNumber mergeability is still being computed by GitHub. Retry later; refusing to merge blind.",
                    logs = logs,
                    startedAt = startedAt,
                )
                else -> logs += "mergeable=true (${detail.mergeableState})"
            }

            if (policy.requireCiGreen) {
                val status = client.getCombinedStatus(token, repoFullName, detail.headSha)
                logs += "combined status on ${detail.headSha.take(8)}: ${status.state} (${status.totalCount} checks)"
                when (status.state) {
                    "success" -> Unit // gate passes
                    "pending" -> return OperationResult.failure(
                        error = "CI is still pending on PR #$prNumber. Refusing to merge before checks complete.",
                        logs = logs,
                        startedAt = startedAt,
                    )
                    else -> return OperationResult.failure(
                        error = "CI status is '${status.state}' on PR #$prNumber. Merge blocked until checks are green.",
                        logs = logs,
                        startedAt = startedAt,
                    )
                }
            }

            if (policy.requiredApprovals > 0) {
                val approvals = distinctApprovals(token, repoFullName, prNumber)
                logs += "distinct approvals: $approvals (required: ${policy.requiredApprovals})"
                if (approvals < policy.requiredApprovals) {
                    return OperationResult.failure(
                        error = "PR #$prNumber has $approvals approval(s); policy requires ${policy.requiredApprovals}.",
                        logs = logs,
                        startedAt = startedAt,
                    )
                }
            }

            val merge = client.mergePullRequest(
                token = token,
                repoFullName = repoFullName,
                prNumber = prNumber,
                commitTitle = policy.commitTitle,
                commitMessage = policy.commitMessage,
                method = policy.method,
            )
            if (!merge.merged) {
                return OperationResult.failure(
                    error = "GitHub refused the merge for PR #$prNumber: ${merge.message.ifBlank { "no reason given" }}",
                    logs = logs,
                    startedAt = startedAt,
                )
            }
            val evidence = "PR #$prNumber merged via ${policy.method.value} (sha=${merge.sha ?: "unknown"})"
            logs += evidence
            OperationResult.success(
                logs = logs,
                artifacts = merge.sha?.let { listOf(it) } ?: emptyList(),
                verificationEvidence = evidence,
                startedAt = startedAt,
            )
        } catch (e: Exception) {
            OperationResult.failure(
                error = "Auto-merge evaluation failed for PR #$prNumber: ${e.message}",
                logs = logs,
                startedAt = startedAt,
            )
        }
    }

    /**
     * Counts distinct reviewers whose LATEST review state is APPROVED. A reviewer
     * who later requested changes no longer counts — handled by taking the last
     * review per user in API order (oldest first).
     */
    private fun distinctApprovals(token: String, repoFullName: String, prNumber: Int): Int {
        val latestByUser = LinkedHashMap<String, String>()
        for (review in client.listReviews(token, repoFullName, prNumber)) {
            val state = review.state.uppercase()
            if (state == "APPROVED" || state == "CHANGES_REQUESTED" || state == "DISMISSED") {
                latestByUser[review.user] = state
            }
        }
        return latestByUser.values.count { it == "APPROVED" }
    }
}
