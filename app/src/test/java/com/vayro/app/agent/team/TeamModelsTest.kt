package com.vayro.app.agent.team

import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamBudget
import com.vayro.app.agent.team.model.TeamMessage
import com.vayro.app.agent.team.model.TeamMessageType
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamRolePermissions
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskPriority
import com.vayro.app.agent.team.model.TeamTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamModelsTest {

    @Test
    fun testTenSpecializedTeamRolesExist() {
        val roles = TeamRole.entries
        assertEquals(10, roles.size)

        val expectedRoles = setOf(
            "MANAGER", "ARCHITECT", "DEVELOPER", "UI_ENGINEER",
            "BACKEND_ENGINEER", "DATABASE_ENGINEER", "TESTER",
            "SECURITY_REVIEWER", "CODE_REVIEWER", "DEVOPS_ENGINEER"
        )
        assertEquals(expectedRoles, roles.map { it.name }.toSet())

        roles.forEach { role ->
            assertTrue(role.title.isNotBlank())
            assertTrue(role.description.isNotBlank())
        }
    }

    @Test
    fun testRolePermissionsMatrix() {
        val devPerms = TeamRolePermissions.forRole(TeamRole.DEVELOPER)
        assertTrue(devPerms.canWriteCode)
        assertTrue(devPerms.canRunTerminal)
        assertFalse(devPerms.canApproveReviews)

        val codeReviewPerms = TeamRolePermissions.forRole(TeamRole.CODE_REVIEWER)
        assertFalse(codeReviewPerms.canWriteCode)
        assertTrue(codeReviewPerms.canApproveReviews)

        val devOpsPerms = TeamRolePermissions.forRole(TeamRole.DEVOPS_ENGINEER)
        assertTrue(devOpsPerms.canWriteCode)
        assertTrue(devOpsPerms.canRunTerminal)
        assertTrue(devOpsPerms.canCommitGit)
        assertTrue(devOpsPerms.canAccessNetwork)
    }

    @Test
    fun testTeamTaskDefaultsAndStatus() {
        val task = TeamTask(
            title = "Build Database Schema",
            description = "Room entities and SQLite DDL",
            assignedRole = TeamRole.DATABASE_ENGINEER,
            priority = TeamTaskPriority.HIGH,
            dependencies = listOf("arch-task-1"),
            affectedFiles = listOf("db/AppDb.kt")
        )

        assertNotNull(task.id)
        assertEquals(TeamTaskStatus.PENDING, task.status)
        assertEquals(TeamTaskPriority.HIGH, task.priority)
        assertEquals(1, task.dependencies.size)
        assertEquals("db/AppDb.kt", task.affectedFiles.first())
        assertEquals(0L, task.tokensUsed)
    }

    @Test
    fun testTeamMessageCreation() {
        val msg = TeamMessage(
            fromRole = TeamRole.MANAGER,
            toRole = TeamRole.ARCHITECT,
            type = TeamMessageType.PROPOSAL,
            content = "Please draft system architecture."
        )

        assertNotNull(msg.id)
        assertEquals(TeamRole.MANAGER, msg.fromRole)
        assertEquals(TeamRole.ARCHITECT, msg.toRole)
        assertEquals(TeamMessageType.PROPOSAL, msg.type)
        assertFalse(msg.read)
    }

    @Test
    fun testTeamArtifactCreation() {
        val artifact = TeamArtifact(
            title = "Architecture Decision Record 001",
            type = ArtifactType.ADR,
            authorRole = TeamRole.ARCHITECT,
            content = "Use Room SQLite for on-device persistence.",
            tags = setOf("architecture", "database")
        )

        assertNotNull(artifact.id)
        assertEquals(1, artifact.version)
        assertEquals(ArtifactType.ADR, artifact.type)
        assertEquals(TeamRole.ARCHITECT, artifact.authorRole)
        assertTrue(artifact.tags.contains("database"))
    }

    @Test
    fun testTeamBudgetDefaults() {
        val budget = TeamBudget(
            tokenQuota = 50_000L,
            totalTokensUsed = 25_000L,
            costPer1kTokens = 0.002,
            totalCostUsd = 0.05
        )

        assertEquals(50_000L, budget.tokenQuota)
        assertEquals(25_000L, budget.totalTokensUsed)
        assertFalse(budget.isQuotaExceeded)
        assertEquals(0.05, budget.totalCostUsd, 0.0001)
    }

    @Test
    fun testReviewVerdictValues() {
        val verdicts = ReviewVerdict.entries
        assertTrue(verdicts.contains(ReviewVerdict.APPROVED))
        assertTrue(verdicts.contains(ReviewVerdict.CHANGES_REQUESTED))
        assertTrue(verdicts.contains(ReviewVerdict.REJECTED))
        assertTrue(verdicts.contains(ReviewVerdict.PENDING))
    }
}
