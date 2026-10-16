package com.vayro.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionModeTest {

    @Test
    fun testExecutionModeFromStored() {
        assertEquals(ExecutionMode.PLAN, ExecutionMode.fromStored("PLAN"))
        assertEquals(ExecutionMode.PLAN, ExecutionMode.fromStored("plan"))
        assertEquals(ExecutionMode.BUILD, ExecutionMode.fromStored("BUILD"))
        assertEquals(ExecutionMode.BUILD, ExecutionMode.fromStored("build"))
        assertEquals(ExecutionMode.UNIFIED, ExecutionMode.fromStored("UNIFIED"))
        assertEquals(ExecutionMode.UNIFIED, ExecutionMode.fromStored("unified"))
        // Fallback
        assertEquals(ExecutionMode.UNIFIED, ExecutionMode.fromStored(null))
        assertEquals(ExecutionMode.UNIFIED, ExecutionMode.fromStored("invalid_mode"))
    }

    @Test
    fun testActiveRoadmapApproval() {
        val roadmap = ActiveRoadmap(
            title = "Sample Plan",
            summary = "Sample summary",
            steps = listOf(
                RoadmapStep(title = "Step 1", status = StepStatus.PENDING),
                RoadmapStep(title = "Step 2", status = StepStatus.COMPLETED),
            ),
            comments = listOf(
                RoadmapComment(author = "User", text = "Please do step 1 first"),
            ),
            mode = ExecutionMode.UNIFIED,
            isApproved = false,
        )

        assertFalse(roadmap.isApproved)
        assertEquals(2, roadmap.steps.size)
        assertEquals(1, roadmap.comments.size)

        val approved = roadmap.copy(isApproved = true)
        assertTrue(approved.isApproved)
        assertEquals("Sample Plan", approved.title)
    }

    @Test
    fun testScratchpadItemStatus() {
        val runningItem = ScratchpadItem(
            title = "Compiling app",
            status = TaskStatus.RUNNING,
            detail = ":app:compileDebugKotlin",
        )
        assertEquals(TaskStatus.RUNNING, runningItem.status)
        assertEquals("Compiling app", runningItem.title)

        val finishedItem = runningItem.copy(status = TaskStatus.FINISHED)
        assertEquals(TaskStatus.FINISHED, finishedItem.status)
    }
}
