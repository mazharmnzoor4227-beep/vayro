package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v3.2.52: the persistent last-known connection state — encode/decode
 * round-trip, reason shortening, and the exact status-line formats the
 * AI Agent screen renders under the Test button.
 */
class AgentConnectionStateTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")

    /** 2026-10-20 18:12 in Karachi → "6:12 PM". */
    private val atMillis: Long =
        ZonedDateTime.of(2026, 10, 20, 18, 12, 0, 0, zone).toInstant().toEpochMilli()

    private fun record(
        outcome: AgentConnectionState.Outcome,
        reason: String = "",
    ) = AgentConnectionState.Record(outcome, reason, atMillis)

    @Test
    fun `status lines are exact`() {
        assertEquals(
            "Last check: Connected · 6:12 PM",
            AgentConnectionState.statusLine(record(AgentConnectionState.Outcome.TEST_CONNECTED), zone),
        )
        assertEquals(
            "Last task answered · 6:12 PM",
            AgentConnectionState.statusLine(record(AgentConnectionState.Outcome.TASK_ANSWERED), zone),
        )
        assertEquals(
            "Last check failed · 6:12 PM — Antigravity did not answer within 2 minutes.",
            AgentConnectionState.statusLine(
                record(
                    AgentConnectionState.Outcome.TEST_FAILED,
                    "Antigravity did not answer within 2 minutes.",
                ),
                zone,
            ),
        )
        assertEquals(
            "Last task failed · 6:12 PM — quota exceeded",
            AgentConnectionState.statusLine(
                record(AgentConnectionState.Outcome.TASK_FAILED, "quota exceeded"),
                zone,
            ),
        )
        // A failure without a reason carries no dangling suffix.
        assertEquals(
            "Last check failed · 6:12 PM",
            AgentConnectionState.statusLine(record(AgentConnectionState.Outcome.TEST_FAILED), zone),
        )
    }

    @Test
    fun `encode decode round trips`() {
        val original = record(
            AgentConnectionState.Outcome.TEST_FAILED,
            "Account is not eligible | see https://example.com/help",
        )
        val decoded = AgentConnectionState.decode(AgentConnectionState.encode(original))
        assertEquals(original.outcome, decoded?.outcome)
        assertEquals(original.atMillis, decoded?.atMillis)
        // The pipe in the reason is sanitised so the format stays parseable.
        assertEquals("Account is not eligible / see https://example.com/help", decoded?.reason)
    }

    @Test
    fun `decode rejects malformed values`() {
        assertNull(AgentConnectionState.decode(null))
        assertNull(AgentConnectionState.decode(""))
        assertNull(AgentConnectionState.decode("garbage"))
        assertNull(AgentConnectionState.decode("NOPE|123|x"))
        assertNull(AgentConnectionState.decode("TEST_CONNECTED|notanumber|x"))
    }

    @Test
    fun `reason shortening collapses whitespace and caps length`() {
        assertEquals("a b c", AgentConnectionState.shortenReason("  a\n b\t\tc  "))
        val long = "x".repeat(200)
        val shortened = AgentConnectionState.shortenReason(long)
        assertEquals(90, shortened.length)
        assertTrue(shortened.endsWith("…"))
    }
}
