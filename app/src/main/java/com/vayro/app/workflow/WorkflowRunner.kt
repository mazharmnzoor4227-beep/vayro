package com.vayro.app.workflow

import com.vayro.app.core.result.OperationResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M6: the autonomous workflow runner — plan → implement → build → test →
 * fix (bounded) → retest → review → approve → commit.
 *
 * Phases:
 * 1. PLANNING — [TaskDecomposer] turns the goal into a [WorkflowPlan].
 *    Refused/invalid plans fail honestly; nothing executes.
 * 2. EXECUTING — [PlanExecutor] runs the work steps (everything except
 *    GIT_COMMIT/GIT_PUSH) in dependency order with bounded auto-repair.
 *    Every write is checkpointed before it happens.
 * 3. REVIEWING — [WorkflowReviewer] decides. Anything but APPROVE stops the
 *    run before any commit: REJECT/REQUEST_CHANGES never reach the commit
 *    phase.
 * 4. COMMITTING — the commit-phase steps run through the safety gateway.
 *
 * Failure handling: when [rollbackOnFailure] is true, a failed run restores
 * every checkpointed file. Run state is persisted after every step, so
 * [resume] can continue an interrupted run from the first incomplete step.
 *
 * Integrity rules (from M0, enforced structurally):
 * - A SIMULATED agent result is refused by the IMPLEMENT executor.
 * - Empty or no-op work never reaches the commit phase.
 * - The final SUCCESS carries verification evidence; anything else is FAILED
 *   with the reason, never a claimed success.
 */
class WorkflowRunner(
    private val decomposer: TaskDecomposer,
    private val planExecutorFactory: (onStep: (StepRunResult) -> Unit) -> PlanExecutor,
    private val reviewer: WorkflowReviewer,
    private val contextFactory: (runId: String) -> WorkflowContext,
    private val checkpointStore: CheckpointStore,
    private val rollbackOnFailure: Boolean = true,
    /**
     * Honest empty-diff guard (M0 rule): before the commit phase, verify the
     * working tree actually changed. Defaults to a real `git status
     * --porcelain` check; tests inject a fake.
     */
    private val diffChecker: suspend (ctx: WorkflowContext) -> Boolean = { ctx ->
        runCatching {
            val out = ProcessCommandRunner(timeoutMinutes = 1)
                .run("git status --porcelain", ctx.workspaceRoot)
            out.exitCode == 0 && out.stdout.isNotBlank()
        }.getOrDefault(false)
    },
) {

    private val _state = MutableStateFlow<WorkflowRunState?>(null)
    val state: StateFlow<WorkflowRunState?> = _state.asStateFlow()

    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Starts a new run for [goal]. Returns the final [OperationResult].
     */
    suspend fun start(goal: WorkflowGoal): OperationResult {
        val runId = "run-" + UUID.randomUUID().toString().take(8)
        cancelled.set(false)

        val plan = when (val outcome = decomposer.decompose(goal)) {
            is DecompositionOutcome.Planned -> outcome.plan
            is DecompositionOutcome.Refused -> {
                val failed = OperationResult.failure("Plan refused: ${outcome.reason}")
                _state.value = WorkflowRunState(runId, WorkflowPlan(goal, emptyList()), phase = WorkflowPhase.FAILED, finishedResult = failed)
                return failed
            }
        }

        val initial = WorkflowRunState(runId, plan, phase = WorkflowPhase.EXECUTING)
        _state.value = initial
        checkpointStore.saveRunState(initial)
        return continueRun(initial)
    }

    /**
     * Resumes an interrupted run. SUCCEEDED steps are not re-executed.
     * Returns FAILED honestly when no such run state exists.
     */
    suspend fun resume(runId: String): OperationResult {
        val saved = checkpointStore.loadRunState(runId)
            ?: return OperationResult.failure("Cannot resume: no saved state for run '$runId'")
        if (saved.phase == WorkflowPhase.DONE) {
            return saved.finishedResult
                ?: OperationResult.failure("Run '$runId' is marked DONE but has no final result")
        }
        cancelled.set(false)
        _state.value = saved
        return continueRun(saved)
    }

    private suspend fun continueRun(initial: WorkflowRunState): OperationResult {
        val runId = initial.runId
        val plan = initial.plan
        val ctx = contextFactory(runId)
        val collected = LinkedHashMap<String, StepRunResult>()
        collected.putAll(initial.stepResults)

        fun publish(phase: WorkflowPhase, notes: String = collected.values
            .firstOrNull { it.status == StepRunStatus.FAILED }?.result?.error ?: "") {
            val s = WorkflowRunState(
                runId = runId,
                plan = plan,
                stepResults = LinkedHashMap(collected),
                phase = phase,
                reviewNotes = notes,
                finishedResult = _state.value?.finishedResult,
            )
            _state.value = s
            checkpointStore.saveRunState(s)
        }

        val executor = planExecutorFactory { stepResult ->
            collected[stepResult.stepId] = stepResult
            publish(_state.value?.phase ?: WorkflowPhase.EXECUTING)
        }

        // ---- Phase 2: work steps ----
        publish(WorkflowPhase.EXECUTING)
        val workResults = executor.executeSubset(plan.workSteps(), ctx, collected)
        workResults.forEach { collected[it.stepId] = it }
        publish(WorkflowPhase.EXECUTING)

        val workFailed = workResults.any { it.status == StepRunStatus.FAILED }
        if (workFailed || cancelled.get()) {
            return finishFailed(runId, plan, collected,
                if (cancelled.get()) "Run cancelled by user" else "Work phase failed: step(s) did not succeed",
                ctx)
        }

        // ---- Phase 3: review gate (before any commit) ----
        publish(WorkflowPhase.REVIEWING)
        val allResults = plan.topologicalOrder().mapNotNull { collected[it.id] }
        val review = runCatching { reviewer.review(plan, allResults) }
            .getOrElse { e -> ReviewOutcome(ReviewVerdict.REJECT, "Reviewer threw: ${e.message}") }
        if (review.verdict != ReviewVerdict.APPROVE) {
            return finishFailed(runId, plan, collected,
                "Review did not approve (${review.verdict}): ${review.notes}", ctx,
                reviewNotes = review.notes)
        }
        publish(WorkflowPhase.REVIEWING, review.notes)

        // ---- Phase 4: commit phase ----
        val commitSteps = plan.commitSteps()
        if (commitSteps.isNotEmpty()) {
            // Refuse to commit a no-op (M0 rule): the working tree must show
            // a real diff, otherwise a no-op run could open a PR/commit.
            val hasDiff = runCatching { diffChecker(ctx) }.getOrDefault(false)
            if (!hasDiff) {
                return finishFailed(runId, plan, collected,
                    "Refusing to commit: working tree shows no changes (empty diff guard)", ctx,
                    reviewNotes = review.notes)
            }
            publish(WorkflowPhase.COMMITTING, review.notes)
            val commitResults = executor.executeSubset(commitSteps, ctx, collected)
            commitResults.forEach { collected[it.stepId] = it }
            publish(WorkflowPhase.COMMITTING, review.notes)
            if (commitResults.any { it.status == StepRunStatus.FAILED } || cancelled.get()) {
                return finishFailed(runId, plan, collected,
                    if (cancelled.get()) "Run cancelled during commit phase"
                    else "Commit phase failed: step(s) did not succeed", ctx,
                    reviewNotes = review.notes)
            }
        }

        val succeeded = collected.values.count { it.status == StepRunStatus.SUCCEEDED }
        val repaired = collected.values.count { it.repaired }
        val final = OperationResult.success(
            verificationEvidence = "Workflow '$runId' complete: $succeeded step(s) succeeded " +
                "($repaired repaired), review approved, " +
                (if (commitSteps.isEmpty()) "no commit phase" else "commit phase succeeded"),
            logs = listOf("Goal: ${plan.goal.title}", "Review: ${review.notes}"),
        )
        val done = WorkflowRunState(runId, plan, LinkedHashMap(collected), WorkflowPhase.DONE, review.notes, final)
        _state.value = done
        checkpointStore.saveRunState(done)
        return final
    }

    private fun finishFailed(
        runId: String,
        plan: WorkflowPlan,
        collected: Map<String, StepRunResult>,
        reason: String,
        ctx: WorkflowContext,
        reviewNotes: String = "",
    ): OperationResult {
        if (rollbackOnFailure) {
            runCatching { checkpointStore.rollback(runId, ctx.workspaceRoot) }
        }
        val failed = OperationResult.failure(reason)
        val state = WorkflowRunState(runId, plan, LinkedHashMap(collected), WorkflowPhase.FAILED, reviewNotes, failed)
        _state.value = state
        checkpointStore.saveRunState(state)
        return failed
    }
}
