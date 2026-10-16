package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamRoleConfig
import com.vayro.app.model.AgentKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePromptRendererTest {

    private fun context(role: TeamRole, systemPrompt: String = "") = TeamExecutionContext(
        projectDir = File("/tmp/proj"),
        goal = "Build a todo app",
        role = role,
        roleConfig = TeamRoleConfig(role = role, systemPrompt = systemPrompt),
        agentKind = AgentKind.ANTIGRAVITY,
        dependencyOutputs = mapOf("dep-1" to "API contract done"),
        blackboardSummary = "Prior artifact: sprint plan",
    )

    @Test
    fun testPromptContainsRoleTaskAndGoal() {
        val prompt = RolePromptRenderer.render(context(TeamRole.DEVELOPER), "Implement login", "OAuth flow")
        assertTrue(prompt.contains("Fullstack Developer"))
        assertTrue(prompt.contains("Build a todo app"))
        assertTrue(prompt.contains("Implement login"))
        assertTrue(prompt.contains("OAuth flow"))
    }

    @Test
    fun testPromptIncludesDependencyOutputsAndBlackboard() {
        val prompt = RolePromptRenderer.render(context(TeamRole.DEVELOPER), "T", "D")
        assertTrue(prompt.contains("API contract done"))
        assertTrue(prompt.contains("Prior artifact: sprint plan"))
    }

    @Test
    fun testPromptUsesCustomSystemPromptWhenProvided() {
        val prompt = RolePromptRenderer.render(
            context(TeamRole.DEVELOPER, systemPrompt = "You are a senior Kotlin dev."),
            "T", "D"
        )
        assertTrue(prompt.contains("You are a senior Kotlin dev."))
    }

    @Test
    fun testReviewerPromptDemandsVerdictMarker() {
        val prompt = RolePromptRenderer.render(context(TeamRole.CODE_REVIEWER), "Review", "D")
        assertTrue(prompt.contains("VERDICT:"))
    }

    @Test
    fun testNonReviewerPromptHasNoVerdictInstruction() {
        val prompt = RolePromptRenderer.render(context(TeamRole.DEVELOPER), "T", "D")
        assertTrue(!prompt.contains("VERDICT:"))
    }

    @Test
    fun testParseVerdictApproved() {
        assertEquals(
            ReviewVerdict.APPROVED,
            RolePromptRenderer.parseVerdict("Some review text.\nVERDICT: APPROVED")
        )
    }

    @Test
    fun testParseVerdictChangesRequested() {
        assertEquals(
            ReviewVerdict.CHANGES_REQUESTED,
            RolePromptRenderer.parseVerdict("Notes here.\nverdict: changes_requested")
        )
    }

    @Test
    fun testParseVerdictRejected() {
        assertEquals(
            ReviewVerdict.REJECTED,
            RolePromptRenderer.parseVerdict("VERDICT: REJECTED")
        )
    }

    @Test
    fun testParseVerdictUsesLastMarker() {
        assertEquals(
            ReviewVerdict.APPROVED,
            RolePromptRenderer.parseVerdict("VERDICT: CHANGES_REQUESTED\nFinal call.\nVERDICT: APPROVED")
        )
    }

    @Test
    fun testParseVerdictNullWhenAbsent() {
        assertNull(RolePromptRenderer.parseVerdict("A review with no marker at all."))
    }

    @Test
    fun testParseVerdictNullOnGarbageMarker() {
        assertNull(RolePromptRenderer.parseVerdict("VERDICT: MAYBE"))
    }
}
