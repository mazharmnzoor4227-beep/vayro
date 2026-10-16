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
 * M6: the autonomous workflow runner — plan → implement → build → test →
 * fix → review → approve → commit, with rollback and resume.
 */
class WorkflowRunnerTest {

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

    private fun customGoal(vararg steps: PlanStep) = WorkflowGoal(
        title = "Custom run",
        description = "test",
        kind = WorkflowGoalKind.CUSTOM,
        customSteps = steps.toList(),
    )

    private fun fullPlanGoal() = customGoal(
        PlanStep("write", PlanStepKind.WRITE_FILE, "Write file", target = "out.txt", payload = "hello"),
        PlanStep("test", PlanStepKind.RUN_TESTS, "Run tests", target = "echo ok",
            dependsOn = listOf("write")),
        PlanStep("commit", PlanStepKind.GIT_COMMIT, "Commit", target = "test commit",
            dependsOn = listOf("test")),
        PlanStep("push", PlanStepKind.GIT_PUSH, "Push", target = "origin",
            dependsOn = listOf("commit")),
    )

    private fun buildRunner(
        reviewer: WorkflowReviewer = TestOnlyAutoApproveReviewer(),
        diff: Boolean = true,
        agentExecutor: suspend (String) -> OperationResult =
            { OperationResult.success(verificationEvidence = "agent implemented") },
        commandRunner: FakeCommandRunner = FakeCommandRunner(),
        gitRunner: FakeGitRunner = FakeGitRunner(),
        executors: Map<PlanStepKind, StepExecutor>? = null,
    ): WorkflowRunner {
        val execMap = executors ?: mapOf(
            PlanStepKind.ANALYZE to AnalyzeStepExecutor(),
            PlanStepKind.IMPLEMENT to ImplementStepExecutor(agentExecutor),
            PlanStepKind.WRITE_FILE to WriteFileStepExecutor(),
            PlanStepKind.RUN_COMMAND to CommandStepExecutor(PlanStepKind.RUN_COMMAND, commandRunner),
            PlanStepKind.RUN_TESTS to CommandStepExecutor(PlanStepKind.RUN_TESTS, commandRunner),
            PlanStepKind.GIT_COMMIT to GitStepExecutor(PlanStepKind.GIT_COMMIT, gitRunner),
            PlanStepKind.GIT_PUSH to GitStepExecutor(PlanStepKind.GIT_PUSH, gitRunner),
        )
        return WorkflowRunner(
            decomposer = TemplateTaskDecomposer(),
            planExecutorFactory = { onStep ->
                PlanExecutor(execMap, AutoFixRepairAdvisor(), onStepUpdate = onStep)
            },
            reviewer = reviewer,
            contextFactory = { runId -> harness.context(runId) },
            checkpointStore = harness.checkpointStore,
            diffChecker = { diff },
        )
    }

    @Test
    fun `full loop succeeds and commits after review approval`() = runBlocking {
        harness.allowGitPush()
        val gitRunner = FakeGitRunner()
        val runner = buildRunner(gitRunner = gitRunner)
        val result = runner.start(fullPlanGoal())

        assertTrue("Expected success, got: ${result.error}", result.isSuccess)
        assertTrue(result.verificationEvidence!!.contains("review approved"))
        assertEquals("hello", File(harness.workspaceRoot, "out.txt").readText())
        val kinds = gitRunner.invocations.map { it.first() }
        assertTrue(kinds.contains("commit"))
        assertTrue(kinds.contains("push"))
        assertEquals(WorkflowPhase.DONE, runner.state.value!!.phase)
    }

    @Test
    fun `review rejection stops before commit and rolls back writes`() = runBlocking {
        harness.allowGitPush()
        val gitRunner = FakeGitRunner()
        val reviewer = CallbackWorkflowReviewer { _, _ ->
            ReviewOutcome(ReviewVerdict.REJECT, "not good enough")
        }
        val runner = buildRunner(reviewer = reviewer, gitRunner = gitRunner)
        val result = runner.start(fullPlanGoal())

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("did not approve"))
        assertTrue("Commit must never run after rejection", gitRunner.invocations.isEmpty())
        assertFalse("Written file must be rolled back", File(harness.workspaceRoot, "out.txt").exists())
        assertEquals(WorkflowPhase.FAILED, runner.state.value!!.phase)
    }

    @Test
    fun `empty diff refuses the commit phase`() = runBlocking {
        val gitRunner = FakeGitRunner()
        val runner = buildRunner(diff = false, gitRunner = gitRunner)
        val result = runner.start(fullPlanGoal())

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("no changes"))
        assertTrue(gitRunner.invocations.isEmpty())
    }

    @Test
    fun `refused plan fails honestly without executing anything`() = runBlocking {
        val gitRunner = FakeGitRunner()
        val runner = buildRunner(gitRunner = gitRunner)
        val result = runner.start(WorkflowGoal("", kind = WorkflowGoalKind.ISSUE_FIX))

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("Plan refused"))
        assertTrue(gitRunner.invocations.isEmpty())
    }

    @Test
    fun `simulated agent result is refused at the implement step`() = runBlocking {
        val runner = buildRunner(
            agentExecutor = { OperationResult.simulated("demo mode") },
        )
        val result = runner.start(
            WorkflowGoal("Fix it", "some prompt", WorkflowGoalKind.ISSUE_FIX)
        )

        assertFalse(result.isSuccess)
        val implementResult = runner.state.value!!.stepResults.getValue("implement")
        assertEquals(StepRunStatus.FAILED, implementResult.status)
        assertTrue(implementResult.result!!.error!!.contains("SIMULATED"))
    }

    @Test
    fun `failing work step rolls back the written file`() = runBlocking {
        val failingCommands = FakeCommandRunner(CommandResult(1, "", "tests exploded"))
        val runner = buildRunner(commandRunner = failingCommands)
        val goal = customGoal(
            PlanStep("write", PlanStepKind.WRITE_FILE, "Write file",
                target = "out.txt", payload = "hello"),
            PlanStep("test", PlanStepKind.RUN_TESTS, "Run tests", target = "run-tests",
                dependsOn = listOf("write"), maxAttempts = 1),
        )
        val result = runner.start(goal)

        assertFalse(result.isSuccess)
        assertFalse("Failed run must roll back its writes", File(harness.workspaceRoot, "out.txt").exists())
    }

    @Test
    fun `resume continues from saved state without re-running successes`() = runBlocking {
        harness.allowGitPush()
        val gitRunner = FakeGitRunner()
        val goal = fullPlanGoal()
        val plan = WorkflowPlan(goal, goal.customSteps)
        val partial = WorkflowRunState(
            runId = "resume-1",
            plan = plan,
            stepResults = mapOf(
                "write" to StepRunResult("write", StepRunStatus.SUCCEEDED, 1, false,
                    OperationResult.success(verificationEvidence = "earlier")),
                "test" to StepRunResult("test", StepRunStatus.SUCCEEDED, 1, false,
                    OperationResult.success(verificationEvidence = "earlier")),
            ),
            phase = WorkflowPhase.EXECUTING,
        )
        assertTrue(harness.checkpointStore.saveRunState(partial))

        val writeSpy = FakeStepExecutor(PlanStepKind.WRITE_FILE) { _, _ ->
            throw AssertionError("write must not re-execute on resume")
        }
        val executors = mapOf(
            PlanStepKind.WRITE_FILE to writeSpy,
            PlanStepKind.RUN_COMMAND to CommandStepExecutor(PlanStepKind.RUN_COMMAND, FakeCommandRunner()),
            PlanStepKind.RUN_TESTS to CommandStepExecutor(PlanStepKind.RUN_TESTS, FakeCommandRunner()),
            PlanStepKind.GIT_COMMIT to GitStepExecutor(PlanStepKind.GIT_COMMIT, gitRunner),
            PlanStepKind.GIT_PUSH to GitStepExecutor(PlanStepKind.GIT_PUSH, gitRunner),
        )
        val runner = buildRunner(executors = executors, gitRunner = gitRunner)
        val result = runner.resume("resume-1")

        assertTrue("Expected success, got: ${result.error}", result.isSuccess)
        assertTrue("write step must not have re-executed", writeSpy.calls.isEmpty())
        assertTrue(gitRunner.invocations.any { it.first() == "commit" })
    }

    @Test
    fun `resume of unknown run fails honestly`() = runBlocking {
        val runner = buildRunner()
        val result = runner.resume("ghost-run")
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("no saved state"))
    }
}
