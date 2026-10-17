package com.vayro.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeModeSelectionTest {

    @Test
    fun freeModeAgentsExcludePaidOnlyAgents() {
        val agents = agentsForPicker(freeModeOnly = true)
        assertTrue(agents.contains(AgentKind.ANTIGRAVITY))
        assertFalse("Codex needs a paid OpenAI key", agents.contains(AgentKind.CODEX))
        assertFalse("Custom endpoints have unknown cost", agents.contains(AgentKind.CUSTOM))
    }

    @Test
    fun pickerWithoutFreeModeListsEveryAgent() {
        assertEquals(AgentKind.entries.toList(), agentsForPicker(freeModeOnly = false))
    }

    @Test
    fun freeModeProvidersAreFreeTierOnly() {
        for (agent in listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS, AgentKind.CUSTOM)) {
            val providers = providersForAgent(agent, freeModeOnly = true)
            assertTrue("Free Mode providers for $agent must not be empty", providers.isNotEmpty())
            assertTrue(
                "Free Mode providers for $agent must all be free-tier",
                providers.all { it in FREE_MODE_PROVIDERS },
            )
            assertFalse(providers.contains(ProviderKind.ANTHROPIC))
            assertFalse(providers.contains(ProviderKind.OPENAI))
            assertFalse(providers.contains(ProviderKind.CLAUDE))
        }
    }

    @Test
    fun providersWithoutFreeModeMatchTheBasePicker() {
        for (agent in AgentKind.entries) {
            assertEquals(providersForAgent(agent), providersForAgent(agent, freeModeOnly = false))
        }
    }

    @Test
    fun antigravityHasNoApiProvidersInEitherMode() {
        assertTrue(providersForAgent(AgentKind.ANTIGRAVITY, freeModeOnly = true).isEmpty())
    }
}
