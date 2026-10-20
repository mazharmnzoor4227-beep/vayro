package com.vayro.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v3.2.45 (Item 3): the home Regenerate history shaping — the fresh
 * request must be built from the history WITHOUT the last assistant
 * reply, over the same preceding turns.
 */
class HomeRegenerateTest {

    private fun user(text: String) = HomeChatMessage(fromUser = true, text = text)
    private fun assistant(text: String) = HomeChatMessage(fromUser = false, text = text)
    private fun system(text: String) = HomeChatMessage(fromUser = false, text = text, kind = "system")

    @Test
    fun `drops the last assistant reply and keeps the preceding history`() {
        val messages = listOf(user("hi"), assistant("hello"), user("write a poem"), assistant("roses are red"))
        val shaped = HomeRegenerate.historyWithoutLastReply(messages)
        assertEquals(listOf("hi", "hello", "write a poem"), shaped?.map { it.text })
    }

    @Test
    fun `drops system lines that trailed the last reply`() {
        val messages = listOf(user("q"), assistant("a"), system("Answered by Groq"))
        val shaped = HomeRegenerate.historyWithoutLastReply(messages)
        assertEquals(listOf("q"), shaped?.map { it.text })
    }

    @Test
    fun `single turn thread regenerates to just the user turn`() {
        val shaped = HomeRegenerate.historyWithoutLastReply(listOf(user("q"), assistant("a")))
        assertEquals(listOf("q"), shaped?.map { it.text })
    }

    @Test
    fun `no assistant reply means nothing to regenerate`() {
        assertNull(HomeRegenerate.historyWithoutLastReply(listOf(user("q"))))
        assertNull(HomeRegenerate.historyWithoutLastReply(emptyList()))
    }

    @Test
    fun `a reply with no user turn under it is not regenerable`() {
        assertNull(HomeRegenerate.historyWithoutLastReply(listOf(assistant("a"))))
    }

    @Test
    fun `handoff cards are not replies and do not count`() {
        val card = HomeChatMessage(fromUser = false, text = "This is a job for Work", kind = "handoff", taskText = "build it")
        assertNull(HomeRegenerate.historyWithoutLastReply(listOf(user("build it"), card)))
    }
}
