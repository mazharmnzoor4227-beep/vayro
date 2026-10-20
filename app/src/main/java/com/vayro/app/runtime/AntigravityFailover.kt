package com.vayro.app.runtime

/**
 * v3.2.50 — pure classification of Antigravity failure reasons for the
 * auto round-robin account rotation.
 *
 * History: the quota/auth token lists lived as private helpers inside
 * MainViewModel (isAntigravityQuotaFailure / isAntigravityAuthFailure),
 * untestable in isolation, and they matched quota and auth failures
 * only. The owner's account was refused by Google with an ELIGIBILITY
 * error ("Eligibility check failed: Your current account is not
 * eligible for Antigravity…") — a permanent, per-account refusal that
 * matched neither list, so the rotation never fired and every turn
 * (and the connection test) died on the ineligible account.
 *
 * The lists below are those same lists, verbatim, moved here so the
 * rotation decision is unit-testable (AntigravityFailoverTest), plus
 * [isEligibilityFailure], which matches conservatively on the
 * provider's own eligibility phrases only ("not eligible" /
 * "eligibility") — no paraphrase matching, so an unrelated failure can
 * never trigger a rotation.
 */
object AntigravityFailover {

    fun isQuotaFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "quota" in value ||
            "rate limit" in value ||
            "ratelimit" in value ||
            "resource_exhausted" in value ||
            "resource exhausted" in value ||
            "429" in value ||
            "out of credits" in value ||
            "credits" in value ||
            "limit exceeded" in value ||
            "exhausted" in value ||
            "too many requests" in value ||
            "60 minute" in value ||
            "minute limit" in value ||
            "hourly limit" in value ||
            "hour limit" in value ||
            "daily limit" in value ||
            "day limit" in value ||
            "weekly limit" in value ||
            "week limit" in value ||
            "usage limit" in value ||
            "plan limit" in value ||
            "account limit" in value ||
            "capacity" in value ||
            "overloaded" in value ||
            "quota_exceeded" in value ||
            "rate_limit_exceeded" in value ||
            "reached your limit" in value ||
            "hit your limit" in value ||
            "exceeded your limit" in value ||
            "exceeded your current quota" in value ||
            "insufficient quota" in value ||
            "free tier limit" in value ||
            "tokens per minute" in value ||
            "requests per minute" in value ||
            "requests per day" in value ||
            "requests per week" in value ||
            "billing" in value ||
            "credit balance" in value ||
            "quota limit" in value
    }

    fun isAuthFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "unauthenticated" in value ||
            "401" in value ||
            "invalid authentication credentials" in value ||
            "oauth 2 access token" in value ||
            "login cookie" in value ||
            "authentication required" in value ||
            "authentication failed" in value ||
            "auth expired" in value ||
            "sign-in" in value ||
            "not signed in" in value ||
            "token expired" in value
    }

    /**
     * Google's eligibility refusal for the account itself (seen verbatim
     * on device: "Eligibility check failed: Your current account is not
     * eligible for Antigravity. Verify your account to continue…"). The
     * account will never pass on retry, so — like quota — it is a
     * rotation trigger when other connected accounts exist.
     */
    fun isEligibilityFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "not eligible" in value || "eligibility" in value
    }

    fun isFailoverEligible(reason: String): Boolean =
        isQuotaFailure(reason) || isAuthFailure(reason) || isEligibilityFailure(reason)
}
