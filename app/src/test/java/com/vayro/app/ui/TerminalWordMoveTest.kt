package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA Medium: the terminal ALT key was a dead control (highlighted but never
 * consumed). It now modifies the arrow keys to move by word; these tests pin
 * the word-boundary math behind that behaviour.
 */
class TerminalWordMoveTest {

    @Test
    fun `prevWordStart moves to start of current word`() {
        assertEquals(11, prevWordStart("git commit -m", 13))
    }

    @Test
    fun `prevWordStart from a word start skips whitespace to the previous word`() {
        assertEquals(4, prevWordStart("git commit -m", 11))
    }

    @Test
    fun `prevWordStart at the start stays at zero`() {
        assertEquals(0, prevWordStart("hello", 0))
    }

    @Test
    fun `prevWordStart clamps a cursor past the end`() {
        assertEquals(0, prevWordStart("hi", 99))
    }

    @Test
    fun `nextWordEnd moves to end of current word`() {
        assertEquals(3, nextWordEnd("git commit", 0))
    }

    @Test
    fun `nextWordEnd from a word end skips whitespace to the next word`() {
        assertEquals(10, nextWordEnd("git commit", 3))
    }

    @Test
    fun `nextWordEnd at the end stays at the end`() {
        assertEquals(5, nextWordEnd("hello", 5))
    }

    @Test
    fun `word movement on empty text stays at zero`() {
        assertEquals(0, prevWordStart("", 0))
        assertEquals(0, nextWordEnd("", 0))
    }
}
