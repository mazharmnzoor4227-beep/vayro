package com.vayro.app.terminal

import com.vayro.app.ui.TerminalOutputLine
import com.vayro.app.ui.TerminalSessionTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSearchTest {

    private fun filterTerminalBuffer(lines: List<TerminalOutputLine>, query: String): List<TerminalOutputLine> {
        if (query.isBlank()) return lines
        return lines.filter { line ->
            line.command.contains(query, ignoreCase = true) ||
            line.output.contains(query, ignoreCase = true)
        }
    }

    @Test
    fun `buffer search returns all lines when query is blank`() {
        val lines = listOf(
            TerminalOutputLine(command = "ls -la", output = "total 12\ndrwxr-xr-x 2 root root 4096"),
            TerminalOutputLine(command = "git status", output = "On branch main\nnothing to commit")
        )

        val result = filterTerminalBuffer(lines, "")
        assertEquals(2, result.size)
    }

    @Test
    fun `buffer search matches case-insensitively across commands and output`() {
        val lines = listOf(
            TerminalOutputLine(command = "git status", output = "On branch main\nnothing to commit"),
            TerminalOutputLine(command = "npm run build", output = "BUILD SUCCESSFUL in 12s"),
            TerminalOutputLine(command = "python script.py", output = "Process finished with exit code 0")
        )

        val gitMatches = filterTerminalBuffer(lines, "GIT")
        assertEquals(1, gitMatches.size)
        assertEquals("git status", gitMatches[0].command)

        val successMatches = filterTerminalBuffer(lines, "success")
        assertEquals(1, successMatches.size)
        assertEquals("npm run build", successMatches[0].command)

        val nonMatches = filterTerminalBuffer(lines, "docker")
        assertEquals(0, nonMatches.size)
    }

    @Test
    fun `terminal session controller preserves independent buffers for separate tabs`() {
        val controller = TerminalSessionController()
        val initialSessions = listOf(
            TerminalSessionTab(id = "tab-1", title = "Terminal 1", cwd = "/workspace")
        )

        // 1. Create a second tab
        val (sessionsAfterCreate, newTab) = controller.createSession(initialSessions, defaultCwd = "/workspace")
        assertEquals(2, sessionsAfterCreate.size)
        val tab2Id = newTab.id

        // 2. Add output to tab 1 and switch to tab 2
        val tab1Lines = listOf(
            TerminalOutputLine(command = "cd backend", output = "")
        )

        val (sessionsAfterSwitch, selectedTab2) = controller.selectSession(
            sessions = sessionsAfterCreate,
            currentActiveId = "tab-1",
            currentLines = tab1Lines,
            currentCwd = "/workspace/backend",
            currentRunning = false,
            targetSessionId = tab2Id
        )

        assertEquals(tab2Id, selectedTab2?.id)
        val savedTab1 = sessionsAfterSwitch.first { it.id == "tab-1" }
        assertEquals(1, savedTab1.lines.size)
        assertEquals("cd backend", savedTab1.lines[0].command)

        // 3. Close tab 2 and verify fallback to tab 1
        val (remainingSessions, activeAfterClose) = controller.closeSession(
            sessions = sessionsAfterSwitch,
            currentActiveId = tab2Id,
            targetSessionId = tab2Id
        )

        assertEquals(1, remainingSessions.size)
        assertEquals("tab-1", activeAfterClose?.id)
    }
}
