package com.vayro.app.session

import com.vayro.app.runtime.ProjectMemoryStore
import com.vayro.app.workspace.WorkspaceCheckpointManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var memoryDir: File
    private lateinit var orchestrator: AgentOrchestrator

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("orchestrator_workspace")
        memoryDir = tempFolder.newFolder("orchestrator_memory")
        val memoryStore = ProjectMemoryStore(memoryDir)
        orchestrator = AgentOrchestrator(
            checkpointManager = null,
            memoryStore = memoryStore,
            maxSelfHealingIterations = 3,
        )
    }

    @Test
    fun `selfHealingLoop diagnoses compilation error and suggests fix`() {
        val loop = SelfHealingLoop(maxIterations = 2)
        val errorLog = """
            e: src/Main.kt:15:20 Unresolved reference 'calculateTotal'.
        """.trimIndent()

        val diag = loop.recordFailure(errorLog)
        assertEquals(ErrorType.COMPILATION_ERROR, diag.type)
        assertTrue(diag.affectedFiles.contains("src/Main.kt"))
        assertTrue(loop.canRetry())
    }

    @Test
    fun `executeVerificationLoop succeeds immediately if build passes`() = runBlocking {
        orchestrator.startTask("proj-1", workspaceDir, "Add feature")

        val result = orchestrator.executeVerificationLoop(
            projectId = "proj-1",
            buildAndTest = { Pair(true, "BUILD SUCCESSFUL") },
            applyAutoFix = { true },
        )

        assertTrue(result)
        assertEquals(EngineeringState.COMPLETED, orchestrator.snapshot.value.state)
    }

    @Test
    fun `executeVerificationLoop triggers self healing and recovers on retry`() = runBlocking {
        orchestrator.startTask("proj-1", workspaceDir, "Fix logic")

        var attempt = 0
        val result = orchestrator.executeVerificationLoop(
            projectId = "proj-1",
            buildAndTest = {
                attempt++
                if (attempt == 1) {
                    Pair(false, "e: src/Calc.kt:5 Unresolved reference 'add'")
                } else {
                    Pair(true, "BUILD SUCCESSFUL")
                }
            },
            applyAutoFix = { diagnosis ->
                assertEquals(ErrorType.COMPILATION_ERROR, diagnosis.type)
                true // Successfully applied fix
            },
        )

        assertTrue(result)
        assertEquals(EngineeringState.COMPLETED, orchestrator.snapshot.value.state)
        assertEquals(1, orchestrator.selfHealing.currentIteration)
    }

    @Test
    fun `executeVerificationLoop fails gracefully when exceeding max retries`() = runBlocking {
        orchestrator.startTask("proj-1", workspaceDir, "Complex refactor")

        val result = orchestrator.executeVerificationLoop(
            projectId = "proj-1",
            buildAndTest = { Pair(false, "Tests failed with assertion error") },
            applyAutoFix = { true },
        )

        assertFalse(result)
        assertEquals(EngineeringState.FAILED, orchestrator.snapshot.value.state)
        assertEquals(3, orchestrator.selfHealing.currentIteration)
    }

    @Test
    fun `manual state transitions update snapshot history properly`() {
        orchestrator.startTask("proj-1", workspaceDir, "Full feature implementation")
        assertEquals(EngineeringState.ANALYZING, orchestrator.snapshot.value.state)

        orchestrator.transition(EngineeringState.PLANNING, "Formulating step-by-step roadmap")
        assertEquals(EngineeringState.PLANNING, orchestrator.snapshot.value.state)

        orchestrator.transition(EngineeringState.IMPLEMENTING, "Writing code")
        assertEquals(EngineeringState.IMPLEMENTING, orchestrator.snapshot.value.state)

        orchestrator.transition(EngineeringState.BUILDING, "Building APK")
        assertEquals(EngineeringState.BUILDING, orchestrator.snapshot.value.state)

        orchestrator.transition(EngineeringState.TESTING, "Executing unit tests")
        assertEquals(EngineeringState.TESTING, orchestrator.snapshot.value.state)

        orchestrator.transition(EngineeringState.REVIEWING, "Preparing diff summary")
        assertEquals(EngineeringState.REVIEWING, orchestrator.snapshot.value.state)

        orchestrator.transition(EngineeringState.COMPLETED, "Completed successfully")
        assertEquals(EngineeringState.COMPLETED, orchestrator.snapshot.value.state)
        assertEquals(7, orchestrator.snapshot.value.history.size)
    }

    @Test
    fun `rollbackToBaseline restores workspace to original baseline state`() {
        val checkpointDir = tempFolder.newFolder("checkpoints")
        val cpManager = WorkspaceCheckpointManager(checkpointDir)
        val orchestratorWithCp = AgentOrchestrator(
            checkpointManager = cpManager,
            memoryStore = null,
            maxSelfHealingIterations = 3,
        )

        val codeFile = File(workspaceDir, "App.kt")
        codeFile.writeText("original code")

        orchestratorWithCp.startTask("proj-test", workspaceDir, "Risky refactor")

        // Modify file
        codeFile.writeText("broken code from bad agent action")
        assertEquals("broken code from bad agent action", codeFile.readText())

        // Rollback
        val rolledBack = orchestratorWithCp.rollbackToBaseline(workspaceDir)
        assertTrue(rolledBack)
        assertEquals(EngineeringState.ROLLED_BACK, orchestratorWithCp.snapshot.value.state)
        assertEquals("original code", codeFile.readText())
    }

    @Test
    fun `unanswered approval times out instead of hanging forever`() = runBlocking {
        orchestrator.startTask("proj-1", workspaceDir, "Add feature")
        orchestrator.requestApproval("Approve?", "A step needs approval")

        assertEquals(EngineeringState.WAITING_FOR_APPROVAL, orchestrator.snapshot.value.state)
        val decided = orchestrator.awaitApprovalDecision(timeoutMs = 200)

        assertFalse(decided)
        assertEquals(EngineeringState.FAILED, orchestrator.snapshot.value.state)
        assertTrue(
            orchestrator.snapshot.value.failureSummary.orEmpty().contains("timed out", ignoreCase = true)
        )
    }

    @Test
    fun `granted approval before the timeout proceeds`() = runBlocking {
        orchestrator.startTask("proj-1", workspaceDir, "Add feature")
        orchestrator.requestApproval("Approve?", "A step needs approval")
        orchestrator.approveCurrentStep()

        assertTrue(orchestrator.awaitApprovalDecision(timeoutMs = 5_000))
    }
}
