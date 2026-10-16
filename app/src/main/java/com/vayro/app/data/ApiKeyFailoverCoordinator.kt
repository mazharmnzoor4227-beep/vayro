package com.vayro.app.data

/**
 * Coordinates automatic failover across multiple API keys for a single provider.
 *
 * When a request fails with a quota / rate-limit / auth error, the coordinator marks
 * the current key as exhausted and selects the next healthy key. This powers the
 * multi-account behavior for DeepSeek, NVIDIA NIM, and any other API-key provider:
 * add several keys in Settings, and sessions automatically carry forward when one
 * key hits its limit — no manual switching required.
 *
 * Pure logic (no Android dependencies) so it is unit-testable; the [ApiKeyVault]
 * remains the encrypted storage backing.
 */
class ApiKeyFailoverCoordinator {

    /** Key IDs that have failed in the current session and should be skipped. */
    private val failedKeyIds = mutableSetOf<String>()

    /** Key IDs currently in quota cooldown (exhaustedAt millis). */
    private val quotaCooldowns = mutableMapOf<String, Long>()

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

    /** Clears all per-session failure state, e.g. when a new session starts cleanly. */
    fun reset() {
        failedKeyIds.clear()
        quotaCooldowns.clear()
    }

    /**
     * Records that [keyId] failed with [reason]. Returns true when the failure looks
     * like a key problem (worth rotating); false for unrelated errors.
     */
    fun recordFailure(keyId: String, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isKeyFailure(reason)) return false
        failedKeyIds += keyId
        if (isQuotaFailure(reason)) {
            quotaCooldowns[keyId] = nowMs
        }
        return true
    }

    /**
     * Selects the next key to try from [credentials] (active key first).
     * Skips keys that failed this session, preferring keys never exhausted; falls back
     * to the key whose quota cooldown elapsed longest ago.
     */
    fun nextKey(
        credentials: List<ApiKeyCredential>,
        nowMs: Long = System.currentTimeMillis(),
    ): ApiKeyCredential? {
        if (credentials.isEmpty()) return null
        // 1. Keys that have not failed this session and are not in quota cooldown.
        val fresh = credentials.filter { it.id !in failedKeyIds && it.id !in quotaCooldowns }
        if (fresh.isNotEmpty()) return fresh.first()
        // 2. Keys whose quota cooldown has elapsed.
        val cooledDown = credentials
            .filter { it.id in quotaCooldowns && nowMs - (quotaCooldowns[it.id] ?: 0L) >= QUOTA_COOLDOWN_MS }
            .sortedBy { quotaCooldowns[it.id] }
        if (cooledDown.isNotEmpty()) {
            val chosen = cooledDown.first()
            quotaCooldowns.remove(chosen.id)
            failedKeyIds.remove(chosen.id)
            return chosen
        }
        // 3. Any failed key not in quota cooldown (e.g. transient 401 that may recover).
        val retryable = credentials.filter { it.id !in quotaCooldowns }
        return retryable.firstOrNull()
    }

    /** Number of keys currently excluded from rotation. */
    fun failedCount(): Int = failedKeyIds.size
}
