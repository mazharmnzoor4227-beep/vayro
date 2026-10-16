package com.vayro.app.workflow

/**
 * M6: task decomposition.
 *
 * Decomposition is template-driven and honest: the decomposer never invents
 * capabilities it does not have. Template kinds map a goal to a fixed,
 * validated step sequence; CUSTOM goals carry an explicit caller-supplied step
 * list that is validated (unique ids, known dependencies, no cycles). Anything
 * else is refused with a reason — never a hallucinated plan.
 */

sealed class DecompositionOutcome {
    data class Planned(val plan: WorkflowPlan) : DecompositionOutcome()
    data class Refused(val reason: String) : DecompositionOutcome()
}

interface TaskDecomposer {
    fun decompose(goal: WorkflowGoal): DecompositionOutcome
}

class TemplateTaskDecomposer : TaskDecomposer {

    override fun decompose(goal: WorkflowGoal): DecompositionOutcome {
        if (goal.title.isBlank()) {
            return DecompositionOutcome.Refused("Goal title is blank: nothing to decompose")
        }
        val steps: List<PlanStep> = when (goal.kind) {
            WorkflowGoalKind.ISSUE_FIX -> listOf(
                PlanStep("analyze", PlanStepKind.ANALYZE, "Analyze the issue",
                    "Read the files named in the issue and locate the fault."),
                PlanStep("implement", PlanStepKind.IMPLEMENT, "Implement the fix",
                    "Apply the minimal change that resolves the issue.",
                    target = goal.description, dependsOn = listOf("analyze")),
                PlanStep("test", PlanStepKind.RUN_TESTS, "Run tests",
                    "Verify the fix with the project's test suite.",
                    target = "test", dependsOn = listOf("implement"), maxAttempts = 3),
                PlanStep("commit", PlanStepKind.GIT_COMMIT, "Commit the fix",
                    "Commit the verified change.",
                    target = goal.title, dependsOn = listOf("test")),
                PlanStep("push", PlanStepKind.GIT_PUSH, "Push the branch",
                    "Push the committed change to the remote.",
                    target = "origin", dependsOn = listOf("commit")),
            )
            WorkflowGoalKind.FEATURE -> listOf(
                PlanStep("analyze", PlanStepKind.ANALYZE, "Analyze the feature request",
                    "Read the relevant files and understand where the feature belongs."),
                PlanStep("implement", PlanStepKind.IMPLEMENT, "Implement the feature",
                    "Build the feature as described.",
                    target = goal.description, dependsOn = listOf("analyze")),
                PlanStep("build", PlanStepKind.RUN_COMMAND, "Build the project",
                    "Compile the project to catch build breaks early.",
                    target = "build", dependsOn = listOf("implement"), maxAttempts = 2),
                PlanStep("test", PlanStepKind.RUN_TESTS, "Run tests",
                    "Verify the feature with the project's test suite.",
                    target = "test", dependsOn = listOf("build"), maxAttempts = 3),
                PlanStep("commit", PlanStepKind.GIT_COMMIT, "Commit the feature",
                    "Commit the verified change.",
                    target = goal.title, dependsOn = listOf("test")),
                PlanStep("push", PlanStepKind.GIT_PUSH, "Push the branch",
                    "Push the committed change to the remote.",
                    target = "origin", dependsOn = listOf("commit")),
            )
            WorkflowGoalKind.REFACTOR -> listOf(
                PlanStep("analyze", PlanStepKind.ANALYZE, "Analyze the refactor target",
                    "Read the files to restructure and map their dependencies."),
                PlanStep("implement", PlanStepKind.IMPLEMENT, "Apply the refactor",
                    "Restructure the code without changing behavior.",
                    target = goal.description, dependsOn = listOf("analyze")),
                PlanStep("test", PlanStepKind.RUN_TESTS, "Run tests",
                    "Confirm no behavior changed with the project's test suite.",
                    target = "test", dependsOn = listOf("implement"), maxAttempts = 3),
                PlanStep("commit", PlanStepKind.GIT_COMMIT, "Commit the refactor",
                    "Commit the verified change.",
                    target = goal.title, dependsOn = listOf("test")),
                PlanStep("push", PlanStepKind.GIT_PUSH, "Push the branch",
                    "Push the committed change to the remote.",
                    target = "origin", dependsOn = listOf("commit")),
            )
            WorkflowGoalKind.CUSTOM -> return decomposeCustom(goal)
        }
        return DecompositionOutcome.Planned(WorkflowPlan(goal, steps))
    }

    private fun decomposeCustom(goal: WorkflowGoal): DecompositionOutcome {
        if (goal.customSteps.isEmpty()) {
            return DecompositionOutcome.Refused(
                "CUSTOM goal '${goal.title}' carries no steps: supply an explicit step list"
            )
        }
        val ids = goal.customSteps.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            return DecompositionOutcome.Refused(
                "CUSTOM goal '${goal.title}' has duplicate step ids: ${duplicates.joinToString()}"
            )
        }
        if (goal.customSteps.any { it.title.isBlank() }) {
            return DecompositionOutcome.Refused(
                "CUSTOM goal '${goal.title}' has a step with a blank title"
            )
        }
        val plan = WorkflowPlan(goal, goal.customSteps)
        return try {
            plan.topologicalOrder()
            DecompositionOutcome.Planned(plan)
        } catch (e: IllegalArgumentException) {
            DecompositionOutcome.Refused("CUSTOM goal '${goal.title}' is invalid: ${e.message}")
        }
    }
}
