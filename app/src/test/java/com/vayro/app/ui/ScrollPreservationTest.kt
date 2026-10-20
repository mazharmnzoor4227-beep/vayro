package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.40 (Scopes H + I/I2): the scroll-to-bottom arrow's visibility
 * rule and the process-level scroll-position store.
 */
class ScrollPreservationTest {

    @Test
    fun `arrow hidden when the last item is visible`() {
        assertFalse(isFarFromBottom(totalItems = 10, lastVisibleIndex = 9, visibleCount = 4))
    }

    @Test
    fun `arrow hidden when less than a screenful sits below`() {
        // 2 items below the viewport, a screenful is 4 -> not far.
        assertFalse(isFarFromBottom(totalItems = 10, lastVisibleIndex = 7, visibleCount = 4))
    }

    @Test
    fun `arrow shows when a screenful or more sits below`() {
        assertTrue(isFarFromBottom(totalItems = 30, lastVisibleIndex = 10, visibleCount = 5))
        // Exactly one screenful below also counts as far.
        assertTrue(isFarFromBottom(totalItems = 10, lastVisibleIndex = 5, visibleCount = 4))
    }

    @Test
    fun `arrow hidden for empty and single item lists`() {
        assertFalse(isFarFromBottom(totalItems = 0, lastVisibleIndex = -1, visibleCount = 0))
        assertFalse(isFarFromBottom(totalItems = 1, lastVisibleIndex = 0, visibleCount = 1))
    }

    @Test
    fun `scroll store saves restores and clears per key`() {
        ChatScrollStore.save("ws:proj:chat1", 3, 40)
        ChatScrollStore.save("home:thread1", 7, 0)
        assertEquals(3 to 40, ChatScrollStore.get("ws:proj:chat1"))
        assertEquals(7 to 0, ChatScrollStore.get("home:thread1"))
        assertNull(ChatScrollStore.get("ws:proj:other"))
        ChatScrollStore.clear("ws:proj:chat1")
        assertNull(ChatScrollStore.get("ws:proj:chat1"))
        ChatScrollStore.clear("home:thread1")
    }

    // ---- v3.2.43 (D1): restores are layout-versioned + range-checked ----

    @Test
    fun `validated position returns an in-range saved position`() {
        ChatScrollStore.save("home:t-valid", 4, 120)
        assertEquals(4 to 120, ChatScrollStore.validatedPosition("home:t-valid", totalItems = 10))
        // The oldest item itself is a legitimate place to have left off.
        assertEquals(4 to 120, ChatScrollStore.validatedPosition("home:t-valid", totalItems = 5))
        ChatScrollStore.clear("home:t-valid")
    }

    @Test
    fun `validated position drops an index past the end instead of clamping onto the oldest`() {
        // A position captured for a longer conversation (e.g. written
        // under this key by the pre-fix save/restore race): applying
        // it used to clamp onto the last item — the oldest message.
        ChatScrollStore.save("home:t-stale", 55, 0)
        assertNull(ChatScrollStore.validatedPosition("home:t-stale", totalItems = 12))
        ChatScrollStore.clear("home:t-stale")
    }

    @Test
    fun `validated position is null for empty lists and unknown keys`() {
        ChatScrollStore.save("home:t-empty", 0, 0)
        assertNull(ChatScrollStore.validatedPosition("home:t-empty", totalItems = 0))
        assertNull(ChatScrollStore.validatedPosition("home:t-never-saved", totalItems = 9))
        ChatScrollStore.clear("home:t-empty")
    }

    @Test
    fun `positions written under a foreign layout version are invalidated`() {
        // Layout version 1 = the pre-3.2.42 forward layout: its
        // indices point at the opposite end of the reversed lists
        // and must never be applied.
        ChatScrollStore.saveWithLayoutVersion("home:t-legacy", 3, 40, layoutVersion = 1)
        assertNull(ChatScrollStore.get("home:t-legacy"))
        assertNull(ChatScrollStore.validatedPosition("home:t-legacy", totalItems = 10))
        ChatScrollStore.clear("home:t-legacy")
    }
}
