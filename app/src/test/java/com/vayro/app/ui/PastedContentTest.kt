package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v3.2.40 (Scope F): the paste split — a long paste becomes the chip,
 * typing and short pastes stay in the field, content is never dropped.
 */
class PastedContentTest {

    private val longText = "x".repeat(PASTE_CHIP_THRESHOLD_CHARS + 1)

    @Test
    fun `typing updates the field and never creates a chip`() {
        val split = splitPasteChange("hell", "hello", null)
        assertEquals("hello", split.fieldText)
        assertNull(split.chipText)
    }

    @Test
    fun `a long paste at the end becomes the chip`() {
        val split = splitPasteChange("note: ", "note: " + longText, null)
        assertEquals("note: ", split.fieldText)
        assertEquals(longText, split.chipText)
    }

    @Test
    fun `a long paste into an empty field leaves the field empty`() {
        val split = splitPasteChange("", longText, null)
        assertEquals("", split.fieldText)
        assertEquals(longText, split.chipText)
    }

    @Test
    fun `a long paste in the middle keeps the typed text around it`() {
        val split = splitPasteChange("ab", "a" + longText + "b", null)
        assertEquals("ab", split.fieldText)
        assertEquals(longText, split.chipText)
    }

    @Test
    fun `a paste exactly at the threshold stays in the field`() {
        val exact = "y".repeat(PASTE_CHIP_THRESHOLD_CHARS)
        val split = splitPasteChange("", exact, null)
        assertEquals(exact, split.fieldText)
        assertNull(split.chipText)
    }

    @Test
    fun `a short paste stays in the field`() {
        val split = splitPasteChange("a", "a short paste", null)
        assertEquals("a short paste", split.fieldText)
        assertNull(split.chipText)
    }

    @Test
    fun `a second long paste appends to the existing chip`() {
        val second = "z".repeat(PASTE_CHIP_THRESHOLD_CHARS + 5)
        val split = splitPasteChange("typed", "typed" + second, longText)
        assertEquals("typed", split.fieldText)
        assertEquals(longText + "\n\n" + second, split.chipText)
    }

    @Test
    fun `deleting text never touches the chip`() {
        val split = splitPasteChange("hello world", "hello", longText)
        assertEquals("hello", split.fieldText)
        assertEquals(longText, split.chipText)
    }

    @Test
    fun `combined model text carries the paste in full`() {
        assertEquals("typed\n\n" + longText, combinedModelText("typed", longText))
        assertEquals(longText, combinedModelText("", longText))
        assertEquals("typed", combinedModelText("typed", null))
        assertEquals("typed", combinedModelText("typed", "  "))
    }
}
