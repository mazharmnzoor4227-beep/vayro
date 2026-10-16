package com.vayro.app.workflow

import com.vayro.app.core.result.OperationStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M6: checkpoint-before-write (snapshot/rollback) and resumable run state
 * persistence.
 */
class CheckpointStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var store: CheckpointStore

    @Before
    fun setUp() {
        workspace = tempFolder.newFolder("workspace")
        store = CheckpointStore(tempFolder.newFolder("checkpoints"))
    }

    @Test
    fun `snapshot then rollback restores modified file`() {
        val f = File(workspace, "a.txt").apply { writeText("original") }
        store.snapshot("r1", workspace, listOf("a.txt"))
        f.writeText("modified")
        store.rollback("r1", workspace)
        assertEquals("original", f.readText())
    }

    @Test
    fun `rollback deletes a file that did not exist at snapshot time`() {
        store.snapshot("r1", workspace, listOf("new.txt"))
        val f = File(workspace, "new.txt").apply { writeText("created by run") }
        assertTrue(f.exists())
        store.rollback("r1", workspace)
        assertFalse(f.exists())
    }

    @Test
    fun `rollback with no snapshots is a no-op`() {
        store.rollback("missing", workspace) // must not throw
    }

    @Test
    fun `discard removes run data`() {
        File(workspace, "a.txt").apply { writeText("x") }
        store.snapshot("r1", workspace, listOf("a.txt"))
        assertTrue(store.listRunIds().contains("r1"))
        store.discard("r1")
        assertFalse(store.listRunIds().contains("r1"))
    }

    @Test
    fun `save and load round-trips run state`() {
        val plan = WorkflowPlan(
            WorkflowGoal("Goal", "desc", WorkflowGoalKind.ISSUE_FIX),
            listOf(PlanStep("a", PlanStepKind.ANALYZE, "Analyze", target = "a.txt")),
        )
        val state = WorkflowRunState(
            runId = "r9",
            plan = plan,
            stepResults = mapOf(
                "a" to StepRunResult("a", StepRunStatus.SUCCEEDED, 1, false, null)
            ),
            phase = WorkflowPhase.EXECUTING,
            reviewNotes = "looks good",
        )
        assertTrue(store.saveRunState(state))
        val loaded = store.loadRunState("r9")!!
        assertEquals("r9", loaded.runId)
        assertEquals(WorkflowPhase.EXECUTING, loaded.phase)
        assertEquals("looks good", loaded.reviewNotes)
        assertEquals("Goal", loaded.plan.goal.title)
        assertEquals(WorkflowGoalKind.ISSUE_FIX, loaded.plan.goal.kind)
        assertEquals(1, loaded.plan.steps.size)
        assertEquals("a.txt", loaded.plan.steps[0].target)
        assertEquals(StepRunStatus.SUCCEEDED, loaded.stepResults.getValue("a").status)
    }

    @Test
    fun `step result with operation result round-trips`() {
        val plan = WorkflowPlan(
            WorkflowGoal("G", kind = WorkflowGoalKind.CUSTOM),
            listOf(PlanStep("t", PlanStepKind.RUN_TESTS, "Tests", target = "test", maxAttempts = 3)),
        )
        val op = com.vayro.app.core.result.OperationResult.failure("boom", exitCode = 1, logs = listOf("l1"))
        val state = WorkflowRunState(
            "r8", plan,
            mapOf("t" to StepRunResult("t", StepRunStatus.FAILED, 3, true, op)),
            WorkflowPhase.FAILED,
        )
        store.saveRunState(state)
        val loaded = store.loadRunState("r8")!!
        val r = loaded.stepResults.getValue("t")
        assertEquals(3, r.attempts)
        assertTrue(r.repaired)
        assertEquals(OperationStatus.FAILED, r.result!!.status)
        assertEquals("boom", r.result!!.error)
        assertEquals(1, r.result!!.exitCode)
        assertEquals(listOf("l1"), r.result!!.logs)
        assertEquals(3, loaded.plan.steps[0].maxAttempts)
    }

    @Test
    fun `corrupt state file loads as null`() {
        val dir = tempFolder.newFolder("manual")
        val manual = CheckpointStore(dir)
        val runDir = File(dir, "bad").apply { mkdirs() }
        File(runDir, "state.json").writeText("{ not valid json")
        assertNull(manual.loadRunState("bad"))
    }

    @Test
    fun `missing run loads as null`() {
        assertNull(store.loadRunState("nope"))
    }
}
