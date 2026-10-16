package com.vayro.app.workflow

/**
 * M6: review gate.
 *
 * The workflow never commits without an explicit review decision. Production
 * wiring supplies a [CallbackWorkflowReviewer] whose callback surfaces the
 * plan and results to the user (or a policy engine) for approval.
 * [TestOnlyAutoApproveReviewer] exists for unit tests and demos only — its
 * name and documentation say so, and it must never be wired in production.
 */

enum class ReviewVerdict { APPROVE, REJECT, REQUEST_CHANGES }

data class ReviewOutcome(
    val verdict: ReviewVerdict,
    val notes: String = "",
)

interface WorkflowReviewer {
    suspend fun review(plan: WorkflowPlan, results: List<StepRunResult>): ReviewOutcome
}

class CallbackWorkflowReviewer(
    private val onReview: suspend (plan: WorkflowPlan, results: List<StepRunResult>) -> ReviewOutcome,
) : WorkflowReviewer {
    override suspend fun review(plan: WorkflowPlan, results: List<StepRunResult>): ReviewOutcome =
        onReview(plan, results)
}

/** Tests and demos only. Never wire in production: it approves everything. */
class TestOnlyAutoApproveReviewer : WorkflowReviewer {
    override suspend fun review(plan: WorkflowPlan, results: List<StepRunResult>): ReviewOutcome =
        ReviewOutcome(ReviewVerdict.APPROVE, "Test-only auto-approval (not a real review)")
}
