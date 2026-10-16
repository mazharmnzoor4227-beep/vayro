package com.vayro.app.workflow

import com.vayro.app.agent.AutonomousLoopEngine
import com.vayro.app.core.result.OperationResult
import com.vayro.app.core.result.OperationStatus
import com.vayro.app.security.SafetyDeniedException
import com.vayro.app.security.SafetyPromptRequiredException

/**
 * M6: plan execution — dependency-ordered, with bounded auto-repair.
 *
 * Steps run in [WorkflowPlan.topologicalOrder]. A step whose dependencies did
 * not all succeed is SKIPPED (never executed blindly). When a step fails and
 * has attempts left (`maxAttempts`), the [RepairAdvisor] is consulted: if it
 * advises a retry, the step is re-executed with an auto-fix prompt built by
 * [AutonomousLoopEngine.buildAutoFixPrompt]. Repair is bounded by
 * `maxAttempts` — once exhausted, the step is FAILED and its dependents are
 * skipped. A safety DENY is never repaired: it fails the step immediately.
 *
 * [executeSubset] supports resume: already-SUCCEEDED steps from a previous
 * run are kept and not re-executed.
 */

data class RepairAdvice(
    val shouldRetry: Boolean,
    val note: String = "",
)

interface RepairAdvisor {
    suspend fun advise(step: PlanStep, failed: OperationResult, attempt: Int, maxAttempts: Int): RepairAdvice
}

/**
 * Default advisor: retries while attempts remain, attaching the standard
 * auto-fix prompt (built from real compiler/test output) as the note.
 */
class AutoFixRepairAdvisor : RepairAdvisor {
    override suspend fun advise(
        step: PlanStep,
        failed: OperationResult,
        attempt: Int,
        maxAttempts: Int,
    ): RepairAdvice {
        if (attempt >= maxAttempts) return RepairAdvice(false, "Attempt budget exhausted ($attempt/$maxAttempts)")
        val prompt = AutonomousLoopEngine.buildAutoFixPrompt(
            com.vayro.app.agent.BuildVerificationResult(
                success = false,
                toolName = step.kind.name,
                command = step.target,
                output = failed.logs.joinToString("\n"),
                errors = failed.error?.let { listOf(it) } ?: emptyList(),
            ),
            iteration = attempt,
            maxIterations = maxAttempts,
        )
        return RepairAdvice(true, prompt)
    }
}

class PlanExecutor(
    private val executors: Map<PlanStepKind, StepExecutor>,
    private val repairAdvisor: RepairAdvisor = AutoFixRepairAdvisor(),
    private val isCancelled: () -> Boolean = { false },
    private val onStepUpdate: ((StepRunResult) -> Unit)? = null,
) {

    /**
     * Executes [steps] (already filtered to the desired phase) in dependency
     * order. [previous] carries results from an earlier partial run for
     * resume; SUCCEEDED steps are kept without re-execution.
     */
    suspend fun executeSubset(
        steps: List<PlanStep>,
        ctx: WorkflowContext,
        previous: Map<String, StepRunResult> = emptyMap(),
    ): List<StepRunResult> {
        val ids = steps.map { it.id }.toSet()
        // Dependencies outside this subset (e.g. commit steps depending on
        // work steps) must already be SUCCEEDED in `previous`; they are not
        // re-executed here.
        val unsatisfied = steps.flatMap { step ->
            step.dependsOn
                .filter { it !in ids }
                .filter { depId -> previous[depId]?.status != StepRunStatus.SUCCEEDED }
                .map { depId -> "'${step.id}' needs '$depId'" }
        }
        if (unsatisfied.isNotEmpty()) {
            val reason = "External dependencies not satisfied: ${unsatisfied.joinToString()}"
            return steps.map {
                StepRunResult(it.id, StepRunStatus.FAILED, 0, false,
                    OperationResult.failure(reason))
            }
        }
        val byId = steps.associateBy { it.id }
        val internalSteps = steps.map { it.copy(dependsOn = it.dependsOn.filter { dep -> dep in ids }) }
        val orderedIds = try {
            WorkflowPlan(WorkflowGoal("subset"), internalSteps).topologicalOrder().map { it.id }
        } catch (e: IllegalArgumentException) {
            return steps.map {
                StepRunResult(it.id, StepRunStatus.FAILED, 0, false,
                    OperationResult.failure("Plan is invalid: ${e.message}"))
            }
        }
        val ordered = orderedIds.map { byId.getValue(it) }
        val results = LinkedHashMap<String, StepRunResult>()
        // Seed with previous results; resume keeps successes.
        previous.forEach { (id, r) -> results[id] = r }

        var cancelNoted = false
        for (step in ordered) {
            if (isCancelled()) {
                // Never overwrite a resumed success; mark the first cancelled
                // step FAILED and the rest SKIPPED (they never started).
                if (!results.containsKey(step.id)) {
                    val res = if (!cancelNoted) {
                        cancelNoted = true
                        StepRunResult(step.id, StepRunStatus.FAILED, 0, false,
                            OperationResult.failure("Run cancelled before step '${step.id}'"))
                    } else {
                        StepRunResult(step.id, StepRunStatus.SKIPPED, 0, false,
                            OperationResult.failure("Skipped: run was cancelled"))
                    }
                    results[step.id] = res
                    onStepUpdate?.invoke(res)
                }
                continue
            }
            val kept = results[step.id]
            if (kept != null && kept.status == StepRunStatus.SUCCEEDED) {
                continue // resume: already done
            }
            val depFailed = step.dependsOn.any { depId ->
                val dep = results[depId]
                dep == null || dep.status != StepRunStatus.SUCCEEDED
            }
            if (depFailed) {
                val skipped = StepRunResult(step.id, StepRunStatus.SKIPPED, 0, false,
                    OperationResult.failure("Skipped: a dependency of '${step.id}' did not succeed"))
                results[step.id] = skipped
                onStepUpdate?.invoke(skipped)
                continue
            }
            val executor = executors[step.kind]
            if (executor == null) {
                val failed = StepRunResult(step.id, StepRunStatus.FAILED, 0, false,
                    OperationResult.failure("No executor registered for step kind ${step.kind}"))
                results[step.id] = failed
                onStepUpdate?.invoke(failed)
                continue
            }
            results[step.id] = runWithRepair(step, executor, ctx)
            onStepUpdate?.invoke(results.getValue(step.id))
        }
        return ordered.map { results.getValue(it.id) }
    }

    private suspend fun runWithRepair(
        step: PlanStep,
        executor: StepExecutor,
        ctx: WorkflowContext,
    ): StepRunResult {
        val maxAttempts = maxOf(1, step.maxAttempts)
        var attempt = 0
        var repaired = false
        var last: OperationResult? = null

        while (attempt < maxAttempts) {
            attempt++
            onStepUpdate?.invoke(StepRunResult(step.id, StepRunStatus.RUNNING, attempt, repaired, last))
            val outcome = runCatching { executor.execute(step, ctx) }
                .getOrElse { e ->
                    // Safety DENY/PROMPT (and anything else thrown) becomes an
                    // honest FAILED result. A DENY is never repaired: retrying
                    // a denied operation would be a bypass attempt, not a fix.
                    // A PROMPT means "do not execute yet": it needs a user
                    // grant first, so the run fails and resume retries later.
                    when (e) {
                        is SafetyDeniedException -> return StepRunResult(
                            step.id, StepRunStatus.FAILED, attempt, repaired,
                            OperationResult.failure("Safety gateway denied step '${step.id}': ${e.message}")
                        )
                        is SafetyPromptRequiredException -> return StepRunResult(
                            step.id, StepRunStatus.FAILED, attempt, repaired,
                            OperationResult.failure(
                                "Step '${step.id}' requires authorization before it can run: " +
                                    "${e.operation.describe()} (grant the permission, then resume the run)"
                            )
                        )
                        else -> OperationResult.failure("Step '${step.id}' threw: ${e.message}")
                    }
                }
            last = outcome
            if (outcome.status == OperationStatus.SUCCESS) {
                return StepRunResult(step.id, StepRunStatus.SUCCEEDED, attempt, repaired, outcome)
            }
            if (attempt >= maxAttempts) break
            val advice = runCatching { repairAdvisor.advise(step, outcome, attempt, maxAttempts) }
                .getOrDefault(RepairAdvice(false, "Repair advisor failed"))
            if (!advice.shouldRetry) break
            repaired = true
        }
        return StepRunResult(step.id, StepRunStatus.FAILED, attempt, repaired, last)
    }
}
