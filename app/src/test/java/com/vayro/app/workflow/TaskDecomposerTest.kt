package com.vayro.app.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6: task decomposition — template plans per goal kind, CUSTOM validation,
 * and honest refusals.
 */
class TaskDecomposerTest {

    private val decomposer = TemplateTaskDecomposer()

    private fun planned(goal: WorkflowGoal): WorkflowPlan {
        val outcome = decomposer.decompose(goal)
        assertTrue("Expected Planned but got $outcome", outcome is DecompositionOutcome.Planned)
        return (outcome as DecompositionOutcome.Planned).plan
    }

    @Test
    fun `issue fix template has the full loop in dependency order`() {
        val plan = planned(WorkflowGoal("Crash on launch", "NullPointer in MainActivity", WorkflowGoalKind.ISSUE_FIX))
        val kinds = plan.steps.map { it.kind }
        assertEquals(
            listOf(
                PlanStepKind.ANALYZE,
                PlanStepKind.IMPLEMENT,
                PlanStepKind.RUN_TESTS,
                PlanStepKind.GIT_COMMIT,
                PlanStepKind.GIT_PUSH,
            ),
            kinds
        )
        // Dependency order must be resolvable.
        val ordered = plan.topologicalOrder()
        assertEquals(kinds, ordered.map { it.kind })
        // Test step gets a repair budget; commit/push are commit-phase.
        assertTrue(plan.steps.first { it.kind == PlanStepKind.RUN_TESTS }.maxAttempts > 1)
        assertEquals(2, plan.commitSteps().size)
        assertEquals(3, plan.workSteps().size)
    }

    @Test
    fun `feature template includes a build step before tests`() {
        val plan = planned(WorkflowGoal("Add export", "CSV export", WorkflowGoalKind.FEATURE))
        val kinds = plan.steps.map { it.kind }
        assertTrue(kinds.indexOf(PlanStepKind.RUN_COMMAND) < kinds.indexOf(PlanStepKind.RUN_TESTS))
        plan.topologicalOrder()
    }

    @Test
    fun `refactor template plans analyze implement test commit push`() {
        val plan = planned(WorkflowGoal("Split God class", "Extract coordinators", WorkflowGoalKind.REFACTOR))
        assertEquals(
            listOf(
                PlanStepKind.ANALYZE,
                PlanStepKind.IMPLEMENT,
                PlanStepKind.RUN_TESTS,
                PlanStepKind.GIT_COMMIT,
                PlanStepKind.GIT_PUSH,
            ),
            plan.steps.map { it.kind }
        )
    }

    @Test
    fun `custom goal with valid steps is accepted`() {
        val goal = WorkflowGoal(
            title = "Custom docs run",
            kind = WorkflowGoalKind.CUSTOM,
            customSteps = listOf(
                PlanStep("w", PlanStepKind.WRITE_FILE, "Write notes", target = "notes.txt", payload = "hi"),
                PlanStep("c", PlanStepKind.GIT_COMMIT, "Commit", target = "docs", dependsOn = listOf("w")),
            ),
        )
        val plan = planned(goal)
        assertEquals(listOf("w", "c"), plan.topologicalOrder().map { it.id })
    }

    @Test
    fun `blank goal title is refused`() {
        val outcome = decomposer.decompose(WorkflowGoal("", kind = WorkflowGoalKind.ISSUE_FIX))
        assertTrue(outcome is DecompositionOutcome.Refused)
    }

    @Test
    fun `custom goal without steps is refused`() {
        val outcome = decomposer.decompose(WorkflowGoal("Empty", kind = WorkflowGoalKind.CUSTOM))
        assertTrue(outcome is DecompositionOutcome.Refused)
    }

    @Test
    fun `custom goal with duplicate ids is refused`() {
        val outcome = decomposer.decompose(WorkflowGoal(
            title = "Dup",
            kind = WorkflowGoalKind.CUSTOM,
            customSteps = listOf(
                PlanStep("a", PlanStepKind.ANALYZE, "First"),
                PlanStep("a", PlanStepKind.ANALYZE, "Second"),
            ),
        ))
        assertTrue(outcome is DecompositionOutcome.Refused)
    }

    @Test
    fun `custom goal with unknown dependency is refused`() {
        val outcome = decomposer.decompose(WorkflowGoal(
            title = "Bad dep",
            kind = WorkflowGoalKind.CUSTOM,
            customSteps = listOf(
                PlanStep("a", PlanStepKind.ANALYZE, "First", dependsOn = listOf("nope")),
            ),
        ))
        assertTrue(outcome is DecompositionOutcome.Refused)
    }

    @Test
    fun `custom goal with dependency cycle is refused`() {
        val outcome = decomposer.decompose(WorkflowGoal(
            title = "Cycle",
            kind = WorkflowGoalKind.CUSTOM,
            customSteps = listOf(
                PlanStep("a", PlanStepKind.ANALYZE, "First", dependsOn = listOf("b")),
                PlanStep("b", PlanStepKind.ANALYZE, "Second", dependsOn = listOf("a")),
            ),
        ))
        assertTrue(outcome is DecompositionOutcome.Refused)
    }
}
