package com.vayro.app.terminal

import com.vayro.app.ui.TerminalOutputLine
import com.vayro.app.ui.TerminalSessionTab

/**
 * Domain controller for managing multi-session terminal tabs, switching, and closing.
 * Decoupled from MainViewModel to ensure single-responsibility and testability.
 */
class TerminalSessionController {

    fun createSession(
        sessions: List<TerminalSessionTab>,
        defaultCwd: String = "/workspace",
    ): Pair<List<TerminalSessionTab>, TerminalSessionTab> {
        val nextNum = sessions.size + 1
        val newId = "term-${System.currentTimeMillis()}"
        val newSession = TerminalSessionTab(
            id = newId,
            title = "Terminal $nextNum",
            isRunning = false,
            cwd = defaultCwd,
            lines = emptyList(),
        )
        return (sessions + newSession) to newSession
    }

    fun selectSession(
        sessions: List<TerminalSessionTab>,
        currentActiveId: String,
        currentLines: List<TerminalOutputLine>,
        currentCwd: String,
        currentRunning: Boolean,
        targetSessionId: String,
    ): Pair<List<TerminalSessionTab>, TerminalSessionTab?> {
        if (currentActiveId == targetSessionId) {
            val target = sessions.firstOrNull { it.id == targetSessionId }
            return sessions to target
        }
        val updated = sessions.map { session ->
            if (session.id == currentActiveId) {
                session.copy(
                    lines = currentLines,
                    cwd = currentCwd,
                    isRunning = currentRunning,
                )
            } else session
        }
        val target = updated.firstOrNull { it.id == targetSessionId }
        return updated to target
    }

    fun closeSession(
        sessions: List<TerminalSessionTab>,
        currentActiveId: String,
        targetSessionId: String,
    ): Pair<List<TerminalSessionTab>, TerminalSessionTab?> {
        if (sessions.size <= 1) {
            return sessions to null
        }
        val remaining = sessions.filter { it.id != targetSessionId }
        val newActive = if (currentActiveId == targetSessionId) {
            remaining.last()
        } else {
            remaining.firstOrNull { it.id == currentActiveId } ?: remaining.first()
        }
        return remaining to newActive
    }
}
