package com.vayro.app.session

import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.RoadmapStep
import com.vayro.app.model.StepStatus
import com.vayro.app.workspace.WorkspaceCheckpointManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies app restart recovery following simulated process termination (crash, force close, OOM).
 * Tests state restoration across distinct manager instances sharing the persistent storage directory.
 */
class AppRestartRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `simulates process crash and restores session state in fresh manager instance`() {
        val sessionsDir = tempFolder.newFolder("persisted_sessions")

        // Phase 1: Process 1 is running an agent session
        val process1Manager = AgentSessionManager(sessionsDir)
        val roadmap = ActiveRoadmap(
            id = "roadmap-crash-test",
            title = "Refactoring Auth Module",
            summary = "Refactoring Auth Module",
            steps = listOf(
                RoadmapStep(id = "step-1", title = "Extract interface", description = "Separate API", status = StepStatus.COMPLETED),
                RoadmapStep(id = "step-2", title = "Implement repository", description = "Wire Retrofit", status = StepStatus.IN_PROGRESS),
                RoadmapStep(id = "step-3", title = "Write unit tests", description = "100% coverage", status = StepStatus.PENDING),
            ),
        )

        process1Manager.recordSessionStart(
            sessionId = "session-pid-101",
            projectId = "project-alpha",
            projectTitle = "Alpha Mobile Client",
            chatId = "chat-main",
            prompt = "Refactor the auth module with clean architecture",
            roadmap = roadmap,
        )

        process1Manager.updateSessionProgress("session-pid-101", roadmap, summary = "Completed step 1, working on step 2")

        // Phase 2: Process 1 dies abruptly (e.g. SIGKILL, Android OS memory reclamation)
        // No graceful completeSession() or clean teardown is called.

        // Phase 3: App cold restarts. Fresh manager instance created on the same storage directory.
        val process2Manager = AgentSessionManager(sessionsDir)
        val restoredSession = process2Manager.detectInterruptedSessions().firstOrNull()

        assertNotNull("Process 2 must detect the interrupted session from disk", restoredSession)
        assertEquals("session-pid-101", restoredSession?.sessionId)
        assertEquals("project-alpha", restoredSession?.projectId)
        assertEquals("Alpha Mobile Client", restoredSession?.projectTitle)
        assertEquals("chat-main", restoredSession?.chatId)
        assertEquals("Refactor the auth module with clean architecture", restoredSession?.initialPrompt)
        assertEquals(AgentSessionStatus.INTERRUPTED, restoredSession?.status)
        assertEquals("Completed step 1, working on step 2", restoredSession?.summary)

        // Verify roadmap integrity was restored
        val restoredRoadmap = restoredSession?.activeRoadmap
        assertNotNull(restoredRoadmap)
        assertEquals(3, restoredRoadmap?.steps?.size)
        assertEquals("step-1", restoredRoadmap?.steps?.get(0)?.id)
        assertEquals(StepStatus.COMPLETED, restoredRoadmap?.steps?.get(0)?.status)
        assertEquals("step-2", restoredRoadmap?.steps?.get(1)?.id)
        assertEquals(StepStatus.IN_PROGRESS, restoredRoadmap?.steps?.get(1)?.status)
    }

    @Test
    fun `recovers workspace checkpoint alongside agent session on restart`() {
        val rootDir = tempFolder.newFolder("app_storage")
        val sessionsDir = File(rootDir, "sessions")
        val checkpointsDir = File(rootDir, "checkpoints")
        val workspaceDir = tempFolder.newFolder("workspace_alpha")

        // Create sample project files
        val srcFile = File(workspaceDir, "App.kt").apply { writeText("fun main() = println(1)") }

        // Process 1: Create checkpoint and start session
        val checkpointManager1 = WorkspaceCheckpointManager(checkpointsDir)
        val checkpoint = checkpointManager1.createCheckpoint("project-alpha", workspaceDir, "pre-task baseline")
        assertNotNull(checkpoint)

        val sessionManager1 = AgentSessionManager(sessionsDir)
        sessionManager1.recordSessionStart(
            sessionId = "session-ckpt-202",
            projectId = "project-alpha",
            projectTitle = "Alpha Mobile Client",
            chatId = "chat-main",
            prompt = "Modify App.kt",
        )

        // File modified by agent before crash
        srcFile.writeText("fun main() = println(2)")

        // Process dies and restarts
        val checkpointManager2 = WorkspaceCheckpointManager(checkpointsDir)
        val sessionManager2 = AgentSessionManager(sessionsDir)

        val restoredSession = sessionManager2.detectInterruptedSessions().firstOrNull()
        assertNotNull(restoredSession)
        assertEquals("session-ckpt-202", restoredSession?.sessionId)

        // Restore pre-task baseline checkpoint
        val latestCheckpoint = checkpointManager2.listCheckpoints("project-alpha").firstOrNull()
        assertNotNull(latestCheckpoint)
        val rollbackSuccess = checkpointManager2.rollbackToCheckpoint("project-alpha", latestCheckpoint!!.id, workspaceDir)
        assertTrue(rollbackSuccess)
        assertEquals("fun main() = println(1)", srcFile.readText())
    }

    @Test
    fun `handles corrupted session file gracefully on restart without throwing`() {
        val sessionsDir = tempFolder.newFolder("corrupted_sessions")
        val activeFile = File(sessionsDir, "active_session.json")
        activeFile.writeText("{ corrupted_json_truncate... ")

        val manager = AgentSessionManager(sessionsDir)
        val detected = manager.detectInterruptedSessions()
        assertTrue("Corrupted json must fail safely and return empty", detected.isEmpty())
    }

    @Test
    fun `clearing restored session deletes active session file cleanly`() {
        val sessionsDir = tempFolder.newFolder("cleanup_sessions")
        val manager1 = AgentSessionManager(sessionsDir)
        manager1.recordSessionStart(
            sessionId = "session-to-clear",
            projectId = "p1",
            projectTitle = "P1",
            chatId = "c1",
            prompt = "Quick task",
        )

        val manager2 = AgentSessionManager(sessionsDir)
        assertNotNull(manager2.detectInterruptedSessions().firstOrNull())

        manager2.clearActiveSession("session-to-clear")
        assertTrue(manager2.checkInterruptedSessions().isEmpty())
    }
}
