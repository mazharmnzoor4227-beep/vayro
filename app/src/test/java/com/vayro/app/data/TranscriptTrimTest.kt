package com.vayro.app.data

import com.vayro.app.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * QA H28: the transcript persist cap. Every write is bounded to the
 * newest MAX_TRANSCRIPT_MESSAGES instead of rewriting an unbounded
 * transcript (O(n^2) total I/O).
 */
class TranscriptTrimTest {

    private fun messages(count: Int): List<ChatMessage> =
        (0 until count).map { i ->
            ChatMessage(id = "msg-$i", fromUser = i % 2 == 0, text = "message $i")
        }

    @Test
    fun `under the cap the list is returned unchanged`() {
        val input = messages(MAX_TRANSCRIPT_MESSAGES - 1)
        assertSame(input, trimTranscriptForPersist(input))
    }

    @Test
    fun `exactly at the cap the list is returned unchanged`() {
        val input = messages(MAX_TRANSCRIPT_MESSAGES)
        assertSame(input, trimTranscriptForPersist(input))
    }

    @Test
    fun `over the cap only the newest window is kept`() {
        val input = messages(MAX_TRANSCRIPT_MESSAGES + 5)
        val trimmed = trimTranscriptForPersist(input)
        assertEquals(MAX_TRANSCRIPT_MESSAGES, trimmed.size)
        assertEquals("msg-5", trimmed.first().id)
        assertEquals("msg-${MAX_TRANSCRIPT_MESSAGES + 4}", trimmed.last().id)
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<ChatMessage>(), trimTranscriptForPersist(emptyList()))
    }
}
