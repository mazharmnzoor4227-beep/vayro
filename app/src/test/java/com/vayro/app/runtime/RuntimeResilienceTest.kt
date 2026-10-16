package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResilienceTest {
    @Test fun transientNetworkErrorsAreRetryable() {
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("Network error: connection reset by peer"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("HTTP 503 Service Unavailable"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("SocketTimeoutException: timeout"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("DNS resolution failed"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("read tcp 2409:40d4::48208->2001:4860::443: read: software caused connection abort"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("stream error: stream ID 1; INTERNAL_ERROR"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("context deadline exceeded"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("There was a network issue connecting to the server, please try again."))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("fetch failed"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("connect ECONNREFUSED 127.0.0.1:8080"))
    }

    @Test fun permanentProviderErrorsAreNotRetryable() {
        assertFalse(RuntimeFailureClassifier.isTransientNetworkFailure("HTTP 401 authentication failed"))
        assertFalse(RuntimeFailureClassifier.isTransientNetworkFailure("HTTP 429 quota exceeded"))
        assertFalse(RuntimeFailureClassifier.isTransientNetworkFailure("invalid model"))
        assertFalse(RuntimeFailureClassifier.isTransientNetworkFailure("Stopped by user"))
        assertFalse(RuntimeFailureClassifier.isTransientNetworkFailure("oneoff task completed"))
        assertFalse(RuntimeFailureClassifier.isTransientNetworkFailure("Configured DNS settings in router"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("unexpected eof while reading response"))
        assertTrue(RuntimeFailureClassifier.isTransientNetworkFailure("dns lookup failed for host"))
    }

    @Test fun friendlyNetworkErrorMessageHidesRawSocketErrors() {
        val raw = "agent executor error: generating and executing: request failed: Post \"https://api.example.com/v1internal:streamGenerateContent?alt=sse\": read tcp [2409:40d4:2016:27ce:289f:391a:2ff6:c540]:48208->[2001:4860:4847:400::]:443: read: software caused connection abort"
        val friendly = RuntimeFailureClassifier.friendlyNetworkErrorMessage(raw)
        assertFalse(friendly.contains("2409:40d4"))
        assertFalse(friendly.contains("api.example.com"))
        assertTrue(friendly.contains("cellular/Wi-Fi disturbance") || friendly.contains("Network connection was interrupted"))

        val serverIssueFriendly = RuntimeFailureClassifier.friendlyNetworkErrorMessage("There was a network issue connecting to the server, please try again.")
        assertTrue(serverIssueFriendly.contains("network issue connecting to the server", ignoreCase = true) || serverIssueFriendly.contains("internet connection", ignoreCase = true))
    }

    @Test fun retryPolicyHas3MinuteLimit() {
        val policy = RuntimeRetryPolicy()
        assertEquals(180_000L, policy.maxTotalRetryDurationMillis)
        assertEquals(15, policy.maxAutomaticRetries)
    }

    @Test fun durationFormattingWorks() {
        assertEquals("45s", formatDurationText(45L))
        assertEquals("2m 42s", formatDurationText(162L))
        assertEquals("1h 15m", formatDurationText(4500L))
    }

    @Test fun retryBackoffIsBounded() {
        val policy = RuntimeRetryPolicy(maxAutomaticRetries = 5, initialBackoffMillis = 1000, maxBackoffMillis = 4000, jitterRatio = 0.0)
        assertEquals(1000L, policy.delayMillis(1, 0.5))
        assertEquals(2000L, policy.delayMillis(2, 0.5))
        assertEquals(4000L, policy.delayMillis(5, 0.5))
    }
}
