package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v3.2.52: connection tests must wait at least as long as the real
 * path waits — a test that gives up early condemns slow-but-working
 * connections (the owner's cold Antigravity hello took 1–1.5 minutes
 * while the test capped at 60 s, and the Gemini API probe failed at
 * 45 s reads the real path allows 120 s for).
 */
class ConnectionPatienceTest {

    @Test
    fun `antigravity hello bound matches the real session print timeout`() {
        assertEquals(120_000L, HELLO_TIMEOUT_MILLIS)
    }

    @Test
    fun `hello no-answer message states the wait honestly`() {
        assertEquals(
            "Antigravity did not answer within 2 minutes.",
            helloNoAnswerMessage(120_000L),
        )
        assertEquals(
            "Antigravity did not answer within 1 minute.",
            helloNoAnswerMessage(60_000L),
        )
    }

    @Test
    fun `gemini probe patience equals the real path patience`() {
        // openConnection() applies these same constants to the real
        // streaming agent path; the probe must not be stricter.
        assertEquals(30_000, GEMINI_CONNECT_TIMEOUT_MILLIS)
        assertEquals(120_000, GEMINI_READ_TIMEOUT_MILLIS)
    }
}
