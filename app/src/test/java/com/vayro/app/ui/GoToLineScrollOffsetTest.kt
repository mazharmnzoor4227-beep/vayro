package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA Medium: Go-to-line scrolled 3x too far because the offset multiplied
 * the 20sp line height by a hardcoded 3 (one phone's density) instead of
 * converting through the real density.
 */
class GoToLineScrollOffsetTest {

    @Test
    fun `first line scrolls to zero`() {
        assertEquals(0, goToLineScrollOffset(1, 60f))
    }

    @Test
    fun `offset is lines times line height in pixels`() {
        // 20sp at density 3 = 60px per line.
        assertEquals(60, goToLineScrollOffset(2, 60f))
        assertEquals(600, goToLineScrollOffset(11, 60f))
    }

    @Test
    fun `offset follows the actual density, not a hardcoded 3`() {
        // 20sp at density 2 = 40px per line: the old code produced 120.
        assertEquals(40, goToLineScrollOffset(2, 40f))
        assertEquals(400, goToLineScrollOffset(11, 40f))
    }

    @Test
    fun `out-of-range lines clamp to zero`() {
        assertEquals(0, goToLineScrollOffset(0, 60f))
        assertEquals(0, goToLineScrollOffset(-5, 60f))
    }
}
