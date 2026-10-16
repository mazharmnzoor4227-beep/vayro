package com.vayro.app.session

import com.vayro.app.runtime.ProjectMemoryStore
import com.vayro.app.workspace.GitCommandResult
import com.vayro.app.workspace.GitDomainController
import com.vayro.app.workspace.WorkspaceCheckpointManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentOrchestratorFullPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var baseDir: File
    private lateinit var memoryStore: ProjectMemoryStore
    private lateinit var checkpointManager: WorkspaceCheckpointManager
    private lateinit var gitController: GitDomainController
    private lateinit var orchestrator: AgentOrchestrator

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("orch_pipeline_ws")
        baseDir = tempFolder.newFolder("orch_pipeline_base")
        memoryStore = ProjectMemoryStore(baseDir)
        checkpointManager = WorkspaceCheckpointManager(baseDir)

        gitController = GitDomainController { args, _, _ ->
            when (args.firstOrNull()) {
                "status" -> 0 to " M src/Auth.kt\n?? src/AuthTest.kt"
                "diff" -> 0 to "2 files changed, 20 insertions(+)"
                "commit" -> 0 to "[main abc1234] commit succeeded"
                else -> 0 to ""
            }
        }

        orchestrator = AgentOrchestrator(
            checkpointManager = checkpointManager,
            memoryStore = memoryStore,
            gitDomainController = gitController,
            maxSelfHealingIterations = 3,
        )
    }

    @Test
    fun `canTransition enforces legitimate state machine pipeline`() {
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.IDLE, EngineeringState.ANALYZING))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.ANALYZING, EngineeringState.PLANNING))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.PLANNING, EngineeringState.IMPLEMENTING))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.IMPLEMENTING, EngineeringState.BUILDING))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.BUILDING, EngineeringState.TESTING))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.TESTING, EngineeringState.ANALYZING_FAILURE))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.ANALYZING_FAILURE, EngineeringState.FIXING))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.FIXING, EngineeringState.RETESTING))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.RETESTING, EngineeringState.REVIEWING))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.REVIEWING, EngineeringState.WAITING_FOR_APPROVAL))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.WAITING_FOR_APPROVAL, EngineeringState.COMPLETED))

        // FAILED and ROLLED_BACK are universally reachable from active work
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.IMPLEMENTING, EngineeringState.FAILED))
        assertTrue(EngineeringStateMachine.canTransition(EngineeringState.FAILED, EngineeringState.ROLLED_BACK))

        // Invalid direct jump
        assertFalse(EngineeringStateMachine.canTransition(EngineeringState.IDLE, EngineeringState.COMPLETED))
    }

    @Test
    fun `executeFullAutonomousWorkflow runs end-to-end and commits verified changes`() = runBlocking {
        var stepsExecuted = 0
        val success = orchestrator.executeFullAutonomousWorkflow(
            projectId = "test-proj",
            workspaceDir = workspaceDir,
            userGoal = "Add JWT token refresh endpoint",
            planSteps = listOf("Create JwtService", "Implement refresh route"),
            approvalPolicy = ApprovalPolicy.AUTO_APPROVE,
            onImplement = { _, _ ->
                stepsExecuted++
                true
            },
            buildAndTest = { Pair(true, "BUILD SUCCESSFUL") },
            applyAutoFix = { true },
            commitMessage = "feat: add JWT refresh endpoint",
        )

        assertTrue(success)
        assertEquals(2, stepsExecuted)
        assertEquals(EngineeringState.COMPLETED, orchestrator.snapshot.value.state)

        // Verify change review was attached
        val review = orchestrator.snapshot.value.changeReview
        assertNotNull(review)
        assertEquals(2, review!!.totalChanges)
        assertTrue(review.modifiedFiles.contains("src/Auth.kt"))
    }

    @Test
    fun `executeFullAutonomousWorkflow recovers through self-healing on build failure`() = runBlocking {
        var attempt = 0
        val success = orchestrator.executeFullAutonomousWorkflow(
            projectId = "test-proj",
            workspaceDir = workspaceDir,
            userGoal = "Fix calculation bug",
            planSteps = listOf("Update tax calculation"),
            approvalPolicy = ApprovalPolicy.AUTO_APPROVE,
            onImplement = { _, _ -> true },
            buildAndTest = {
                attempt++
                if (attempt == 1) {
                    Pair(false, "e: src/Tax.kt:10 Unresolved reference 'rate'")
                } else {
                    Pair(true, "BUILD SUCCESSFUL")
                }
            },
            applyAutoFix = { diag ->
                assertEquals(ErrorType.COMPILATION_ERROR, diag.type)
                true
            },
        )

        assertTrue(success)
        assertEquals(1, orchestrator.selfHealing.currentIteration)
        assertEquals(EngineeringState.COMPLETED, orchestrator.snapshot.value.state)
    }

    @Test
    fun `approval checkpoint pauses workflow until human approval is granted`() = runBlocking {
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            orchestrator.executeFullAutonomousWorkflow(
                projectId = "test-proj",
                workspaceDir = workspaceDir,
                userGoal = "Refactor database migrations",
                planSteps = listOf("Drop old table", "Migrate data"),
                approvalPolicy = ApprovalPolicy.ALWAYS_ASK,
                onImplement = { _, _ -> true },
                buildAndTest = { Pair(true, "BUILD SUCCESSFUL") },
                applyAutoFix = { true },
            )
        }

        // Wait until paused at step 1 approval
        var retries = 0
        while (orchestrator.snapshot.value.pendingApproval == null && retries++ < 100) {
            delay(20)
        }
        assertEquals(EngineeringState.WAITING_FOR_APPROVAL, orchestrator.snapshot.value.state)
        assertEquals("Approve Step 1", orchestrator.snapshot.value.pendingApproval?.title)
        orchestrator.approveCurrentStep()

        // Wait for second step approval
        retries = 0
        while (orchestrator.snapshot.value.pendingApproval == null && retries++ < 100) {
            delay(20)
        }
        assertEquals("Approve Step 2", orchestrator.snapshot.value.pendingApproval?.title)
        orchestrator.approveCurrentStep()

        // Wait for final changes review approval
        retries = 0
        while (orchestrator.snapshot.value.pendingApproval == null && retries++ < 100) {
            delay(20)
        }
        assertEquals("Approve final changes", orchestrator.snapshot.value.pendingApproval?.title)
        orchestrator.approveCurrentStep()

        job.join()
        assertEquals(EngineeringState.COMPLETED, orchestrator.snapshot.value.state)
    }

    @Test
    fun `rejection halts workflow and transitions to FAILED`() = runBlocking {
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            orchestrator.executeFullAutonomousWorkflow(
                projectId = "test-proj",
                workspaceDir = workspaceDir,
                userGoal = "Delete unused endpoints",
                planSteps = listOf("Delete routes"),
                approvalPolicy = ApprovalPolicy.ALWAYS_ASK,
                onImplement = { _, _ -> true },
                buildAndTest = { Pair(true, "BUILD SUCCESSFUL") },
                applyAutoFix = { true },
            )
        }

        var retries = 0
        while (orchestrator.snapshot.value.pendingApproval == null && retries++ < 100) {
            delay(20)
        }

        // User rejects
        orchestrator.rejectCurrentStep("Do not delete legacy v1 routes")
        job.join()

        assertEquals(EngineeringState.FAILED, orchestrator.snapshot.value.state)
        assertTrue(orchestrator.snapshot.value.failureSummary?.contains("Do not delete") == true)
    }

    @Test
    fun `state persistence saves and restores active session across restarts`() {
        orchestrator.startTask("proj-recover", workspaceDir, "Persisted task goal", listOf("Step A", "Step B"))
        orchestrator.transition(EngineeringState.IMPLEMENTING, "Working on Step A")

        val saved = orchestrator.saveState(baseDir, "proj-recover")
        assertTrue(saved)

        val freshOrchestrator = AgentOrchestrator(checkpointManager, memoryStore, gitController)
        val restored = freshOrchestrator.restoreActiveSession("proj-recover", baseDir)
        assertTrue(restored)

        val snap = freshOrchestrator.snapshot.value
        assertEquals(EngineeringState.IMPLEMENTING, snap.state)
        assertEquals("Persisted task goal", snap.currentTaskGoal)
        assertEquals(2, snap.planSteps.size)
        assertEquals(2, snap.history.size)
    }

    @Test
    fun `rollbackToBaseline reverts workspace and transitions state to ROLLED_BACK`() {
        val file = File(workspaceDir, "code.txt").apply { writeText("original") }
        orchestrator.startTask("proj-rollback", workspaceDir, "Destructive task")

        // Modify file
        file.writeText("corrupted modification")

        val reverted = orchestrator.rollbackToBaseline(workspaceDir)
        assertTrue(reverted)
        assertEquals("original", file.readText())
        assertEquals(EngineeringState.ROLLED_BACK, orchestrator.snapshot.value.state)
    }
}
