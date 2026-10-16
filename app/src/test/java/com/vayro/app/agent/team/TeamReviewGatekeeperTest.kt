package com.vayro.app.agent.team

import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskStatus
import com.vayro.app.agent.team.review.TeamReviewGatekeeper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeamReviewGatekeeperTest {

    private lateinit var gatekeeper: TeamReviewGatekeeper

    @Before
    fun setUp() {
        gatekeeper = TeamReviewGatekeeper()
    }

    @Test
    fun testDetectFileConflictsBetweenConcurrentTasks() {
        val task1 = TeamTask(
            id = "t1",
            title = "Backend Auth Endpoint",
            description = "",
            assignedRole = TeamRole.BACKEND_ENGINEER,
            status = TeamTaskStatus.IN_PROGRESS,
            affectedFiles = listOf("core/User.kt", "api/AuthRoutes.kt")
        )

        val task2 = TeamTask(
            id = "t2",
            title = "UI Login Screen",
            description = "",
            assignedRole = TeamRole.UI_ENGINEER,
            status = TeamTaskStatus.READY,
            affectedFiles = listOf("ui/LoginScreen.kt", "core/User.kt")
        )

        val conflictResult = gatekeeper.detectFileConflicts(listOf(task1, task2))
        assertTrue(conflictResult.hasConflict)
        assertEquals(1, conflictResult.conflictingFiles.size)
        assertEquals("core/user.kt", conflictResult.conflictingFiles.first().lowercase())
        assertEquals(1, conflictResult.conflictingTasks.size)
    }

    @Test
    fun testNoConflictWhenFilesAreDisjoint() {
        val task1 = TeamTask(
            id = "t1",
            title = "Database Migration",
            description = "",
            assignedRole = TeamRole.DATABASE_ENGINEER,
            status = TeamTaskStatus.IN_PROGRESS,
            affectedFiles = listOf("db/schema.sql")
        )

        val task2 = TeamTask(
            id = "t2",
            title = "UI Dashboard Screen",
            description = "",
            assignedRole = TeamRole.UI_ENGINEER,
            status = TeamTaskStatus.IN_PROGRESS,
            affectedFiles = listOf("ui/Dashboard.kt")
        )

        val conflictResult = gatekeeper.detectFileConflicts(listOf(task1, task2))
        assertFalse(conflictResult.hasConflict)
        assertTrue(conflictResult.conflictingFiles.isEmpty())
    }

    @Test
    fun testCodeTaskRequiresCodeReviewApproval() {
        val devTask = TeamTask(
            id = "dev-1",
            title = "Implement Feature Logic",
            description = "",
            assignedRole = TeamRole.DEVELOPER,
            status = TeamTaskStatus.UNDER_REVIEW,
            affectedFiles = listOf("feature/Logic.kt")
        )

        // Without any review, cannot complete
        assertFalse(gatekeeper.canCompleteTask(devTask))

        // Submit changes requested review
        gatekeeper.submitReview(
            taskId = "dev-1",
            reviewerRole = TeamRole.CODE_REVIEWER,
            verdict = ReviewVerdict.CHANGES_REQUESTED,
            comments = "Refactor method into smaller helper functions."
        )
        assertFalse(gatekeeper.canCompleteTask(devTask))

        // Submit approved review
        gatekeeper.submitReview(
            taskId = "dev-1",
            reviewerRole = TeamRole.CODE_REVIEWER,
            verdict = ReviewVerdict.APPROVED,
            comments = "Refactoring verified. Clean code."
        )
        assertTrue(gatekeeper.canCompleteTask(devTask))
    }

    @Test
    fun testSecuritySensitiveTaskRequiresSecurityReviewerApproval() {
        val authTask = TeamTask(
            id = "auth-1",
            title = "OAuth Token Refresh Service",
            description = "",
            assignedRole = TeamRole.BACKEND_ENGINEER,
            status = TeamTaskStatus.UNDER_REVIEW,
            affectedFiles = listOf("auth/TokenManager.kt")
        )

        // Give code review approval first
        gatekeeper.submitReview(
            taskId = "auth-1",
            reviewerRole = TeamRole.CODE_REVIEWER,
            verdict = ReviewVerdict.APPROVED,
            comments = "Code quality OK."
        )

        // Still cannot complete because affectedFiles contains 'auth' and 'token'
        assertFalse(gatekeeper.canCompleteTask(authTask))

        // Give security approval
        gatekeeper.submitReview(
            taskId = "auth-1",
            reviewerRole = TeamRole.SECURITY_REVIEWER,
            verdict = ReviewVerdict.APPROVED,
            comments = "Tokens stored in Android Keystore, zero plaintext leakage."
        )

        assertTrue(gatekeeper.canCompleteTask(authTask))
    }

    @Test
    fun testManagerAndQATasksBypassCodeReviewGate() {
        val managerTask = TeamTask(
            id = "m1",
            title = "Plan Sprint",
            description = "",
            assignedRole = TeamRole.MANAGER
        )
        assertTrue(gatekeeper.canCompleteTask(managerTask))

        val qaTask = TeamTask(
            id = "q1",
            title = "Run Unit Tests",
            description = "",
            assignedRole = TeamRole.TESTER
        )
        assertTrue(gatekeeper.canCompleteTask(qaTask))
    }
}
