package com.vayro.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.41 (Scope 1): the user owns the scroll position. These tests
 * pin the gate both chat lists (home thread, workspace chat) route
 * their follow/restore effects through, so a programmatic scroll can
 * never again fire mid-gesture, under an overlay, or after the user
 * scrolled away.
 */
class ScrollFollowGateTest {

    @Test
    fun `own send follows`() {
        assertTrue(
            ScrollFollowGate.shouldFollow(
                ownSend = true, atBottom = false,
                userScrollInProgress = false, userScrolledUp = false, overlayOpen = false,
            ),
        )
    }

    @Test
    fun `parked at the bottom follows new content`() {
        assertTrue(
            ScrollFollowGate.shouldFollow(
                ownSend = false, atBottom = true,
                userScrollInProgress = false, userScrolledUp = false, overlayOpen = false,
            ),
        )
    }

    @Test
    fun `reading up-list never follows`() {
        assertFalse(
            ScrollFollowGate.shouldFollow(
                ownSend = false, atBottom = false,
                userScrollInProgress = false, userScrolledUp = false, overlayOpen = false,
            ),
        )
    }

    @Test
    fun `a scroll gesture in progress blocks every follow, even an own send`() {
        assertFalse(
            ScrollFollowGate.shouldFollow(
                ownSend = true, atBottom = true,
                userScrollInProgress = true, userScrolledUp = false, overlayOpen = false,
            ),
        )
    }

    @Test
    fun `an open overlay blocks follow`() {
        assertFalse(
            ScrollFollowGate.shouldFollow(
                ownSend = true, atBottom = true,
                userScrollInProgress = false, userScrolledUp = false, overlayOpen = true,
            ),
        )
    }

    @Test
    fun `scrolled up during streaming blocks follow`() {
        assertFalse(
            ScrollFollowGate.shouldFollow(
                ownSend = false, atBottom = false,
                userScrollInProgress = false, userScrolledUp = true, overlayOpen = false,
            ),
        )
    }

    @Test
    fun `restore needs a saved position`() {
        assertFalse(ScrollFollowGate.shouldRestore(false, liveIndex = 0, liveOffset = 0, userScrollInProgress = false))
    }

    @Test
    fun `restore fires when the live list lost its position`() {
        assertTrue(ScrollFollowGate.shouldRestore(true, liveIndex = 0, liveOffset = 0, userScrollInProgress = false))
    }

    @Test
    fun `restore never overrides a live reading position`() {
        assertFalse(ScrollFollowGate.shouldRestore(true, liveIndex = 7, liveOffset = 120, userScrollInProgress = false))
        assertFalse(ScrollFollowGate.shouldRestore(true, liveIndex = 0, liveOffset = 40, userScrollInProgress = false))
    }

    @Test
    fun `restore never fires mid-scroll`() {
        assertFalse(ScrollFollowGate.shouldRestore(true, liveIndex = 0, liveOffset = 0, userScrollInProgress = true))
    }
}
