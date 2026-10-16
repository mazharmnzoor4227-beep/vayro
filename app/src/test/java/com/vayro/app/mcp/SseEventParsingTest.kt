package com.vayro.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * QA Medium: legal SSE splits one event's JSON across several consecutive
 * `data:` lines. The old per-line parsing returned the first line alone,
 * which is not valid JSON for a split event, so the payload was
 * silently dropped.
 */
class SseEventParsingTest {

    @Test
    fun `single data line is one event`() {
        assertEquals(
            listOf("""{"jsonrpc":"2.0","id":1}"""),
            McpProtocol.parseSseEvents("data: {\"jsonrpc\":\"2.0\",\"id\":1}\n")
        )
    }

    @Test
    fun `consecutive data lines join into one event`() {
        val body = "data: {\"jsonrpc\":\"2.0\",\n" +
            "data: \"id\":1}\n" +
            "\n"
        assertEquals(listOf("{\"jsonrpc\":\"2.0\",\n\"id\":1}"), McpProtocol.parseSseEvents(body))
    }

    @Test
    fun `blank line separates two events`() {
        val body = "data: {\"id\":1}\n\n" + "data: {\"id\":2}\n\n"
        assertEquals(listOf("{\"id\":1}", "{\"id\":2}"), McpProtocol.parseSseEvents(body))
    }

    @Test
    fun `first payload joins a split event`() {
        val body = "data: {\"jsonrpc\":\"2.0\",\n" +
            "data: \"id\":1}\n" +
            "\n" +
            "data: {\"id\":2}\n"
        assertEquals("{\"jsonrpc\":\"2.0\",\n\"id\":1}", McpProtocol.firstSsePayload(body))
    }

    @Test
    fun `done terminator yields no event`() {
        assertEquals(emptyList<String>(), McpProtocol.parseSseEvents("data: [DONE]\n"))
        assertNull(McpProtocol.firstSsePayload("data: [DONE]\n"))
    }

    @Test
    fun `empty body yields no event`() {
        assertEquals(emptyList<String>(), McpProtocol.parseSseEvents(""))
        assertNull(McpProtocol.firstSsePayload(""))
    }
}
