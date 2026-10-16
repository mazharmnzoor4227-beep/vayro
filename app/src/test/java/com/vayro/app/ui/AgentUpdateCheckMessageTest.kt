package com.vayro.app.ui

import com.vayro.app.model.AgentKind
import com.vayro.app.runtime.AgentUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA Medium: an agent update check used to swallow per-agent fetch
 * failures, so offline looked exactly like "all installed agents are
 * up to date". A failed check must never report "up to date".
 */
class AgentUpdateCheckMessageTest {

    @Test
    fun `no updates and no failures reports up to date`() {
        assertEquals(
            "All installed agents are up to date",
            agentUpdatesCheckMessage(emptyMap(), emptyList())
        )
    }

    @Test
    fun `no updates with a failed check reports could not check`() {
        val message = agentUpdatesCheckMessage(emptyMap(), listOf(AgentKind.CLAUDE_CODE))
        assertTrue(message.startsWith("Could not check for agent updates"))
        assertTrue(message.contains("Claude Code"))
    }

    @Test
    fun `updates with a partial failure reports both`() {
        val message = agentUpdatesCheckMessage(
            mapOf(AgentKind.CODEX to AgentUpdateInfo("1.0", "1.1")),
            listOf(AgentKind.ANTIGRAVITY)
        )
        assertTrue(message.contains("1 agent update available"))
        assertTrue(message.contains("Could not check Antigravity CLI"))
    }
}
