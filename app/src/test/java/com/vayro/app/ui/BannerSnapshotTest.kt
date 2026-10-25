package com.vayro.app.ui

import com.vayro.app.session.EngineeringState
import com.vayro.app.session.OrchestratorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers bannerSnapshotFor + completionEventKeys: a live working snapshot
 * wins; otherwise an unacknowledged completion event shows (stored outcome
 * preferred over a latched terminal live snapshot); an acknowledged event
 * never falls through to the latched terminal snapshot (the pinning bug).
 */
class BannerSnapshotTest {

    private fun live(state: EngineeringState, goal: String = "live goal") = OrchestratorSnapshot(
        state = state,
        currentIteration = 1,
        maxIterations = 3,
        planSteps = emptyList(),
        currentStepIndex = 0,
        history = emptyList(),
        currentTaskGoal = goal,
    )

    @Test
    fun liveWorkingWinsOverOutcome() {
        val out = TaskOutcome(EngineeringState.COMPLETED, "Task completed successfully", 1L)
        assertEquals("live goal", bannerSnapshotFor(live(EngineeringState.BUILDING), out)?.currentTaskGoal)
    }

    @Test
    fun outcomePreferredOverLatchedTerminalLive() {
        val out = TaskOutcome(EngineeringState.COMPLETED, "Task completed successfully", 100L)
        val snap = bannerSnapshotFor(live(EngineeringState.COMPLETED, "latched"), out, taskFinishedAtMillis = 100L)
        assertEquals("Task completed successfully", snap?.currentTaskGoal)
    }

    @Test
    fun acknowledgedEventNeverFallsThroughToLatchedTerminal() {
        val out = TaskOutcome(EngineeringState.COMPLETED, "Task completed successfully", 100L)
        val liveSnap = live(EngineeringState.COMPLETED, "latched")
        val keys = completionEventKeys(liveSnap, out, 100L)
        assertEquals(null, bannerSnapshotFor(liveSnap, out, taskFinishedAtMillis = 100L, acknowledgedEventKeys = keys))
    }

    @Test
    fun outcomeRendersWhenLiveIsIdle() {
        val out = TaskOutcome(EngineeringState.FAILED, "Task failed", 2L)
        val snap = bannerSnapshotFor(live(EngineeringState.IDLE), out)
        assertEquals(EngineeringState.FAILED, snap?.state)
        assertEquals("Task failed", snap?.currentTaskGoal)
    }

    @Test
    fun nullLiveAndNullOutcomeStaysNull() {
        assertNull(bannerSnapshotFor(null, null))
    }

    @Test
    fun completionEventKeysEmptyWhileWorking() {
        val out = TaskOutcome(EngineeringState.COMPLETED, "Task completed successfully", 1L)
        assertEquals(emptySet<String>(), completionEventKeys(live(EngineeringState.BUILDING), out, 1L))
    }
}
