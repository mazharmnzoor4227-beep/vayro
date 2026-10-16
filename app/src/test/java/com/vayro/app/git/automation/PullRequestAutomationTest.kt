package com.vayro.app.git.automation

import com.vayro.app.core.result.OperationStatus
import com.vayro.app.network.CombinedStatus
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubPullRequestDetail
import com.vayro.app.network.GitHubReview
import com.vayro.app.network.MergeResult
import com.vayro.app.network.PullRequestMergeMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class FakePrState(
    val detail: GitHubPullRequestDetail,
    val combinedState: String = "success",
    val reviews: List<GitHubReview> = emptyList(),
    val mergeResult: MergeResult = MergeResult(merged = true, message = "merged", sha = "abc123"),
)

private class FakePrClient(var state: FakePrState) : GitHubClient() {
    var mergeCalls = 0
    override fun getPullRequest(token: String, repoFullName: String, prNumber: Int) = state.detail
    override fun getCombinedStatus(token: String, repoFullName: String, ref: String) =
        CombinedStatus(state = state.combinedState, totalCount = 3, commitSha = ref)
    override fun listReviews(token: String, repoFullName: String, prNumber: Int) = state.reviews
    override fun mergePullRequest(
        token: String,
        repoFullName: String,
        prNumber: Int,
        commitTitle: String?,
        commitMessage: String?,
        method: PullRequestMergeMethod,
    ): MergeResult {
        mergeCalls++
        return state.mergeResult
    }
}

private fun openPr(
    mergeable: Boolean? = true,
    draft: Boolean = false,
    merged: Boolean = false,
    state: String = "open",
) = GitHubPullRequestDetail(
    number = 12,
    title = "Fix crash",
    state = state,
    user = "dev",
    headBranch = "fix/crash",
    baseBranch = "main",
    headSha = "deadbeef",
    htmlUrl = "https://github.com/o/r/pull/12",
    draft = draft,
    merged = merged,
    mergeable = mergeable,
    mergeableState = if (mergeable == true) "clean" else "dirty",
)

private fun approval(user: String, state: String) = GitHubReview(
    id = user.hashCode().toLong(),
    user = user,
    state = state,
    body = "",
    submittedAt = "",
)

class PullRequestAutomationTest {

    private val policy = AutoMergePolicy(requireCiGreen = true, requiredApprovals = 1)

    @Test
    fun mergesWhenAllGatesPass() {
        val client = FakePrClient(
            FakePrState(
                detail = openPr(),
                reviews = listOf(approval("alice", "APPROVED")),
            )
        )
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(1, client.mergeCalls)
        assertTrue(result.verificationEvidence!!.contains("merged"))
    }

    @Test
    fun refusesWhenCiFailing() {
        val client = FakePrClient(FakePrState(detail = openPr(), combinedState = "failure"))
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(0, client.mergeCalls)
        assertTrue(result.error!!.contains("failure"))
    }

    @Test
    fun refusesWhenCiPending() {
        val client = FakePrClient(FakePrState(detail = openPr(), combinedState = "pending"))
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(0, client.mergeCalls)
        assertTrue(result.error!!.contains("pending"))
    }

    @Test
    fun refusesWhenApprovalsUnmet() {
        val client = FakePrClient(
            FakePrState(detail = openPr(), reviews = listOf(approval("alice", "COMMENTED")))
        )
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(0, client.mergeCalls)
        assertTrue(result.error!!.contains("approval"))
    }

    @Test
    fun changesRequestedCancelsEarlierApproval() {
        val client = FakePrClient(
            FakePrState(
                detail = openPr(),
                reviews = listOf(approval("alice", "APPROVED"), approval("alice", "CHANGES_REQUESTED")),
            )
        )
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(0, client.mergeCalls)
    }

    @Test
    fun refusesWhenNotMergeable() {
        val client = FakePrClient(FakePrState(detail = openPr(mergeable = false)))
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(0, client.mergeCalls)
        assertTrue(result.error!!.contains("not mergeable"))
    }

    @Test
    fun refusesWhenMergeabilityUnknown() {
        val client = FakePrClient(FakePrState(detail = openPr(mergeable = null)))
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(0, client.mergeCalls)
        assertTrue(result.error!!.contains("still being computed"))
    }

    @Test
    fun refusesAlreadyMergedPr() {
        val client = FakePrClient(FakePrState(detail = openPr(merged = true, state = "closed")))
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(0, client.mergeCalls)
        assertTrue(result.error!!.contains("already merged"))
    }

    @Test
    fun refusesDraftWhenPolicyDisallows() {
        val client = FakePrClient(FakePrState(detail = openPr(draft = true)))
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(0, client.mergeCalls)
        assertTrue(result.error!!.contains("draft"))
    }

    @Test
    fun mergeApiRefusalIsFailure() {
        val client = FakePrClient(
            FakePrState(
                detail = openPr(),
                reviews = listOf(approval("alice", "APPROVED")),
                mergeResult = MergeResult(merged = false, message = "merge conflict detected", sha = null),
            )
        )
        val result = PullRequestAutomation(client).evaluateAndMerge("tok", "o/r", 12, policy)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(1, client.mergeCalls)
        assertFalse(result.isSuccess)
    }

    @Test
    fun blankTokenIsNotConfigured() {
        val client = FakePrClient(FakePrState(detail = openPr()))
        val result = PullRequestAutomation(client).evaluateAndMerge("", "o/r", 12, policy)
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, client.mergeCalls)
    }

    @Test
    fun noApprovalRequirementMergesWithoutReviews() {
        val client = FakePrClient(FakePrState(detail = openPr()))
        val result = PullRequestAutomation(client)
            .evaluateAndMerge("tok", "o/r", 12, AutoMergePolicy(requireCiGreen = true, requiredApprovals = 0))
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(1, client.mergeCalls)
    }
}
