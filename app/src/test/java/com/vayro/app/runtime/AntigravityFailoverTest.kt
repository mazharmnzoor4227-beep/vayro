package com.vayro.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.50 (Item 2) — the round-robin trigger set. Eligibility is a
 * rotation trigger alongside quota/auth; unrelated failures are not.
 */
class AntigravityFailoverTest {

    // The provider's own words, seen verbatim on the owner's device
    // (v3.2.48 workspace Activity sheet).
    private val eligibilityText =
        "Eligibility check failed: Your current account is not eligible for Antigravity. " +
            "Verify your account to continue. Alternatively, try signing in with another " +
            "personal Google account. Please verify your account in your browser to continue."

    @Test
    fun `eligibility wording triggers a switch`() {
        assertTrue(AntigravityFailover.isEligibilityFailure(eligibilityText))
        assertTrue(AntigravityFailover.isFailoverEligible(eligibilityText))
    }

    @Test
    fun `eligibility wording is not misread as quota or auth`() {
        // The retry flow labels the switch by kind; the eligibility text
        // must land on the eligibility branch, not quota/auth.
        assertFalse(AntigravityFailover.isQuotaFailure(eligibilityText))
        assertFalse(AntigravityFailover.isAuthFailure(eligibilityText))
    }

    @Test
    fun `short eligibility phrase also matches`() {
        assertTrue(AntigravityFailover.isEligibilityFailure("Account not eligible for this service"))
    }

    @Test
    fun `quota wording still triggers a switch (existing behavior)`() {
        val quota = "429: exceeded your current quota for this account"
        assertTrue(AntigravityFailover.isQuotaFailure(quota))
        assertTrue(AntigravityFailover.isFailoverEligible(quota))
        assertFalse(AntigravityFailover.isEligibilityFailure(quota))
    }

    @Test
    fun `auth wording still triggers a switch (existing behavior)`() {
        val auth = "Unauthenticated: invalid authentication credentials (401)"
        assertTrue(AntigravityFailover.isAuthFailure(auth))
        assertTrue(AntigravityFailover.isFailoverEligible(auth))
        assertFalse(AntigravityFailover.isEligibilityFailure(auth))
    }

    @Test
    fun `unrelated failure never triggers a switch`() {
        assertFalse(AntigravityFailover.isFailoverEligible("Error: no space left on device while writing file"))
        assertFalse(AntigravityFailover.isFailoverEligible("Antigravity exited without answering"))
        assertFalse(AntigravityFailover.isFailoverEligible("The selected model is unavailable"))
        assertFalse(AntigravityFailover.isFailoverEligible(""))
        assertFalse(AntigravityFailover.isEligibilityFailure("The selected model is unavailable"))
    }
}
