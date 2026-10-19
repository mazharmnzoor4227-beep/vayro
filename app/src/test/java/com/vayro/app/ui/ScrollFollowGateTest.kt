package com.vayro.app.ui

import org.junit.Assert.assertEquals
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

    // ---- v3.2.42: the gate against the REVERSED list indexing ----
    // Both chat lists now run reverseLayout = true: the newest
    // message is index 0 (the visual bottom) and "at bottom" is
    // expressed by isParkedAtNewest over canScrollBackward.

    @Test
    fun `reversed - parked at the newest message`() {
        // Cannot scroll backward (toward index 0) any further.
        assertTrue(ScrollFollowGate.isParkedAtNewest(canScrollBackward = false))
        assertEquals(0, ScrollFollowGate.NEWEST_INDEX)
    }

    @Test
    fun `reversed - scrolled up into history is not parked`() {
        assertFalse(ScrollFollowGate.isParkedAtNewest(canScrollBackward = true))
    }

    @Test
    fun `reversed - follow while parked at the newest`() {
        assertTrue(
            ScrollFollowGate.shouldFollow(
                ownSend = false,
                atBottom = ScrollFollowGate.isParkedAtNewest(canScrollBackward = false),
                userScrollInProgress = false, userScrolledUp = false, overlayOpen = false,
            ),
        )
    }

    @Test
    fun `reversed - no follow while reading history`() {
        assertFalse(
            ScrollFollowGate.shouldFollow(
                ownSend = false,
                atBottom = ScrollFollowGate.isParkedAtNewest(canScrollBackward = true),
                userScrollInProgress = false, userScrolledUp = false, overlayOpen = false,
            ),
        )
    }

    @Test
    fun `reversed - own send follows even from history, but never mid-gesture or under an overlay`() {
        assertTrue(
            ScrollFollowGate.shouldFollow(
                ownSend = true,
                atBottom = ScrollFollowGate.isParkedAtNewest(canScrollBackward = true),
                userScrollInProgress = false, userScrolledUp = false, overlayOpen = false,
            ),
        )
        assertFalse(
            ScrollFollowGate.shouldFollow(
                ownSend = true,
                atBottom = ScrollFollowGate.isParkedAtNewest(canScrollBackward = true),
                userScrollInProgress = true, userScrolledUp = false, overlayOpen = false,
            ),
        )
        assertFalse(
            ScrollFollowGate.shouldFollow(
                ownSend = true,
                atBottom = ScrollFollowGate.isParkedAtNewest(canScrollBackward = true),
                userScrollInProgress = false, userScrolledUp = false, overlayOpen = true,
            ),
        )
    }

    @Test
    fun `reversed - restore semantics survive the flip`() {
        // Parked at the newest IS live index 0 / offset 0 in a
        // reversed list — restoring is safe there only because a
        // position saved at the newest is stored as 0/0 too, which
        // storeHasPosition reports as "nothing to restore".
        assertFalse(ScrollFollowGate.shouldRestore(false, liveIndex = 0, liveOffset = 0, userScrollInProgress = false))
        // A real saved reading position (index > 0 in reversed
        // indexing = up in the history) restores over a reset list.
        assertTrue(ScrollFollowGate.shouldRestore(true, liveIndex = 0, liveOffset = 0, userScrollInProgress = false))
        // And never over a live reading position.
        assertFalse(ScrollFollowGate.shouldRestore(true, liveIndex = 12, liveOffset = 30, userScrollInProgress = false))
    }

    @Test
    fun `reversed - arrow rule counts items toward the newest`() {
        // Parked at the newest: first visible index 0 -> no arrow.
        assertFalse(isFarFromNewest(firstVisibleIndex = 0, visibleCount = 4))
        // One item toward the newest, screenful is 4 -> not far.
        assertFalse(isFarFromNewest(firstVisibleIndex = 1, visibleCount = 4))
        // Exactly a screenful toward the newest -> far.
        assertTrue(isFarFromNewest(firstVisibleIndex = 4, visibleCount = 4))
        assertTrue(isFarFromNewest(firstVisibleIndex = 25, visibleCount = 5))
        // Minimum threshold is 2 regardless of a tiny viewport count.
        assertFalse(isFarFromNewest(firstVisibleIndex = 1, visibleCount = 1))
        assertTrue(isFarFromNewest(firstVisibleIndex = 2, visibleCount = 1))
        // No items laid out yet.
        assertFalse(isFarFromNewest(firstVisibleIndex = -1, visibleCount = 4))
    }
}
