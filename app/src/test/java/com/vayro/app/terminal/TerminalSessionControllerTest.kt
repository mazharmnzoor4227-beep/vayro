package com.vayro.app.terminal

import com.vayro.app.ui.TerminalOutputLine
import com.vayro.app.ui.TerminalSessionTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionControllerTest {

    private val controller = TerminalSessionController()

    @Test
    fun `createSession appends new tab with unique id and title`() {
        val initial = listOf(
            TerminalSessionTab(id = "term-1", title = "Terminal 1", cwd = "/workspace")
        )

        val (updated, newTab) = controller.createSession(initial, defaultCwd = "/workspace/project")
        assertEquals(2, updated.size)
        assertEquals("Terminal 2", newTab.title)
        assertEquals("/workspace/project", newTab.cwd)
        assertTrue(newTab.id.startsWith("term-"))
    }

    @Test
    fun `selectSession updates active session lines and returns target session`() {
        val session1 = TerminalSessionTab(id = "term-1", title = "Terminal 1", cwd = "/workspace")
        val session2 = TerminalSessionTab(id = "term-2", title = "Terminal 2", cwd = "/workspace/sub")
        val list = listOf(session1, session2)

        val currentLines = listOf(
            TerminalOutputLine(command = "echo hello", output = "hello", exitCode = 0)
        )

        val (updated, target) = controller.selectSession(
            sessions = list,
            currentActiveId = "term-1",
            currentLines = currentLines,
            currentCwd = "/workspace",
            currentRunning = false,
            targetSessionId = "term-2"
        )

        assertNotNull(target)
        assertEquals("term-2", target?.id)
        // Verify session 1 lines were saved
        val savedSession1 = updated.first { it.id == "term-1" }
        assertEquals(1, savedSession1.lines.size)
        assertEquals("hello", savedSession1.lines[0].output)
    }

    @Test
    fun `closeSession removes specified session and activates remaining tab`() {
        val session1 = TerminalSessionTab(id = "term-1", title = "Terminal 1")
        val session2 = TerminalSessionTab(id = "term-2", title = "Terminal 2")
        val list = listOf(session1, session2)

        val (remaining, newActive) = controller.closeSession(
            sessions = list,
            currentActiveId = "term-2",
            targetSessionId = "term-2"
        )

        assertEquals(1, remaining.size)
        assertEquals("term-1", remaining[0].id)
        assertEquals("term-1", newActive?.id)
    }
}
