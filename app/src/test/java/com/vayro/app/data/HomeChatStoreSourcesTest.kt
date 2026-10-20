package com.vayro.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.51: a reply's web sources persist with the message — the
 * JSON round-trip keeps them, and threads saved by older versions
 * (no sourceUrls key) still load.
 */
class HomeChatStoreSourcesTest {

    @Test
    fun `sources survive the json round trip`() {
        val message = HomeChatMessage(
            id = "m1",
            fromUser = false,
            text = "Gold is at a record high.",
            createdAtMillis = 1234L,
            sourceUrls = listOf("https://ex.com/gold", "https://ex.com/silver"),
        )
        val restored = homeChatMessageFromJson(message.toJson())
        assertEquals(message.id, restored.id)
        assertEquals(message.text, restored.text)
        assertEquals(message.createdAtMillis, restored.createdAtMillis)
        assertEquals(message.sourceUrls, restored.sourceUrls)
    }

    @Test
    fun `legacy messages without sources load with an empty list`() {
        val legacy = JSONObject()
            .put("id", "old")
            .put("fromUser", true)
            .put("text", "hello")
        val restored = homeChatMessageFromJson(legacy)
        assertEquals("hello", restored.text)
        assertTrue(restored.sourceUrls.isEmpty())
    }
}
