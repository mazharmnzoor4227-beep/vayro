package com.vayro.app.ui

import com.vayro.app.model.ExecutionMode
import com.vayro.app.model.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadmapParserTest {

    @Test
    fun testParseStandardRoadmapWithChecklist() {
        val markdown = """
            Here is the proposed architectural plan for adding new modes:
            
            ### Implementation Roadmap
            This roadmap outlines the changes needed for Plan, Build, and Unified modes.
            
            1. [ ] Create ModeSelectorBar and Scratchpad UI components
               - Files: `app/src/main/java/com/jarves/mh/ui/RoadmapComponents.kt`
            2. [x] Add ExecutionMode and ActiveRoadmap data models
               - Files: `app/src/main/java/com/jarves/mh/model/Models.kt`
            3. [ ] Integrate with MainViewModel and PocketDevApp
               - Details: Wire state, preferences, and approval callbacks
               - Files: `app/src/main/java/com/jarves/mh/ui/MainViewModel.kt`, `app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt`
            
            Please review the roadmap and click 'Approve & Build' when ready.
        """.trimIndent()

        val roadmap = RoadmapParser.parseFromText(markdown, ExecutionMode.UNIFIED)
        assertNotNull(roadmap)
        assertEquals("Implementation Roadmap", roadmap!!.title)
        assertFalse(roadmap.isApproved)
        assertEquals(ExecutionMode.UNIFIED, roadmap.mode)
        assertEquals(3, roadmap.steps.size)

        // Step 1
        val step1 = roadmap.steps[0]
        assertEquals("Create ModeSelectorBar and Scratchpad UI components", step1.title)
        assertEquals(StepStatus.PENDING, step1.status)
        assertEquals(listOf("app/src/main/java/com/jarves/mh/ui/RoadmapComponents.kt"), step1.filesAffected)

        // Step 2
        val step2 = roadmap.steps[1]
        assertEquals("Add ExecutionMode and ActiveRoadmap data models", step2.title)
        assertEquals(StepStatus.COMPLETED, step2.status)
        assertEquals(listOf("app/src/main/java/com/jarves/mh/model/Models.kt"), step2.filesAffected)

        // Step 3
        val step3 = roadmap.steps[2]
        assertEquals("Integrate with MainViewModel and PocketDevApp", step3.title)
        assertEquals(StepStatus.PENDING, step3.status)
        assertTrue(step3.filesAffected.contains("app/src/main/java/com/jarves/mh/ui/MainViewModel.kt"))
        assertTrue(step3.filesAffected.contains("app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt"))
    }

    @Test
    fun testParseNumberedBoldSteps() {
        val markdown = """
            ## Action Plan
            
            1. **Setup Core Models**: Define execution mode and scratchpad models in `Models.kt`.
            2. **Build Scratchpad View**: Compact UI pill and floating expanded status card.
            3. **Testing**: Run test suite to verify end-to-end functionality.
        """.trimIndent()

        val roadmap = RoadmapParser.parseFromText(markdown, ExecutionMode.PLAN)
        assertNotNull(roadmap)
        assertEquals("Action Plan", roadmap!!.title)
        assertEquals(ExecutionMode.PLAN, roadmap.mode)
        assertEquals(3, roadmap.steps.size)
        assertEquals("Setup Core Models", roadmap.steps[0].title)
        assertEquals("Build Scratchpad View", roadmap.steps[1].title)
        assertEquals("Testing", roadmap.steps[2].title)
    }

    @Test
    fun testNonRoadmapReturnsNull() {
        val regularMessage = "Sure, I have looked into the codebase and found no syntax issues."
        val result = RoadmapParser.parseFromText(regularMessage)
        assertNull(result)
    }

    @Test
    fun testAllCompletedStepsMarkRoadmapApproved() {
        val markdown = """
            ### Implementation Roadmap
            All tasks have been executed successfully:
            
            1. [x] Step 1: Create UI models
            2. [x] Step 2: Implement ViewModels
            3. [x] Step 3: Verify and run unit tests
        """.trimIndent()

        val roadmap = RoadmapParser.parseFromText(markdown, ExecutionMode.BUILD)
        assertNotNull(roadmap)
        assertTrue(roadmap!!.isApproved)
        assertEquals(3, roadmap.steps.size)
        assertTrue(roadmap.steps.all { it.status == StepStatus.COMPLETED })
    }

    @Test
    fun testEmptyReturnsNull() {
        assertNull(RoadmapParser.parseFromText(""))
        assertNull(RoadmapParser.parseFromText("   \n\n  "))
    }

    @Test
    fun testIsRoadmapTextComplete() {
        // Partial roadmap text (steps present, closing line not yet streamed):
        // not complete, so no buttons while streaming.
        assertFalse(
            RoadmapParser.isRoadmapTextComplete(
                "1. [ ] Step one: do the thing\n2. [ ] Step two: verify it\n",
            ),
        )
        // Full roadmap text (closing line present): complete, buttons may render
        // together with the text even before the session finishes.
        assertTrue(
            RoadmapParser.isRoadmapTextComplete(
                "1. [ ] Step one: do the thing\n" +
                    "Please review the roadmap, or click Approve & Build to proceed.",
            ),
        )
        assertFalse(RoadmapParser.isRoadmapTextComplete(""))
    }
}
