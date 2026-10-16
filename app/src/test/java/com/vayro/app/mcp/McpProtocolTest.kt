package com.vayro.app.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpProtocolTest {

    @Test
    fun `request builds valid jsonrpc envelope`() {
        val req = McpProtocol.request("tools/list", JSONObject().put("cursor", "abc"), id = 42)
        assertEquals("2.0", req.getString("jsonrpc"))
        assertEquals(42, req.getInt("id"))
        assertEquals("tools/list", req.getString("method"))
        assertEquals("abc", req.getJSONObject("params").getString("cursor"))
    }

    @Test
    fun `notification has no id field`() {
        val n = McpProtocol.notification("notifications/initialized")
        assertEquals("2.0", n.getString("jsonrpc"))
        assertTrue(n.isNull("id"))
        assertEquals("notifications/initialized", n.getString("method"))
    }

    @Test
    fun `initialize request carries protocol version and client info`() {
        val req = McpProtocol.initializeRequest("test-client", "1.0")
        assertEquals("initialize", req.getString("method"))
        val params = req.getJSONObject("params")
        assertEquals(McpProtocolVersion.PROTOCOL_VERSION, params.getString("protocolVersion"))
        assertEquals("test-client", params.getJSONObject("clientInfo").getString("name"))
        assertEquals("1.0", params.getJSONObject("clientInfo").getString("version"))
    }

    @Test
    fun `tools call request wraps name and arguments`() {
        val args = JSONObject().put("path", "/tmp")
        val req = McpProtocol.toolsCallRequest("read_file", args)
        assertEquals("tools/call", req.getString("method"))
        assertEquals("read_file", req.getJSONObject("params").getString("name"))
        assertEquals("/tmp", req.getJSONObject("params").getJSONObject("arguments").getString("path"))
    }

    @Test
    fun `parseResponse maps result payload`() {
        val json = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 7)
            .put("result", JSONObject().put("ok", true))
        val parsed = McpProtocol.parseResponse(json)
        assertTrue(parsed is McpRpcResult.Success)
        val success = parsed as McpRpcResult.Success
        assertEquals(true, success.result.getBoolean("ok"))
    }

    @Test
    fun `parseResponse maps error payload`() {
        val json = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 7)
            .put(
                "error",
                JSONObject().put("code", -32601).put("message", "Method not found")
            )
        val parsed = McpProtocol.parseResponse(json)
        assertTrue(parsed is McpRpcResult.Failure)
        val failure = parsed as McpRpcResult.Failure
        assertEquals(-32601, failure.code)
        assertEquals("Method not found", failure.message)
    }

    @Test
    fun `parseResponse rejects non jsonrpc messages`() {
        try {
            McpProtocol.parseResponse(JSONObject().put("id", 1))
            fail("expected McpProtocolException")
        } catch (e: McpProtocolException) {
            assertTrue(e.message!!.contains("JSON-RPC"))
        }
    }

    @Test
    fun `parseToolsList extracts definitions and skips nameless entries`() {
        val result = JSONObject().put(
            "tools",
            org.json.JSONArray().apply {
                put(JSONObject().put("name", "read_file").put("description", "Reads a file"))
                put(JSONObject().put("description", "no name here"))
                put(JSONObject().put("name", "write_file").put("inputSchema", JSONObject().put("type", "object")))
            }
        )
        val tools = McpProtocol.parseToolsList(result)
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].name)
        assertEquals("Reads a file", tools[0].description)
        assertEquals("object", tools[1].inputSchema.getString("type"))
    }

    @Test
    fun `parseToolsList returns empty when tools array missing`() {
        assertTrue(McpProtocol.parseToolsList(JSONObject()).isEmpty())
    }

    @Test
    fun `parseToolsCallResult joins text blocks and flags errors`() {
        val result = JSONObject().put(
            "content",
            org.json.JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", "line one"))
                put(JSONObject().put("type", "image").put("data", "zzz"))
                put(JSONObject().put("type", "text").put("text", "line two"))
            }
        ).put("isError", true)
        val outcome = McpProtocol.parseToolsCallResult(result)
        assertTrue(outcome.isError)
        assertEquals("line one\nline two", outcome.text)
    }

    @Test
    fun `parseToolsCallResult handles empty content`() {
        val outcome = McpProtocol.parseToolsCallResult(JSONObject())
        assertFalse(outcome.isError)
        assertEquals("", outcome.text)
    }

    @Test
    fun `parseSseDataLine extracts payload`() {
        assertEquals("{\"a\":1}", McpProtocol.parseSseDataLine("data: {\"a\":1}"))
        assertNull(McpProtocol.parseSseDataLine("data: [DONE]"))
        assertNull(McpProtocol.parseSseDataLine(": keep-alive"))
        assertNull(McpProtocol.parseSseDataLine("event: message"))
        assertNull(McpProtocol.parseSseDataLine(""))
    }

    @Test
    fun `firstSsePayload returns first data line`() {
        val body = ": ping\n\ndata: {\"jsonrpc\":\"2.0\"}\n\ndata: {\"late\":true}\n"
        assertEquals("{\"jsonrpc\":\"2.0\"}", McpProtocol.firstSsePayload(body))
        assertNull(McpProtocol.firstSsePayload("event: open\n\n"))
    }

    @Test
    fun `request ids are unique and increasing`() {
        val a = McpProtocol.nextRequestId()
        val b = McpProtocol.nextRequestId()
        assertTrue(b > a)
        assertNotNull(McpProtocol.request("ping").opt("id"))
    }
}
