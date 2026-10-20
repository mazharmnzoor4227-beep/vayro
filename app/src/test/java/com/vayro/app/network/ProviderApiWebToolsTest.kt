package com.vayro.app.network

import com.vayro.app.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.51: provider-native web tools on the chat body — present
 * only when asked, only on the Anthropic Messages shape — and the
 * citation/source walk over native-search responses.
 */
class ProviderApiWebToolsTest {

    private val client = ProviderApiClient()
    private val turns = listOf(ProviderApiClient.ChatTurn(role = "user", content = "latest news?"))

    @Test
    fun `openrouter body declares both server tools`() {
        val body = JSONObject(
            client.chatBody("openai/gpt-4o", ProviderProtocol.OPENROUTER, turns, "sys", 100, ProviderApiClient.WebToolSet.OPENROUTER),
        )
        val tools = body.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals("openrouter:web_search", tools.getJSONObject(0).getString("type"))
        assertEquals("openrouter:web_fetch", tools.getJSONObject(1).getString("type"))
    }

    @Test
    fun `anthropic body declares the web search server tool`() {
        val body = JSONObject(
            client.chatBody("claude-sonnet-5", ProviderProtocol.ANTHROPIC, turns, "sys", 100, ProviderApiClient.WebToolSet.ANTHROPIC),
        )
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals("web_search_20250305", tools.getJSONObject(0).getString("type"))
        assertEquals("web_search", tools.getJSONObject(0).getString("name"))
        assertEquals(5, tools.getJSONObject(0).getInt("max_uses"))
    }

    @Test
    fun `no tools without the flag and bodies stay identical`() {
        val anthropicPlain = client.chatBody("claude-sonnet-5", ProviderProtocol.ANTHROPIC, turns, "sys", 100)
        assertFalse(JSONObject(anthropicPlain).has("tools"))
        // The default-argument call (every pre-v3.2.51 caller) is the
        // same body as an explicit null.
        assertEquals(
            anthropicPlain,
            client.chatBody("claude-sonnet-5", ProviderProtocol.ANTHROPIC, turns, "sys", 100, null),
        )
        // A tool request never leaks into a non-Messages shape.
        val openAi = client.chatBody("gpt-4o", ProviderProtocol.OPENAI_CHAT, turns, "sys", 100, ProviderApiClient.WebToolSet.OPENROUTER)
        assertFalse(JSONObject(openAi).has("tools"))
    }

    @Test
    fun `sources are collected from search results and citations only`() {
        val body = """
            {"content":[
              {"type":"text","text":"Gold rose today.","citations":[
                {"type":"web_search_result_location","url":"https://cite.example/a"}
              ]},
              {"type":"server_tool_use","name":"web_search","input":{"query":"gold"}},
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","url":"https://res.example/1","title":"One"},
                {"type":"web_search_result","url":"https://res.example/1","title":"Dupe"}
              ]},
              {"type":"image","source":{"url":"https://img.example/x"}}
            ]}
        """.trimIndent()
        val sources = client.parseChatSources(body)
        assertEquals(listOf("https://cite.example/a", "https://res.example/1"), sources)
        assertFalse(sources.contains("https://img.example/x"))
    }

    @Test
    fun `garbage bodies yield no sources`() {
        assertTrue(client.parseChatSources("").isEmpty())
        assertTrue(client.parseChatSources("not json").isEmpty())
    }
}
