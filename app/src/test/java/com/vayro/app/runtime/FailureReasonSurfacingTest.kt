package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v3.2.49 — the chat surfacing decision for failed sessions, generalized
 * from the v3.2.47 DeepSeek-only path to every bridge. Device-proven gap
 * being closed: an Antigravity turn ended "Task stopped" after 54 s and
 * its eligibility reason existed only inside the Activity sheet.
 */
class FailureReasonSurfacingTest {

    private val eligibility =
        "Eligibility check failed: Your current account is not eligible for Antigravity. " +
            "Verify your account to continue using Antigravity."

    @Test
    fun `substantive reason surfaces verbatim as exactly one message`() {
        assertEquals(eligibility, FailureReasonSurfacing.messageToSurface(eligibility, emptyList()))
    }

    @Test
    fun `blank or whitespace reason surfaces nothing`() {
        assertNull(FailureReasonSurfacing.messageToSurface("", emptyList()))
        assertNull(FailureReasonSurfacing.messageToSurface("   \n ", emptyList()))
    }

    @Test
    fun `reason already shown verbatim surfaces nothing`() {
        assertNull(FailureReasonSurfacing.messageToSurface(eligibility, listOf("Some reply", eligibility)))
    }

    @Test
    fun `reason contained in a longer shown message surfaces nothing`() {
        val shown = "I could not finish: $eligibility"
        assertNull(FailureReasonSurfacing.messageToSurface(eligibility, listOf(shown)))
    }

    @Test
    fun `user initiated stop surfaces nothing`() {
        assertNull(FailureReasonSurfacing.messageToSurface("Stopped by user", emptyList()))
        assertNull(FailureReasonSurfacing.messageToSurface("Session stopped by user request", emptyList()))
    }

    @Test
    fun `dsh repair failure still surfaces (v3 2 47 case preserved)`() {
        val repair = DshCompatibility.repairFailedMessage("0.2.1-rc.2", "0.1.2-rc.1")
        assertEquals(repair, FailureReasonSurfacing.messageToSurface(repair, emptyList()))
    }

    @Test
    fun `short generic reason inside unrelated text still surfaces`() {
        // Below the distinctiveness floor, containment must not suppress:
        // a 10-char reason appearing inside a longer message is coincidence.
        assertEquals("Timed out", FailureReasonSurfacing.messageToSurface("Timed out", listOf("The request Timed out twice")))
    }

    @Test
    fun `surfaced eligibility text cannot be reclassified as transient or key failure`() {
        // The surfacing point is terminal (after all retry attempts have
        // declined) and the text is never re-fed as an event reason; this
        // pins that even a hypothetical re-classification could not turn
        // the eligibility stop into a network-retry or key-rotation loop.
        assertFalse(RuntimeFailureClassifier.isTransientNetworkFailure(eligibility))
        val lower = eligibility.lowercase()
        assertFalse(
            listOf("api key", "authentication", "user not found", "http 401", "http 403", "http 429", "expired", "quota", "rate limit")
                .any { it in lower },
        )
    }

    @Test
    fun `dsh repair text cannot be reclassified as transient either`() {
        val repair = DshCompatibility.repairFailedMessage("0.2.1-rc.2", "0.1.2-rc.1")
        assertFalse(RuntimeFailureClassifier.isTransientNetworkFailure(repair))
    }
}
