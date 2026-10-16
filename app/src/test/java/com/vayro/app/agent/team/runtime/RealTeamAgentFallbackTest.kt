package com.vayro.app.agent.team.runtime

import com.vayro.app.model.AgentKind
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine resolution for team tasks: a role's wired engine runs when usable,
 * otherwise the user's selected agent, otherwise any usable engine. A
 * signed-in Antigravity session counts as auth (the Antigravity CLI never
 * reads a vault API key).
 */
class RealTeamAgentFallbackTest {

    private val profile = ProviderProfile(kind = ProviderKind.ANTHROPIC)

    @Test
    fun testNotInstalledIsNotReady() {
        val reason = readinessReason(
            kind = AgentKind.CLAUDE_CODE,
            isInstalled = false,
            sessionAuthAvailable = false,
            provider = profile,
            secret = "sk-key",
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("not installed"))
    }

    @Test
    fun testSessionAuthBypassesMissingVaultSecret() {
        // Antigravity with a signed-in (Gemini) session but no vault key:
        // usable. This is the case the old gate rejected.
        val reason = readinessReason(
            kind = AgentKind.ANTIGRAVITY,
            isInstalled = true,
            sessionAuthAvailable = true,
            provider = profile,
            secret = null,
        )
        assertNull(reason)
    }

    @Test
    fun testMissingSecretWithoutSessionIsNotReady() {
        val reason = readinessReason(
            kind = AgentKind.CLAUDE_CODE,
            isInstalled = true,
            sessionAuthAvailable = false,
            provider = profile,
            secret = null,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("API key"))
    }

    @Test
    fun testInstalledWithSecretIsReady() {
        val reason = readinessReason(
            kind = AgentKind.CLAUDE_CODE,
            isInstalled = true,
            sessionAuthAvailable = false,
            provider = profile,
            secret = "sk-key",
        )
        assertNull(reason)
    }

    @Test
    fun testRequestedReadyWins() {
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = AgentKind.ANTIGRAVITY,
            readinessOf = { null },
        )
        assertEquals(AgentResolution.Ready(AgentKind.CLAUDE_CODE), resolution)
    }

    @Test
    fun testPreferredUsedWhenRequestedNotReady() {
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = AgentKind.DEEPSEEK_HARNESS,
            readinessOf = { kind ->
                if (kind == AgentKind.DEEPSEEK_HARNESS) null else "not usable"
            },
        )
        assertEquals(AgentResolution.Ready(AgentKind.DEEPSEEK_HARNESS), resolution)
    }

    @Test
    fun testAntigravityFallbackWhenWiredEngineHasNoKey() {
        // Manager wired to Claude Code, no Claude key saved; Antigravity
        // session signed in: the task must run on Antigravity.
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = null,
            readinessOf = { kind ->
                if (kind == AgentKind.ANTIGRAVITY) null else "not usable"
            },
        )
        assertEquals(AgentResolution.Ready(AgentKind.ANTIGRAVITY), resolution)
    }

    @Test
    fun testUnusableNamesEveryEngineTried() {
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = null,
            readinessOf = { "not usable" },
        )
        assertTrue(resolution is AgentResolution.Unusable)
        val reasons = (resolution as AgentResolution.Unusable).reasons
        assertTrue(reasons.contains("Claude Code"))
        assertTrue(reasons.contains("Antigravity"))
    }

    @Test
    fun testDeepSeekWithClaudeProviderIsNotReady() {
        // DeepSeek Harness rejects the Claude subscription login outright;
        // a saved Claude key does not make it usable.
        val reason = readinessReason(
            kind = AgentKind.DEEPSEEK_HARNESS,
            isInstalled = true,
            sessionAuthAvailable = false,
            provider = ProviderProfile(kind = ProviderKind.CLAUDE),
            secret = "sk-key",
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("not supported by DeepSeek Harness"))
    }

    @Test
    fun testCustomIsNeverAFallbackCandidate() {
        // Custom is chat-only (no file edits, no tools): even when it is the
        // user's selected agent and reports ready, it must never run an
        // engineering task, or tasks would "complete" with zero code written.
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = AgentKind.CUSTOM,
            readinessOf = { kind ->
                if (kind == AgentKind.CUSTOM) null else "not usable"
            },
        )
        assertTrue(resolution is AgentResolution.Unusable)
        assertTrue((resolution as AgentResolution.Unusable).reasons.contains("Claude Code"))
    }

    @Test
    fun testCodexWithSecretIsReady() {
        // Codex is a full engine: installed + an API key makes it usable.
        val reason = readinessReason(
            kind = AgentKind.CODEX,
            isInstalled = true,
            sessionAuthAvailable = false,
            provider = ProviderProfile(kind = ProviderKind.CUSTOM),
            secret = "sk-key",
        )
        assertNull(reason)
    }

    @Test
    fun testCodexFallbackWhenOtherEnginesNotReady() {
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = null,
            readinessOf = { kind ->
                if (kind == AgentKind.CODEX) null else "not usable"
            },
        )
        assertEquals(AgentResolution.Ready(AgentKind.CODEX), resolution)
    }

    @Test
    fun testCustomAsRequestedKindIsNeverUsed() {
        // Even when CUSTOM is the requested engine and reports ready, it is
        // filtered out: the task falls back to a real engine, or fails when
        // no real engine is usable.
        val fallback = resolveEffectiveKind(
            requested = AgentKind.CUSTOM,
            preferred = null,
            readinessOf = { kind ->
                if (kind == AgentKind.ANTIGRAVITY) null else "not usable"
            },
        )
        assertEquals(AgentResolution.Ready(AgentKind.ANTIGRAVITY), fallback)

        val unusable = resolveEffectiveKind(
            requested = AgentKind.CUSTOM,
            preferred = null,
            readinessOf = { kind ->
                if (kind == AgentKind.CUSTOM) null else "not usable"
            },
        )
        assertTrue(unusable is AgentResolution.Unusable)
    }
}
