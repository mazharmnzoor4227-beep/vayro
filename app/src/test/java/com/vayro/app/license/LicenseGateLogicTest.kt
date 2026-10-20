package com.vayro.app.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.46 (audit H1b + M2): the license gate decision matrix.
 *
 * H1b — a fresh install that has NEVER had a successful license-server
 * contact must not open on the never-confirmed factory default "off".
 * M2 — the offline grace window is anchored to both the wall clock and
 * the monotonic clock; the smaller remaining window wins, so a set-back
 * clock cannot stretch a timed license.
 */
class LicenseGateLogicTest {

    private val grace = LicenseStore.GRACE_MS // 7 days
    private val day = 24L * 3600 * 1000

    // ---------- H1b: step() ----------

    @Test
    fun `never confirmed and server unreachable stays closed`() {
        val step = LicenseGateLogic.step(
            freshMode = null, storedMode = "off", modeConfirmed = false, hasToken = false
        )
        assertEquals(LicenseGateLogic.Step.NEEDS_SERVER_CONTACT, step)
    }

    @Test
    fun `never confirmed and server unreachable stays closed even with a token`() {
        val step = LicenseGateLogic.step(
            freshMode = null, storedMode = "on", modeConfirmed = false, hasToken = true
        )
        assertEquals(LicenseGateLogic.Step.NEEDS_SERVER_CONTACT, step)
    }

    @Test
    fun `never confirmed but server says off opens`() {
        val step = LicenseGateLogic.step(
            freshMode = "off", storedMode = "off", modeConfirmed = false, hasToken = false
        )
        assertEquals(LicenseGateLogic.Step.OPEN_FREE, step)
    }

    @Test
    fun `never confirmed and server on without license needs activation`() {
        val step = LicenseGateLogic.step(
            freshMode = "on", storedMode = "off", modeConfirmed = false, hasToken = false
        )
        assertEquals(LicenseGateLogic.Step.NEEDS_ACTIVATION, step)
    }

    @Test
    fun `never confirmed and server on with license verifies it`() {
        val step = LicenseGateLogic.step(
            freshMode = "on", storedMode = "off", modeConfirmed = false, hasToken = true
        )
        assertEquals(LicenseGateLogic.Step.VERIFY_LICENSE, step)
    }

    @Test
    fun `confirmed off opens while offline`() {
        val step = LicenseGateLogic.step(
            freshMode = null, storedMode = "off", modeConfirmed = true, hasToken = false
        )
        assertEquals(LicenseGateLogic.Step.OPEN_FREE, step)
    }

    @Test
    fun `confirmed on without license needs activation while offline`() {
        val step = LicenseGateLogic.step(
            freshMode = null, storedMode = "on", modeConfirmed = true, hasToken = false
        )
        assertEquals(LicenseGateLogic.Step.NEEDS_ACTIVATION, step)
    }

    @Test
    fun `confirmed on with license verifies while offline`() {
        val step = LicenseGateLogic.step(
            freshMode = null, storedMode = "on", modeConfirmed = true, hasToken = true
        )
        assertEquals(LicenseGateLogic.Step.VERIFY_LICENSE, step)
    }

    @Test
    fun `fresh mode wins over a stale stored mode`() {
        val step = LicenseGateLogic.step(
            freshMode = "off", storedMode = "on", modeConfirmed = true, hasToken = true
        )
        assertEquals(LicenseGateLogic.Step.OPEN_FREE, step)
    }

    // ---------- postCheck(): the verified-license verdicts ----------

    @Test
    fun `active verdict opens`() {
        assertEquals(
            LicenseGateLogic.PostCheck.OPEN_ACTIVE,
            LicenseGateLogic.postCheck(
                reachable = true, ok = true, status = "active",
                graceOpen = false, withinSettle = false
            )
        )
    }

    @Test
    fun `revoked verdict locks`() {
        assertEquals(
            LicenseGateLogic.PostCheck.LOCKED_REVOKED,
            LicenseGateLogic.postCheck(
                reachable = true, ok = false, status = "revoked",
                graceOpen = true, withinSettle = false // grace never rescues a revoked verdict
            )
        )
    }

    @Test
    fun `expired verdict locks as expired`() {
        assertEquals(
            LicenseGateLogic.PostCheck.LOCKED_EXPIRED,
            LicenseGateLogic.postCheck(
                reachable = true, ok = false, status = "expired",
                graceOpen = false, withinSettle = true // expired outranks the settle window
            )
        )
    }

    @Test
    fun `dead verdict inside the settle window softens`() {
        assertEquals(
            LicenseGateLogic.PostCheck.LOCKED_SETTLE,
            LicenseGateLogic.postCheck(
                reachable = true, ok = false, status = "revoked",
                graceOpen = false, withinSettle = true
            )
        )
    }

    @Test
    fun `unreachable with grace left opens on grace`() {
        assertEquals(
            LicenseGateLogic.PostCheck.OPEN_GRACE,
            LicenseGateLogic.postCheck(
                reachable = false, ok = false, status = null,
                graceOpen = true, withinSettle = false
            )
        )
    }

    @Test
    fun `unreachable without grace locks on the connection screen`() {
        assertEquals(
            LicenseGateLogic.PostCheck.LOCKED_OFFLINE,
            LicenseGateLogic.postCheck(
                reachable = false, ok = false, status = null,
                graceOpen = false, withinSettle = false
            )
        )
    }

    // ---------- M2: graceRemainingMs() ----------

    @Test
    fun `grace remains when both clocks are inside the window`() {
        val remaining = LicenseGateLogic.graceRemainingMs(
            lastOkWallMs = 1_000_000L, lastOkMonoMs = 500_000L,
            nowWallMs = 1_000_000L + 3 * day, nowMonoMs = 500_000L + 3 * day,
            graceMs = grace
        )
        assertEquals(4 * day, remaining)
    }

    @Test
    fun `clock setback cannot stretch grace - monotonic measure wins`() {
        // The attack: last OK 8 real days ago, then the phone clock is set
        // back so the wall clock claims only 1 day passed. The monotonic
        // measure (8 days) is the smaller remaining window: no grace.
        val remaining = LicenseGateLogic.graceRemainingMs(
            lastOkWallMs = 1_000_000L, lastOkMonoMs = 500_000L,
            nowWallMs = 1_000_000L + 1 * day, nowMonoMs = 500_000L + 8 * day,
            graceMs = grace
        )
        assertTrue(remaining < 0)
    }

    @Test
    fun `wall clock ahead also closes grace - smaller measure wins both ways`() {
        val remaining = LicenseGateLogic.graceRemainingMs(
            lastOkWallMs = 1_000_000L, lastOkMonoMs = 500_000L,
            nowWallMs = 1_000_000L + 8 * day, nowMonoMs = 500_000L + 1 * day,
            graceMs = grace
        )
        assertTrue(remaining < 0)
    }

    @Test
    fun `no monotonic anchor falls back to the wall measure`() {
        val remaining = LicenseGateLogic.graceRemainingMs(
            lastOkWallMs = 1_000_000L, lastOkMonoMs = 0L,
            nowWallMs = 1_000_000L + 2 * day, nowMonoMs = 999_999_999L,
            graceMs = grace
        )
        assertEquals(5 * day, remaining)
    }

    @Test
    fun `never confirmed has no grace`() {
        val remaining = LicenseGateLogic.graceRemainingMs(
            lastOkWallMs = 0L, lastOkMonoMs = 0L,
            nowWallMs = 1_000_000L, nowMonoMs = 500_000L,
            graceMs = grace
        )
        assertTrue(remaining < 0)
    }

    @Test
    fun `grace boundary - exactly at the limit is still open`() {
        val remaining = LicenseGateLogic.graceRemainingMs(
            lastOkWallMs = 1_000_000L, lastOkMonoMs = 500_000L,
            nowWallMs = 1_000_000L + grace, nowMonoMs = 500_000L + grace,
            graceMs = grace
        )
        assertEquals(0L, remaining)
    }
}
