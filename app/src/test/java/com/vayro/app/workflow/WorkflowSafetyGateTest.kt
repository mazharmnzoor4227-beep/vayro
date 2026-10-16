package com.vayro.app.workflow

import com.vayro.app.core.result.OperationResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M6 acceptance gate: every workflow side effect passes through the M5
 * safety gateway. A denied operation never executes its effect lambda, and
 * the run fails honestly instead of bypassing the gate.
 */
class WorkflowSafetyGateTest {

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

    private fun runner(
        reviewer: WorkflowReviewer = TestOnlyAutoApproveReviewer(),
        commandRunner: FakeCommandRunner = FakeCommandRunner(),
    ): WorkflowRunner {
        val executors: Map<PlanStepKind, StepExecutor> = mapOf(
            PlanStepKind.WRITE_FILE to WriteFileStepExecutor(),
            PlanStepKind.RUN_TESTS to CommandStepExecutor(PlanStepKind.RUN_TESTS, commandRunner),
        )
        return WorkflowRunner(
            decomposer = TemplateTaskDecomposer(),
            planExecutorFactory = { onStep ->
                PlanExecutor(executors, AutoFixRepairAdvisor(), onStepUpdate = onStep)
            },
            reviewer = reviewer,
            contextFactory = { runId -> harness.context(runId) },
            checkpointStore = harness.checkpointStore,
            diffChecker = { true },
        )
    }

    private fun writeGoal(target: String, payload: String = "x") = WorkflowGoal(
        title = "Write attempt",
        kind = WorkflowGoalKind.CUSTOM,
        customSteps = listOf(
            PlanStep("w", PlanStepKind.WRITE_FILE, "Write", target = target, payload = payload),
        ),
    )

    @Test
    fun `explicitly denied file write never executes`() = runBlocking {
        harness.denyFileWrites()
        val result = runner().start(writeGoal("denied.txt"))

        assertFalse(result.isSuccess)
        assertFalse("Denied write must not create the file",
            File(harness.workspaceRoot, "denied.txt").exists())
    }

    @Test
    fun `denied write step reports the gateway reason`() = runBlocking {
        harness.denyFileWrites()
        val r = runner()
        r.start(writeGoal("denied2.txt"))
        val stepResult = r.state.value!!.stepResults.getValue("w")
        assertEquals(StepRunStatus.FAILED, stepResult.status)
        assertTrue(stepResult.result!!.error!!.contains("denied", ignoreCase = true))
    }

    @Test
    fun `path traversal in a write step is a hard deny`() = runBlocking {
        val result = runner().start(writeGoal("../../escape.txt", "evil"))
        assertFalse(result.isSuccess)
        assertFalse(File(harness.workspaceRoot, "../../escape.txt").exists())
    }

    @Test
    fun `critical shell command never executes its runner`() = runBlocking {
        val commands = FakeCommandRunner()
        val r = runner(commandRunner = commands)
        val goal = WorkflowGoal(
            title = "Evil test",
            kind = WorkflowGoalKind.CUSTOM,
            customSteps = listOf(
                PlanStep("t", PlanStepKind.RUN_TESTS, "Tests", target = "rm -rf /", maxAttempts = 1),
            ),
        )
        val result = r.start(goal)
        assertFalse(result.isSuccess)
        assertTrue("Denied command must never reach the runner", commands.commands.isEmpty())
        val stepResult = r.state.value!!.stepResults.getValue("t")
        assertEquals(StepRunStatus.FAILED, stepResult.status)
    }

    @Test
    fun `kill switch blocks the whole run before any write`() = runBlocking {
        harness.policyEngine.engageEmergencyKillSwitch("test stop")
        val result = runner().start(writeGoal("blocked.txt"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("failed", ignoreCase = true))
        assertFalse(File(harness.workspaceRoot, "blocked.txt").exists())
    }

    @Test
    fun `safety deny is not repaired by the auto-fix loop`() = runBlocking {
        harness.denyFileWrites()
        var advisorCalls = 0
        val countingAdvisor = object : RepairAdvisor {
            override suspend fun advise(
                step: PlanStep, failed: OperationResult, attempt: Int, maxAttempts: Int,
            ): RepairAdvice {
                advisorCalls++
                return RepairAdvice(shouldRetry = true)
            }
        }
        val executors: Map<PlanStepKind, StepExecutor> = mapOf(
            PlanStepKind.WRITE_FILE to WriteFileStepExecutor(),
        )
        val r = WorkflowRunner(
            decomposer = TemplateTaskDecomposer(),
            planExecutorFactory = { onStep ->
                PlanExecutor(executors, countingAdvisor, onStepUpdate = onStep)
            },
            reviewer = TestOnlyAutoApproveReviewer(),
            contextFactory = { runId -> harness.context(runId) },
            checkpointStore = harness.checkpointStore,
            diffChecker = { true },
        )
        r.start(writeGoal("denied3.txt", "x").copy(
            customSteps = listOf(
                PlanStep("w", PlanStepKind.WRITE_FILE, "Write",
                    target = "denied3.txt", payload = "x", maxAttempts = 5)
            )
        ))
        val stepResult = r.state.value!!.stepResults.getValue("w")
        assertEquals(1, stepResult.attempts)
        assertEquals(0, advisorCalls)
        assertEquals(StepRunStatus.FAILED, stepResult.status)
    }
}
