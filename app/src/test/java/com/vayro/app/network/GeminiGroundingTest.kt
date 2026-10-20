package com.vayro.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.51: the native Gemini grounded call — request shape (both
 * web tools on board) and response parsing (text + grounded
 * sources from both metadata branches).
 */
class GeminiGroundingTest {

    private val grounding = GeminiGrounding()

    @Test
    fun `grounded body carries both web tools and the system prompt`() {
        val body = grounding.buildGroundedBody(
            "Be helpful.",
            listOf(
                ProviderApiClient.ChatTurn(role = "user", content = "hi"),
                ProviderApiClient.ChatTurn(role = "assistant", content = "hello"),
                ProviderApiClient.ChatTurn(role = "user", content = "what is the score today?"),
            ),
            2048,
        )
        val tools = body.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertTrue(tools.getJSONObject(0).has("google_search"))
        assertTrue(tools.getJSONObject(1).has("url_context"))
        assertEquals(
            "Be helpful.",
            body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        val contents = body.getJSONArray("contents")
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals(2048, body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test
    fun `response parses text and deduped sources from both metadata branches`() {
        val body = """
            {"candidates":[{
              "content":{"parts":[{"text":"The reply "},{"text":"text."}]},
              "groundingMetadata":{"groundingChunks":[
                {"web":{"uri":"https://a.example/1","title":"A"}},
                {"web":{"uri":"https://b.example/2","title":"B"}}
              ]},
              "urlContextMetadata":{"urlMetadata":[
                {"retrievedUrl":"https://b.example/2","urlRetrievalStatus":"URL_RETRIEVAL_STATUS_SUCCESS"},
                {"retrievedUrl":"https://c.example/3","urlRetrievalStatus":"URL_RETRIEVAL_STATUS_SUCCESS"}
              ]}
            }]}
        """.trimIndent()
        val reply = grounding.parseGroundedResponse(body)
        assertEquals("The reply text.", reply.text)
        assertEquals(listOf("https://a.example/1", "https://b.example/2", "https://c.example/3"), reply.sources)
    }

    @Test
    fun `unparseable responses degrade to empty`() {
        val reply = grounding.parseGroundedResponse("not json")
        assertEquals("", reply.text)
        assertTrue(reply.sources.isEmpty())
    }
}
