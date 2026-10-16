package com.vayro.app.workflow

import com.vayro.app.core.result.OperationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M6: plan execution — dependency order, dependent skipping on failure,
 * bounded auto-repair, and resume.
 */
class PlanExecutorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: WorkflowTestHarness

    @Before
    fun setUp() {
        harness = WorkflowTestHarness(
            workspaceRoot = tempFolder.newFolder("workspace"),
            policyDir = tempFolder.newFolder("policy"),
            checkpointBase = tempFolder.newFolder("checkpoints"),
        )
    }

    private fun executorFor(
        advisor: RepairAdvisor = AutoFixRepairAdvisor(),
        behavior: suspend (PlanStep, WorkflowContext) -> OperationResult,
    ): Pair<PlanExecutor, FakeStepExecutor> {
        val fake = FakeStepExecutor(PlanStepKind.RUN_COMMAND, behavior)
        val exec = PlanExecutor(mapOf(PlanStepKind.RUN_COMMAND to fake), advisor)
        return exec to fake
    }

    private fun ctx() = harness.context("run-x")

    @Test
    fun `steps run in dependency order`() = runBlocking {
        val order = mutableListOf<String>()
        val (exec, _) = executorFor { step, _ ->
            order.add(step.id)
            OperationResult.success()
        }
        val steps = listOf(
            PlanStep("c", PlanStepKind.RUN_COMMAND, "C", dependsOn = listOf("b")),
            PlanStep("a", PlanStepKind.RUN_COMMAND, "A"),
            PlanStep("b", PlanStepKind.RUN_COMMAND, "B", dependsOn = listOf("a")),
        )
        val results = exec.executeSubset(steps, ctx())
        assertEquals(listOf("a", "b", "c"), order)
        assertTrue(results.all { it.status == StepRunStatus.SUCCEEDED })
    }

    @Test
    fun `failed step skips its dependents`() = runBlocking {
        val (exec, _) = executorFor { step, _ ->
            if (step.id == "a") OperationResult.failure("a broke") else OperationResult.success()
        }
        val steps = listOf(
            PlanStep("a", PlanStepKind.RUN_COMMAND, "A"),
            PlanStep("b", PlanStepKind.RUN_COMMAND, "B", dependsOn = listOf("a")),
            PlanStep("c", PlanStepKind.RUN_COMMAND, "C"),
        )
        val results = exec.executeSubset(steps, ctx()).associateBy { it.stepId }
        assertEquals(StepRunStatus.FAILED, results.getValue("a").status)
        assertEquals(StepRunStatus.SKIPPED, results.getValue("b").status)
        assertEquals(StepRunStatus.SUCCEEDED, results.getValue("c").status)
    }

    @Test
    fun `repair retries a failing step within its attempt budget`() = runBlocking {
        var calls = 0
        val (exec, _) = executorFor { _, _ ->
            calls++
            if (calls < 3) OperationResult.failure("flaky") else OperationResult.success()
        }
        val steps = listOf(PlanStep("a", PlanStepKind.RUN_COMMAND, "A", maxAttempts = 3))
        val results = exec.executeSubset(steps, ctx())
        val r = results.single()
        assertEquals(StepRunStatus.SUCCEEDED, r.status)
        assertEquals(3, r.attempts)
        assertTrue(r.repaired)
        assertEquals(3, calls)
    }

    @Test
    fun `exhausted repair budget fails the step`() = runBlocking {
        var calls = 0
        val (exec, _) = executorFor { _, _ ->
            calls++
            OperationResult.failure("always broken")
        }
        val steps = listOf(PlanStep("a", PlanStepKind.RUN_COMMAND, "A", maxAttempts = 2))
        val results = exec.executeSubset(steps, ctx())
        val r = results.single()
        assertEquals(StepRunStatus.FAILED, r.status)
        assertEquals(2, r.attempts)
        assertTrue(r.repaired)
        assertEquals(2, calls)
    }

    @Test
    fun `advisor declining retry stops after first attempt`() = runBlocking {
        var calls = 0
        val noRetry = object : RepairAdvisor {
            override suspend fun advise(step: PlanStep, failed: OperationResult, attempt: Int, maxAttempts: Int) =
                RepairAdvice(false, "not worth retrying")
        }
        val (exec, _) = executorFor(noRetry) { _, _ ->
            calls++
            OperationResult.failure("broken")
        }
        val steps = listOf(PlanStep("a", PlanStepKind.RUN_COMMAND, "A", maxAttempts = 5))
        val results = exec.executeSubset(steps, ctx())
        assertEquals(1, calls)
        assertEquals(StepRunStatus.FAILED, results.single().status)
        assertFalse(results.single().repaired)
    }

    @Test
    fun `missing executor fails the step honestly`() = runBlocking {
        val exec = PlanExecutor(emptyMap())
        val steps = listOf(PlanStep("a", PlanStepKind.ANALYZE, "A"))
        val results = exec.executeSubset(steps, ctx())
        assertEquals(StepRunStatus.FAILED, results.single().status)
        assertTrue(results.single().result!!.error!!.contains("No executor"))
    }

    @Test
    fun `resume keeps succeeded steps and reruns the rest`() = runBlocking {
        val calls = mutableListOf<String>()
        val (exec, _) = executorFor { step, _ ->
            calls.add(step.id)
            OperationResult.success()
        }
        val steps = listOf(
            PlanStep("a", PlanStepKind.RUN_COMMAND, "A"),
            PlanStep("b", PlanStepKind.RUN_COMMAND, "B", dependsOn = listOf("a")),
        )
        val previous = mapOf(
            "a" to StepRunResult("a", StepRunStatus.SUCCEEDED, 1, false, OperationResult.success())
        )
        val results = exec.executeSubset(steps, ctx(), previous).associateBy { it.stepId }
        assertEquals(listOf("b"), calls) // 'a' was not re-executed
        assertEquals(StepRunStatus.SUCCEEDED, results.getValue("a").status)
        assertEquals(StepRunStatus.SUCCEEDED, results.getValue("b").status)
    }

    @Test
    fun `dependency cycle inside subset fails honestly`() = runBlocking {
        val exec = PlanExecutor(emptyMap())
        val a = PlanStep("a", PlanStepKind.RUN_COMMAND, "A", dependsOn = listOf("b"))
        val b = PlanStep("b", PlanStepKind.RUN_COMMAND, "B", dependsOn = listOf("a"))
        val results = exec.executeSubset(listOf(a, b), ctx())
        assertTrue(results.all { it.status == StepRunStatus.FAILED })
        assertTrue(results.first().result!!.error!!.contains("cycle", ignoreCase = true))
    }

    @Test
    fun `invalid plan fails every step with the reason`() = runBlocking {
        val exec = PlanExecutor(emptyMap())
        val steps = listOf(PlanStep("a", PlanStepKind.RUN_COMMAND, "A", dependsOn = listOf("ghost")))
        val results = exec.executeSubset(steps, ctx())
        assertEquals(StepRunStatus.FAILED, results.single().status)
        assertTrue(results.single().result!!.error!!.contains("not satisfied"))
    }
}
