package com.vayro.app.agent.team

import com.vayro.app.agent.team.budget.TeamBudgetTracker
import com.vayro.app.agent.team.model.TeamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeamBudgetTrackerTest {

    private lateinit var tracker: TeamBudgetTracker

    @Before
    fun setUp() {
        tracker = TeamBudgetTracker(
            initialTokenQuota = 10_000L,
            initialMaxExecutionTimeMs = 60_000L,
            costPer1kTokens = 0.002
        )
    }

    @Test
    fun testRecordUsageAndCostCalculation() {
        tracker.recordUsage(TeamRole.DEVELOPER, tokens = 2000L, durationMs = 1500L)
        tracker.recordUsage(TeamRole.TESTER, tokens = 1000L, durationMs = 500L)

        val budget = tracker.getBudgetSummary()
        assertEquals(3000L, budget.totalTokensUsed)
        assertEquals(2000L, budget.elapsedTimeMs)
        assertFalse(budget.isQuotaExceeded)
        // Cost: 3000 / 1000 * 0.002 = 0.006 USD
        assertEquals(0.006, budget.totalCostUsd, 0.0001)
    }

    @Test
    fun testQuotaExceededDetection() {
        assertFalse(tracker.isQuotaExceeded())

        tracker.recordUsage(TeamRole.ARCHITECT, tokens = 9999L)
        assertFalse(tracker.isQuotaExceeded())

        tracker.recordUsage(TeamRole.MANAGER, tokens = 1L)
        assertTrue(tracker.isQuotaExceeded())

        val summary = tracker.getBudgetSummary()
        assertTrue(summary.isQuotaExceeded)
    }

    @Test
    fun testRoleLevelUsageTracking() {
        tracker.recordUsage(TeamRole.BACKEND_ENGINEER, tokens = 4000L)
        tracker.recordTaskCompleted(TeamRole.BACKEND_ENGINEER)

        val backendUsage = tracker.getRoleUsage(TeamRole.BACKEND_ENGINEER)
        assertEquals(4000L, backendUsage.tokensUsed)
        assertEquals(1, backendUsage.tasksCompleted)
        assertEquals(0.008, backendUsage.costUsd, 0.0001)

        val allUsages = tracker.getAllRoleUsages()
        assertEquals(10, allUsages.size)
    }

    @Test
    fun testReset() {
        tracker.recordUsage(TeamRole.DEVELOPER, tokens = 5000L)
        assertEquals(5000L, tracker.getBudgetSummary().totalTokensUsed)

        tracker.reset()
        assertEquals(0L, tracker.getBudgetSummary().totalTokensUsed)
        assertFalse(tracker.isQuotaExceeded())
        assertEquals(0L, tracker.getRoleUsage(TeamRole.DEVELOPER).tokensUsed)
    }
}
