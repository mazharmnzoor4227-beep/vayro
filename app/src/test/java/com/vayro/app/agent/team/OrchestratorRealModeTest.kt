package com.vayro.app.agent.team

import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamTaskStatus
import com.vayro.app.agent.team.runtime.SecurityScanResult
import com.vayro.app.agent.team.runtime.TeamAgentExecutor
import com.vayro.app.agent.team.runtime.TeamExecutionResult
import com.vayro.app.agent.team.runtime.TeamRoleValidator
import com.vayro.app.agent.team.runtime.TestSuiteResult
import com.vayro.app.core.result.OperationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrchestratorRealModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun approvingValidator() = TeamRoleValidator(
        testSuiteRunner = { TestSuiteResult(ran = true, passed = 10, failed = 0, skipped = 0, note = "exit=0") },
        securityScanner = { SecurityScanResult(filesScanned = 50) },
    )

    private fun fakeExecutor(): TeamAgentExecutor = TeamAgentExecutor { task, context ->
        // Faithfully simulates PromptBasedTeamAgentExecutor: the VERDICT marker
        // in reviewer output becomes the structured reviewVerdict.
        val verdict = if (context.role == TeamRole.CODE_REVIEWER) ReviewVerdict.APPROVED else null
        TeamExecutionResult(
            result = OperationResult.success(verificationEvidence = "agent completed"),
            outputText = "Real output for ${task.title}." + (verdict?.let { "\nVERDICT: $it" } ?: ""),
            artifacts = listOf(
                TeamArtifact(
                    title = "${context.role.title}: ${task.title}",
                    type = ArtifactType.SOURCE_PATCH,
                    authorRole = context.role,
                    content = "Real output for ${task.title}.",
                    metadata = mapOf("taskId" to task.id),
                    isSimulated = false,
                )
            ),
            reviewVerdict = verdict,
        )
    }

    @Test
    fun testRealModeWithoutExecutorFailsLoudly() {
        val orchestrator = AgentTeamOrchestrator()
        try {
            orchestrator.requireRealExecution()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("TeamAgentExecutor"))
        }
    }

    @Test
    fun testRealModeWithExecutorPassesGuard() {
        val orchestrator = AgentTeamOrchestrator(teamExecutor = fakeExecutor())
        orchestrator.requireRealExecution() // must not throw
    }

    @Test
    fun testExecuteWithoutExecutorRefuses() = runBlocking {
        // No executor wired: execution refuses loudly instead of simulating.
        val orchestrator = AgentTeamOrchestrator()
        orchestrator.planProject("goal")
        try {
            orchestrator.executeNextReadyTask()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("TeamAgentExecutor"))
        }
    }

    @Test
    fun testExecuteNextReadyTaskRunsGenuinely() = runBlocking {
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = fakeExecutor(),
            teamValidator = approvingValidator(),
            projectDir = tempFolder.root,
        )
        orchestrator.planProject("Build a notes app")
        val executed = orchestrator.executeNextReadyTask()
        assertNotNull(executed)
        val stored = orchestrator.scheduler.getTask(executed!!.id)!!
        assertEquals(TeamTaskStatus.COMPLETED, stored.status)
        assertFalse(stored.isSimulated)
        assertTrue(stored.output.contains("Real output"))
        // Genuine artifact on the blackboard, never simulated.
        val artifacts = orchestrator.blackboard.getAllArtifacts()
            .filter { it.metadata["taskId"] == executed.id }
        assertEquals(1, artifacts.size)
        assertFalse(artifacts[0].isSimulated)
    }

    @Test
    fun testAutoRunAllRealCompletesFullDagWithRealReviews() = runBlocking {
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = fakeExecutor(),
            teamValidator = approvingValidator(),
            projectDir = tempFolder.root,
            maxConcurrentAgents = 3,
        )
        orchestrator.planProject("Build a notes app")
        val report = orchestrator.autoRunAllReal()
        assertEquals(10, report.succeeded)
        assertEquals(0, report.failed)
        assertTrue(report.isSuccess)
        // Real reviews were recorded for the reviewer roles.
        val reviews = orchestrator.gatekeeper.getAllReviews()
        val reviewerRoles = reviews.map { it.reviewerRole }.toSet()
        assertTrue(reviewerRoles.contains(TeamRole.TESTER))
        assertTrue(reviewerRoles.contains(TeamRole.SECURITY_REVIEWER))
        assertTrue(reviewerRoles.contains(TeamRole.CODE_REVIEWER))
        assertTrue(reviews.none { it.isSimulated })
        assertTrue(reviews.all { it.verdict == ReviewVerdict.APPROVED })
        // Session state reflects completion.
        assertFalse(orchestrator.sessionState.value.isRunning)
    }

    @Test
    fun testRealFailureMarksTaskFailedNotCompleted() = runBlocking {
        val failing = TeamAgentExecutor { task, _ ->
            TeamExecutionResult(
                result = OperationResult.failure(error = "agent exploded"),
                outputText = "no output",
            )
        }
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = failing,
            teamValidator = approvingValidator(),
        )
        orchestrator.planProject("goal")
        val executed = orchestrator.executeNextReadyTask()
        assertNotNull(executed)
        assertEquals(TeamTaskStatus.FAILED, orchestrator.scheduler.getTask(executed!!.id)!!.status)
    }

    @Test
    fun testFailedTaskSurfacesLastErrorAndStopsClaimingRunning() = runBlocking {
        // Regression: a failed first task used to leave the UI claiming
        // "Executing" with Step as a dead button and no visible cause.
        // The real reason (here: runtime missing, carried in result.error
        // with blank output) must reach sessionState.lastError, and the
        // stalled run must stop claiming to be running.
        val failing = TeamAgentExecutor { _, _ ->
            TeamExecutionResult(
                result = OperationResult.notConfigured("The 'Claude Code' runtime is not installed."),
                outputText = "",
            )
        }
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = failing,
            teamValidator = approvingValidator(),
        )
        orchestrator.planProject("goal")
        val executed = orchestrator.executeNextReadyTask()
        assertNotNull(executed)
        assertEquals(TeamTaskStatus.FAILED, orchestrator.scheduler.getTask(executed!!.id)!!.status)
        val state = orchestrator.sessionState.value
        assertNotNull(state.lastError)
        assertTrue(state.lastError!!.contains("Claude Code"))
        assertFalse(state.isRunning)
    }

    @Test
    fun testRetryFailedTasksRequeuesAfterStall() = runBlocking {
        val failing = TeamAgentExecutor { _, _ ->
            TeamExecutionResult(
                result = OperationResult.failure(error = "agent exploded"),
                outputText = "",
            )
        }
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = failing,
            teamValidator = approvingValidator(),
        )
        orchestrator.planProject("goal")
        orchestrator.executeNextReadyTask()
        assertTrue(orchestrator.scheduler.isStalled())

        orchestrator.retryFailedTasks()
        val state = orchestrator.sessionState.value
        assertNull(state.lastError)
        assertTrue(state.isRunning)
        val failed = orchestrator.scheduler.getTasks().filter { it.status == TeamTaskStatus.FAILED }
        assertTrue(failed.isEmpty())
        assertTrue(state.tasks.any { it.status == TeamTaskStatus.READY })
    }

    @Test
    fun testPlanProjectClearsLastError() = runBlocking {
        val failing = TeamAgentExecutor { _, _ ->
            TeamExecutionResult(
                result = OperationResult.failure(error = "agent exploded"),
                outputText = "",
            )
        }
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = failing,
            teamValidator = approvingValidator(),
        )
        orchestrator.planProject("goal")
        orchestrator.executeNextReadyTask()
        assertNotNull(orchestrator.sessionState.value.lastError)

        orchestrator.planProject("fresh goal")
        assertNull(orchestrator.sessionState.value.lastError)
    }
}
