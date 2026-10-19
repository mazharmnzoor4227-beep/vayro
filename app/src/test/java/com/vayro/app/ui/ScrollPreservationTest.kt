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
}
