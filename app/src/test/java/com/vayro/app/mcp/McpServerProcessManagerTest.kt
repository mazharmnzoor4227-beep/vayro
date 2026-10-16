package com.vayro.app.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpServerProcessManagerTest {

    private fun session(vararg processes: FakeMcpProcess, maxRestarts: Int = 3): McpServerSession =
        McpServerSession(
            testStdioServer(),
            ScriptedMcpProcessFactory(processes.toMutableList()),
            ioTimeoutMs = 2_000,
            maxRestarts = maxRestarts,
            restartBackoffMs = 0
        )

    @Test
    fun `start performs initialize handshake and reaches running`() {
        val fake = FakeMcpProcess(mcpHandshakeResponders())
        val s = session(fake)
        assertTrue(s.start(mapOf("K" to "V")))
        assertEquals(McpSessionState.RUNNING, s.state)
        val methods = fake.written.map { JSONObject(it).optString("method", "") }
        assertTrue(methods.contains("initialize"))
        assertTrue(methods.contains("notifications/initialized"))
    }

    @Test
    fun `start passes resolved env to the process`() {
        val fake = FakeMcpProcess(mcpHandshakeResponders())
        val factory = ScriptedMcpProcessFactory(mutableListOf(fake))
        val s = McpServerSession(testStdioServer(), factory, ioTimeoutMs = 2_000, restartBackoffMs = 0)
        s.start(mapOf("API_KEY" to "sekret"))
        assertEquals("sekret", factory.starts.single()["API_KEY"])
    }

    @Test
    fun `start fails honestly when handshake errors`() {
        val fake = FakeMcpProcess(
            mapOf("initialize" to { req -> testRpcError(req, -32600, "bad") })
        )
        val s = session(fake)
        assertFalse(s.start(emptyMap()))
        assertEquals(McpSessionState.FAILED, s.state)
    }

    @Test
    fun `callTool round trips through the fake server`() {
        val fake = FakeMcpProcess(mcpHandshakeResponders())
        val s = session(fake)
        assertTrue(s.start(emptyMap()))
        val outcome = s.callTool("echo", JSONObject().put("text", "hi"))
        assertFalse(outcome.isError)
        assertEquals("echo-ok", outcome.text)
        val callReq = JSONObject(fake.written.first { JSONObject(it).optString("method") == "tools/call" })
        assertEquals("echo", callReq.getJSONObject("params").getString("name"))
    }

    @Test
    fun `callTool surfaces server rpc errors`() {
        val responders = mcpHandshakeResponders() + mapOf(
            "tools/call" to { req: JSONObject -> testRpcError(req, -32602, "unknown tool") }
        )
        val s = session(FakeMcpProcess(responders))
        assertTrue(s.start(emptyMap()))
        try {
            s.callTool("nope", JSONObject())
            fail("expected McpRpcException")
        } catch (e: McpRpcException) {
            assertEquals(-32602, e.code)
        }
    }

    @Test
    fun `listTools parses advertised tools`() {
        val s = session(FakeMcpProcess(mcpHandshakeResponders()))
        assertTrue(s.start(emptyMap()))
        val tools = s.listTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)
    }

    @Test
    fun `callTool times out honestly when server hangs`() {
        val fake = FakeMcpProcess(mcpHandshakeResponders())
        val s = McpServerSession(
            testStdioServer(), ScriptedMcpProcessFactory(mutableListOf(fake)),
            ioTimeoutMs = 300, restartBackoffMs = 0
        )
        assertTrue(s.start(emptyMap()))
        fake.hangReads = true
        try {
            s.callTool("echo", JSONObject())
            fail("expected McpTransportException")
        } catch (e: McpTransportException) {
            assertTrue(e.message!!.contains("timed out") || e.message!!.contains("closed"))
        }
    }

    @Test
    fun `ensureRunning restarts a dead process within budget`() {
        val dead = FakeMcpProcess(mcpHandshakeResponders(), alive = false)
        val good = FakeMcpProcess(mcpHandshakeResponders())
        val factory = ScriptedMcpProcessFactory(mutableListOf(dead, good))
        val s = McpServerSession(
            testStdioServer(), factory, ioTimeoutMs = 500, maxRestarts = 2, restartBackoffMs = 0
        )
        assertFalse(s.start(emptyMap()))
        assertEquals(McpSessionState.FAILED, s.state)
        assertTrue(s.ensureRunning(emptyMap()))
        assertEquals(McpSessionState.RUNNING, s.state)
        assertEquals(1, s.restartsUsed())
        assertEquals(2, factory.startCalls)
    }

    @Test
    fun `ensureRunning refuses after restart budget exhausted`() {
        val dead1 = FakeMcpProcess(mcpHandshakeResponders(), alive = false)
        val dead2 = FakeMcpProcess(mcpHandshakeResponders(), alive = false)
        val factory = ScriptedMcpProcessFactory(mutableListOf(dead1, dead2))
        val s = McpServerSession(
            testStdioServer(), factory, ioTimeoutMs = 300, maxRestarts = 1, restartBackoffMs = 0
        )
        assertFalse(s.start(emptyMap()))
        assertFalse(s.ensureRunning(emptyMap()))
        assertFalse(s.ensureRunning(emptyMap()))
        assertEquals(2, factory.startCalls)
        assertEquals(McpSessionState.FAILED, s.state)
    }

    @Test
    fun `stop shuts the process down gracefully`() {
        val fake = FakeMcpProcess(mcpHandshakeResponders())
        val s = session(fake)
        assertTrue(s.start(emptyMap()))
        s.stop()
        assertEquals(McpSessionState.STOPPED, s.state)
        assertEquals(1, fake.gracefulCalls)
        assertFalse(fake.isAlive())
    }

    @Test
    fun `ping returns true when server answers`() {
        val s = session(FakeMcpProcess(mcpHandshakeResponders()))
        assertTrue(s.start(emptyMap()))
        assertTrue(s.ping())
    }

    @Test
    fun `ping returns false when server is dead`() {
        val fake = FakeMcpProcess(mcpHandshakeResponders())
        val s = session(fake)
        assertTrue(s.start(emptyMap()))
        fake.hangReads = true
        assertFalse(s.ping())
    }

    @Test
    fun `diagnostics expose stderr lines`() {
        val s = session(FakeMcpProcess(mcpHandshakeResponders()))
        assertTrue(s.start(emptyMap()))
        assertEquals(listOf("fake stderr line 1", "fake stderr line 2"), s.diagnostics())
    }
}
