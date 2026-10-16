package com.vayro.app.network

/**
 * QA Medium: one transient 429/5xx used to be final everywhere — a
 * single rate-limit or server blip failed the whole operation
 * (ProviderApiClient, CustomAgentBridge, GitHubClient,
 * CloudExecutionClient, Marketplace). Shared policy: retry the
 * retryable codes with exponential backoff, honoring the server's
 * Retry-After header when present.
 *
 * All sleeps are blocking Thread.sleep: every call site runs on a
 * background/IO thread, never the main thread.
 */
object HttpRetry {
    const val MAX_ATTEMPTS = 3

    fun isRetryable(code: Int): Boolean = code == 429 || code in 500..599

    /** Exponential backoff in ms for a 0-based [attempt]: 1s, 2s, 4s, capped at 8s. */
    fun backoffDelayMs(attempt: Int): Long =
        minOf(1_000L shl attempt.coerceAtLeast(0).coerceAtMost(10), 8_000L)

    /**
     * Parses a Retry-After header value (delta-seconds) to millis.
     * Returns null for missing, blank, non-numeric, or non-positive
     * values — callers then fall back to [backoffDelayMs].
     */
    fun retryAfterMs(headerValue: String?): Long? {
        val seconds = headerValue?.trim()?.toLongOrNull() ?: return null
        if (seconds <= 0) return null
        return minOf(seconds * 1_000L, 60_000L)
    }

    /**
     * Sleeps before the next attempt: the server's Retry-After when it
     * parses, else the exponential backoff. Returns the slept millis.
     */
    fun sleepBeforeRetry(attempt: Int, retryAfterHeader: String?): Long {
        val delayMs = retryAfterMs(retryAfterHeader) ?: backoffDelayMs(attempt)
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return delayMs
    }
}
