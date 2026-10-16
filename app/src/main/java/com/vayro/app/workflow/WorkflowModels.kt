package com.vayro.app.workflow

import com.vayro.app.core.result.OperationResult

/**
 * M6: genuinely autonomous workflow — models.
 *
 * A [WorkflowGoal] is decomposed into a [WorkflowPlan] of [PlanStep]s, executed
 * in dependency order through the M5 safety gateway, with bounded auto-repair,
 * checkpoint-before-write, review-before-commit, and resumable run state.
 */

/** What kind of work the workflow is asked to do. */
enum class WorkflowGoalKind {
    /** Fix a reported bug: analyze -> implement -> test (repairable) -> commit -> push. */
    ISSUE_FIX,
    /** Build a new feature: analyze -> implement -> build -> test (repairable) -> commit -> push. */
    FEATURE,
    /** Restructure code without behavior change: analyze -> implement -> test (repairable) -> commit -> push. */
    REFACTOR,
    /** Caller-supplied step list, validated before execution. */
    CUSTOM,
}

data class WorkflowGoal(
    val title: String,
    val description: String = "",
    val kind: WorkflowGoalKind = WorkflowGoalKind.CUSTOM,
    /** Required for [WorkflowGoalKind.CUSTOM]; ignored for template kinds. */
    val customSteps: List<PlanStep> = emptyList(),
)

/** The kind of work a single plan step performs. */
enum class PlanStepKind {
    /** Read-only inspection of files (file reads through the safety gateway). */
    ANALYZE,
    /** The agent/AI produces the change (injected executor; SIMULATED results refused). */
    IMPLEMENT,
    /** Direct file write through the safety gateway, with checkpoint-before-write. */
    WRITE_FILE,
    /** Shell command through the safety gateway (build steps). */
    RUN_COMMAND,
    /** Shell command treated as a verification gate (test steps). */
    RUN_TESTS,
    /** Git commit through the safety gateway. Runs only after review approval. */
    GIT_COMMIT,
    /** Git push through the safety gateway. Runs only after review approval. */
    GIT_PUSH,
}

data class PlanStep(
    val id: String,
    val kind: PlanStepKind,
    val title: String,
    val detail: String = "",
    /**
     * Step payload, interpreted per kind:
     * - ANALYZE: comma-separated relative file paths to read.
     * - IMPLEMENT: the prompt sent to the agent executor.
     * - WRITE_FILE: relative path of the file to write.
     * - RUN_COMMAND / RUN_TESTS: the shell command to run.
     * - GIT_COMMIT: the commit message.
     * - GIT_PUSH: the remote name (default "origin").
     */
    val target: String = "",
    /** For WRITE_FILE: the content to write. */
    val payload: String = "",
    val dependsOn: List<String> = emptyList(),
    /** 1 = no repair retries. Higher = bounded auto-repair attempts. */
    val maxAttempts: Int = 1,
)

data class WorkflowPlan(
    val goal: WorkflowGoal,
    val steps: List<PlanStep>,
) {
    /**
     * Steps in dependency order. Throws [IllegalArgumentException] on unknown
     * dependencies or dependency cycles — a plan that cannot be ordered is
     * never executed.
     */
    fun topologicalOrder(): List<PlanStep> {
        val byId = steps.associateBy { it.id }
        steps.forEach { step ->
            step.dependsOn.forEach { dep ->
                require(byId.containsKey(dep)) {
                    "Plan step '${step.id}' depends on unknown step '$dep'"
                }
                require(dep != step.id) {
                    "Plan step '${step.id}' cannot depend on itself"
                }
            }
        }
        val ordered = mutableListOf<PlanStep>()
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()
        fun visit(step: PlanStep) {
            if (step.id in visited) return
            require(inStack.add(step.id)) {
                "Dependency cycle detected involving plan step '${step.id}'"
            }
            step.dependsOn.forEach { visit(byId.getValue(it)) }
            inStack.remove(step.id)
            visited.add(step.id)
            ordered.add(step)
        }
        steps.forEach { visit(it) }
        return ordered
    }

    /** Commit-phase steps: they run only after the review gate approves. */
    fun commitSteps(): List<PlanStep> =
        steps.filter { it.kind == PlanStepKind.GIT_COMMIT || it.kind == PlanStepKind.GIT_PUSH }

    /** All steps except the commit phase. */
    fun workSteps(): List<PlanStep> =
        steps.filter { it.kind != PlanStepKind.GIT_COMMIT && it.kind != PlanStepKind.GIT_PUSH }
}

enum class StepRunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
}

data class StepRunResult(
    val stepId: String,
    val status: StepRunStatus,
    val attempts: Int = 0,
    val repaired: Boolean = false,
    val result: OperationResult? = null,
)

enum class WorkflowPhase {
    PLANNING,
    EXECUTING,
    REVIEWING,
    COMMITTING,
    DONE,
    FAILED,
}

data class WorkflowRunState(
    val runId: String,
    val plan: WorkflowPlan,
    val stepResults: Map<String, StepRunResult> = emptyMap(),
    val phase: WorkflowPhase = WorkflowPhase.PLANNING,
    val reviewNotes: String = "",
    val finishedResult: OperationResult? = null,
)
