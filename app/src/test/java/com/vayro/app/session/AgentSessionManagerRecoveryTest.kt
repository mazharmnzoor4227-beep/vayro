package com.vayro.app.session

import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.RoadmapStep
import com.vayro.app.model.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentSessionManagerRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `records session start and persists active session file`() {
        val manager = AgentSessionManager(tempFolder.newFolder("sessions_1"))

        manager.recordSessionStart(
            sessionId = "session-101",
            projectId = "proj-abc",
            projectTitle = "My Mobile App",
            chatId = "chat-1",
            prompt = "Add user authentication flow",
        )

        // Read through interrupted session check
        val detected = manager.detectInterruptedSessions().firstOrNull()
        assertNotNull(detected)
        assertEquals("session-101", detected?.sessionId)
        assertEquals("proj-abc", detected?.projectId)
        assertEquals("My Mobile App", detected?.projectTitle)
        assertEquals(AgentSessionStatus.INTERRUPTED, detected?.status)
    }

    @Test
    fun `updates progress with roadmap and summary atomically`() {
        val manager = AgentSessionManager(tempFolder.newFolder("sessions_2"))

        manager.recordSessionStart(
            sessionId = "session-102",
            projectId = "proj-abc",
            projectTitle = "My Mobile App",
            chatId = "chat-1",
            prompt = "Build dashboard",
        )

        val roadmap = ActiveRoadmap(
            id = "rm-1",
            title = "Dashboard Roadmap",
            summary = "2 steps",
            steps = listOf(
                RoadmapStep("s1", "UI Layout", "Create dashboard layout", emptyList(), StepStatus.COMPLETED),
                RoadmapStep("s2", "Data Binding", "Connect ViewModel", emptyList(), StepStatus.IN_PROGRESS),
            ),
        )

        manager.updateSessionProgress("session-102", roadmap, summary = "Step 1 completed")

        val interrupted = manager.detectInterruptedSessions().firstOrNull()
        assertNotNull(interrupted)
        assertEquals("Step 1 completed", interrupted?.summary)
        assertEquals(2, interrupted?.activeRoadmap?.steps?.size)
        assertEquals(StepStatus.COMPLETED, interrupted?.activeRoadmap?.steps?.get(0)?.status)
    }

    @Test
    fun `completing session archives to history and deletes active session file`() {
        val manager = AgentSessionManager(tempFolder.newFolder("sessions_3"))

        manager.recordSessionStart(
            sessionId = "session-103",
            projectId = "proj-abc",
            projectTitle = "My Mobile App",
            chatId = "chat-1",
            prompt = "Fix crash on startup",
        )

        manager.recordSessionCompleted("session-103", summary = "Crash fixed successfully")

        // Active session should be deleted
        assertTrue(manager.checkInterruptedSessions().isEmpty())

        // Historical archive should contain the completed session
        val history = manager.listSessionHistory()
        assertEquals(1, history.size)
        assertEquals("session-103", history[0].sessionId)
        assertEquals(AgentSessionStatus.COMPLETED, history[0].status)
        assertEquals("Crash fixed successfully", history[0].summary)
    }

    @Test
    fun `cancelling session records interruption in history and clears active session`() {
        val manager = AgentSessionManager(tempFolder.newFolder("sessions_4"))

        manager.recordSessionStart(
            sessionId = "session-104",
            projectId = "proj-xyz",
            projectTitle = "E-Commerce",
            chatId = "chat-2",
            prompt = "Refactor payment module",
        )

        manager.recordSessionCancelled("session-104")

        assertTrue(manager.checkInterruptedSessions().isEmpty())
        val history = manager.listSessionHistory()
        assertEquals(1, history.size)
        assertEquals(AgentSessionStatus.INTERRUPTED, history[0].status)
        assertEquals("Cancelled by user", history[0].summary)
    }

    @Test
    fun `timing out session archives failed session with timeout reason`() {
        val manager = AgentSessionManager(tempFolder.newFolder("sessions_5"))

        manager.recordSessionStart(
            sessionId = "session-105",
            projectId = "proj-xyz",
            projectTitle = "E-Commerce",
            chatId = "chat-2",
            prompt = "Long running compile task",
        )

        manager.recordSessionTimeout("session-105", 120_000L) // 2 minutes

        assertTrue(manager.checkInterruptedSessions().isEmpty())
        val history = manager.listSessionHistory()
        assertEquals(1, history.size)
        assertEquals(AgentSessionStatus.FAILED, history[0].status)
        assertTrue(history[0].summary?.contains("2 minutes") == true)
    }
}
