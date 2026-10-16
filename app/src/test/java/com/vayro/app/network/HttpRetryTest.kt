package com.vayro.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA Medium: one transient 429/5xx used to be final everywhere. The
 * shared policy retries the retryable codes with exponential backoff,
 * honoring the server's Retry-After header when present.
 */
class HttpRetryTest {

    @Test
    fun `429 and 5xx are retryable`() {
        assertTrue(HttpRetry.isRetryable(429))
        assertTrue(HttpRetry.isRetryable(500))
        assertTrue(HttpRetry.isRetryable(502))
        assertTrue(HttpRetry.isRetryable(503))
        assertTrue(HttpRetry.isRetryable(599))
    }

    @Test
    fun `other codes are not retryable`() {
        assertFalse(HttpRetry.isRetryable(200))
        assertFalse(HttpRetry.isRetryable(400))
        assertFalse(HttpRetry.isRetryable(401))
        assertFalse(HttpRetry.isRetryable(403))
        assertFalse(HttpRetry.isRetryable(404))
        assertFalse(HttpRetry.isRetryable(0))
    }

    @Test
    fun `backoff doubles per attempt and caps at 8s`() {
        assertEquals(1_000L, HttpRetry.backoffDelayMs(0))
        assertEquals(2_000L, HttpRetry.backoffDelayMs(1))
        assertEquals(4_000L, HttpRetry.backoffDelayMs(2))
        assertEquals(8_000L, HttpRetry.backoffDelayMs(3))
        assertEquals(8_000L, HttpRetry.backoffDelayMs(10))
    }

    @Test
    fun `retry-after seconds parse to millis`() {
        // 60s cap: use a value under the cap here; the cap itself is
        // covered by the next test.
        assertEquals(45_000L, HttpRetry.retryAfterMs("45"))
        assertEquals(1_000L, HttpRetry.retryAfterMs("1"))
    }

    @Test
    fun `retry-after caps at 60s and rejects garbage`() {
        assertEquals(60_000L, HttpRetry.retryAfterMs("3600"))
        assertNull(HttpRetry.retryAfterMs(null))
        assertNull(HttpRetry.retryAfterMs(""))
        assertNull(HttpRetry.retryAfterMs("soon"))
        assertNull(HttpRetry.retryAfterMs("0"))
        assertNull(HttpRetry.retryAfterMs("-5"))
    }

    @Test
    fun `max attempts is three`() {
        assertEquals(3, HttpRetry.MAX_ATTEMPTS)
    }
}
