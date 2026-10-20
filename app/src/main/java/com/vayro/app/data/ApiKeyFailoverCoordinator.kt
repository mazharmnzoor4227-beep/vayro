package com.vayro.app.data

/**
 * Failure classification for automatic API-key failover.
 *
 * The rotation itself is hand-rolled at the call sites (home chat walks
 * its ordered credential list in MainViewModel; workspace sessions rotate
 * in MainViewModel.retryWithNextApiKey), because each surface persists
 * and reports the switch differently. This class is the shared
 * classifier both paths consult: [recordFailure] / [isKeyFailure] decide
 * whether a failure looks like a key problem worth rotating over, and
 * [isQuotaFailure] whether it specifically spent the key's quota.
 *
 * v3.2.49: the session-scoped rotation state that used to live here
 * (nextKey / reset / failedCount and their failed/cooldown sets) was
 * removed — an exhaustive call-site audit showed no production caller
 * ever used it (rotation is hand-rolled, above); only its own unit
 * tests kept it alive.
 *
 * Pure logic (no Android dependencies) so it is unit-testable; the
 * [ApiKeyVault] remains the encrypted storage backing.
 */
class ApiKeyFailoverCoordinator {

    companion object {
        /** How long a quota-exhausted key stays out of rotation before being retried. */
        const val QUOTA_COOLDOWN_MS = 60 * 60 * 1000L // 60 minutes

        /** Matches quota / rate-limit / auth failures that warrant a key rotation. */
        fun isKeyFailure(reason: String): Boolean {
            val value = reason.lowercase()
            return "api key" in value || "authentication" in value || "user not found" in value ||
                "http 401" in value || "http 403" in value || "http 429" in value ||
                "expired" in value || "quota" in value || "rate limit" in value ||
                "ratelimit" in value || "limit exceeded" in value || "out of credits" in value ||
                "too many requests" in value || "insufficient" in value
        }

        /** Matches failures that specifically indicate the key's quota is spent (vs. bad key). */
        fun isQuotaFailure(reason: String): Boolean {
            val value = reason.lowercase()
            return "quota" in value || "rate limit" in value || "ratelimit" in value ||
                "http 429" in value || "limit exceeded" in value || "out of credits" in value ||
                "too many requests" in value || "insufficient" in value ||
                "usage limit" in value || "plan limit" in value
        }
    }

    /**
     * Reports that a key failed with [reason]. Returns true when the
     * failure looks like a key problem (worth rotating); false for
     * unrelated errors. The keyId/nowMs parameters are kept for call-site
     * compatibility; the decision itself is the pure [isKeyFailure]
     * classification.
     */
    fun recordFailure(keyId: String, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return isKeyFailure(reason)
    }
}
