package com.vayro.app.runtime

import com.vayro.app.data.GEMINI_API_DEFAULT_MODEL
import com.vayro.app.data.GEMINI_API_MODELS
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiApiRoutingTest {

    @Test
    fun `toggle on with key routes to Gemini API`() {
        assertEquals(GeminiRoute.GEMINI_API, resolveGeminiRoute(true, "secret-key"))
    }

    @Test
    fun `toggle on without key falls back to OAuth`() {
        assertEquals(GeminiRoute.ANTIGRAVITY_OAUTH, resolveGeminiRoute(true, null))
        assertEquals(GeminiRoute.ANTIGRAVITY_OAUTH, resolveGeminiRoute(true, ""))
        assertEquals(GeminiRoute.ANTIGRAVITY_OAUTH, resolveGeminiRoute(true, "   "))
    }

    @Test
    fun `toggle off always routes to OAuth even with key saved`() {
        assertEquals(GeminiRoute.ANTIGRAVITY_OAUTH, resolveGeminiRoute(false, "secret-key"))
        assertEquals(GeminiRoute.ANTIGRAVITY_OAUTH, resolveGeminiRoute(false, null))
    }

    @Test
    fun `default model is Gemini 4 Argon`() {
        assertEquals("gemini-4-argon", GEMINI_API_DEFAULT_MODEL)
        assertTrue(GEMINI_API_MODELS.any { it.first == "gemini-4-argon" })
    }

    @Test
    fun `request body carries system prompt history and prompt`() {
        val body = buildGeminiRequestBody(
            systemPrompt = "Be concise.",
            history = listOf("user" to "Hello", "model" to "Hi there"),
            prompt = "Write a function",
        )
        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("Hello", contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("Write a function", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"))
        val system = body.getJSONObject("system_instruction")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("Be concise.", system)
    }

    @Test
    fun `stream line parser extracts text deltas`() {
        val chunk = JSONObject()
            .put("candidates", JSONArray().put(
                JSONObject().put("content", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", "Hello ")).put(JSONObject().put("text", "world")),
                )),
            )).toString()
        assertEquals("Hello world", parseGeminiStreamLine(chunk))
        assertEquals("Hello world", parseGeminiStreamLine("data: $chunk"))
    }

    @Test
    fun `stream line parser ignores control lines and errors`() {
        assertNull(parseGeminiStreamLine(""))
        assertNull(parseGeminiStreamLine("[DONE]"))
        assertNull(parseGeminiStreamLine("not json at all"))
        val error = JSONObject().put("error", JSONObject().put("message", "bad key")).toString()
        assertNull(parseGeminiStreamLine(error))
    }

    @Test
    fun `error messages never carry the key`() {
        val message = geminiApiErrorMessage(403, "{\"error\":{\"message\":\"API key not valid.\"}}")
        assertTrue(message.contains("invalid", ignoreCase = true))
        assertTrue(!message.contains("secret-key-value"))
    }
}
