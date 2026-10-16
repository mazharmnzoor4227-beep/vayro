package com.vayro.app.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun rpcOk(id: Any?, result: JSONObject): String =
    JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

private fun rpcError(id: Any?, code: Int, message: String): String =
    JSONObject().put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message)).toString()

/** Scripted HTTP layer: inspects the outgoing request, returns a canned response. */
private class FakeHttpLayer(
    var handler: (url: String, headers: Map<String, String>, body: String) -> McpHttpResponse
) : McpHttpLayer {
    var lastUrl: String? = null
    var lastHeaders: Map<String, String>? = null
    var lastBody: String? = null
    var calls = 0

    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): McpHttpResponse {
        calls++
        lastUrl = url
        lastHeaders = headers
        lastBody = body
        return handler(url, headers, body)
    }
}

private fun toolsListBody(id: Any?): String = rpcOk(
    id,
    JSONObject().put(
        "tools",
        org.json.JSONArray().put(
            JSONObject().put("name", "search").put("description", "Web search")
        )
    )
)

class McpRemoteTransportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var credentialStore: McpCredentialStore
    private lateinit var http: FakeHttpLayer

    private fun remoteServer(id: String = "remote1"): McpServerInfo =
        McpServerInfo(
            id = id,
            name = "Remote",
            command = "",
            transport = McpTransportType.STREAMABLE_HTTP,
            remoteUrl = "https://mcp.example.com/rpc"
        )

    @Before
    fun setUp() {
        credentialStore = McpCredentialStore(tempFolder.newFolder("creds"), InMemoryCredentialBackend())
        http = FakeHttpLayer { _, _, _ -> McpHttpResponse(200, "{}") }
    }

    private fun client(
        authType: McpAuthType = McpAuthType.NONE,
        credentialName: String? = null,
        apiKeyHeaderName: String = "X-API-Key"
    ): McpRemoteClient {
        val server = remoteServer()
        return McpRemoteClient(
            server,
            McpRemoteConfig(
                url = "https://mcp.example.com/rpc",
                authType = authType,
                credentialName = credentialName,
                apiKeyHeaderName = apiKeyHeaderName
            ),
            credentialStore,
            http,
            timeoutMs = 5_000
        )
    }

    @Test
    fun `listTools parses tools from plain json body`() {
        http.handler = { _, _, body -> McpHttpResponse(200, toolsListBody(JSONObject(body).opt("id"))) }
        val tools = client().listTools()
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
        assertEquals("https://mcp.example.com/rpc", http.lastUrl)
    }

    @Test
    fun `listTools parses tools from sse stream body`() {
        http.handler = { _, _, body ->
            val sse = "event: message\ndata: ${toolsListBody(JSONObject(body).opt("id"))}\n\n"
            McpHttpResponse(200, sse, "text/event-stream")
        }
        val tools = client().listTools()
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
    }

    @Test
    fun `plain json containing data colon is not treated as sse`() {
        http.handler = { _, _, body ->
            val id = JSONObject(body).opt("id")
            val plain = rpcOk(
                id,
                JSONObject().put(
                    "tools",
                    org.json.JSONArray().put(
                        JSONObject().put("name", "search").put("description", "matches data: patterns")
                    )
                )
            )
            McpHttpResponse(200, plain, "application/json")
        }
        val tools = client().listTools()
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
    }

    @Test
    fun `sse without content type is detected from data line`() {
        http.handler = { _, _, body ->
            val sse = "data: ${toolsListBody(JSONObject(body).opt("id"))}\n\n"
            McpHttpResponse(200, sse, "application/json")
        }
        val tools = client().listTools()
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
    }

    @Test
    fun `callTool returns text outcome`() {
        http.handler = { _, _, body ->
            val id = JSONObject(body).opt("id")
            val result = JSONObject().put(
                "content",
                org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "remote-ok"))
            )
            McpHttpResponse(200, rpcOk(id, result))
        }
        val outcome = client().callTool("search", JSONObject().put("q", "x"))
        assertFalse(outcome.isError)
        assertEquals("remote-ok", outcome.text)
        assertEquals("tools/call", JSONObject(http.lastBody!!).getString("method"))
    }

    @Test
    fun `rpc error surfaces as McpRpcException`() {
        http.handler = { _, _, body -> McpHttpResponse(200, rpcError(JSONObject(body).opt("id"), -32601, "nope")) }
        try {
            client().listTools()
            fail("expected McpRpcException")
        } catch (e: McpRpcException) {
            assertEquals(-32601, e.code)
        }
    }

    @Test
    fun `http 401 surfaces as McpAuthException`() {
        http.handler = { _, _, _ -> McpHttpResponse(401, "unauthorized") }
        try {
            client().listTools()
            fail("expected McpAuthException")
        } catch (e: McpAuthException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `http 500 surfaces as McpTransportException`() {
        http.handler = { _, _, _ -> McpHttpResponse(500, "boom") }
        try {
            client().listTools()
            fail("expected McpTransportException")
        } catch (e: McpTransportException) {
            assertTrue(e.message!!.contains("500"))
        }
    }

    @Test
    fun `network failure surfaces as McpTransportException`() {
        val throwing = object : McpHttpLayer {
            override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): McpHttpResponse {
                throw java.io.IOException("connection refused")
            }
        }
        val c = McpRemoteClient(
            remoteServer(), McpRemoteConfig("https://mcp.example.com/rpc"),
            credentialStore, throwing, 5_000
        )
        try {
            c.listTools()
            fail("expected McpTransportException")
        } catch (e: McpTransportException) {
            assertTrue(e.message!!.contains("failed"))
        }
    }

    @Test
    fun `bearer auth injects authorization header from credential store`() {
        credentialStore.setCredential("remote1", "bearer_token", "tok-abc")
        http.handler = { _, _, body -> McpHttpResponse(200, toolsListBody(JSONObject(body).opt("id"))) }
        client(authType = McpAuthType.BEARER).listTools()
        assertEquals("Bearer tok-abc", http.lastHeaders!!["Authorization"])
    }

    @Test
    fun `missing bearer credential fails before any http call`() {
        try {
            client(authType = McpAuthType.BEARER).listTools()
            fail("expected McpAuthException")
        } catch (e: McpAuthException) {
            assertTrue(e.message!!.contains("Missing bearer credential"))
        }
        assertEquals(0, http.calls)
    }

    @Test
    fun `api key auth uses custom header name`() {
        credentialStore.setCredential("remote1", "my_key", "key-xyz")
        http.handler = { _, _, body -> McpHttpResponse(200, toolsListBody(JSONObject(body).opt("id"))) }
        client(authType = McpAuthType.API_KEY_HEADER, credentialName = "my_key", apiKeyHeaderName = "X-Custom-Key").listTools()
        assertEquals("key-xyz", http.lastHeaders!!["X-Custom-Key"])
    }

    @Test
    fun `basic auth encodes user colon password`() {
        credentialStore.setCredential("remote1", "basic_auth", "user:pass")
        http.handler = { _, _, body -> McpHttpResponse(200, toolsListBody(JSONObject(body).opt("id"))) }
        client(authType = McpAuthType.BASIC).listTools()
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("user:pass".toByteArray())
        assertEquals(expected, http.lastHeaders!!["Authorization"])
    }

    @Test
    fun `blank url is rejected`() {
        try {
            McpRemoteClient(
                remoteServer(),
                McpRemoteConfig(""),
                credentialStore, http
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `non http url is rejected`() {
        try {
            McpRemoteClient(
                remoteServer(),
                McpRemoteConfig("ftp://mcp.example.com/rpc"),
                credentialStore, http
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `plain http to a remote host is rejected`() {
        try {
            McpRemoteClient(
                remoteServer(),
                McpRemoteConfig("http://mcp.example.com/rpc"),
                credentialStore, http
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun `https and loopback http are accepted`() {
        assertTrue(isHttpsOrLoopbackHttp("https://mcp.example.com/rpc"))
        assertTrue(isHttpsOrLoopbackHttp("http://localhost:8080/rpc"))
        assertTrue(isHttpsOrLoopbackHttp("http://127.0.0.1:8080/rpc"))
        assertFalse(isHttpsOrLoopbackHttp("http://mcp.example.com/rpc"))
        assertFalse(isHttpsOrLoopbackHttp("ftp://mcp.example.com/rpc"))
        McpRemoteClient(
            remoteServer(),
            McpRemoteConfig("http://127.0.0.1:8080/rpc"),
            credentialStore, http
        )
    }

    @Test
    fun `ping returns false on failure and true on success`() {
        http.handler = { _, _, body -> McpHttpResponse(200, rpcOk(JSONObject(body).opt("id"), JSONObject())) }
        assertTrue(client().ping())
        http.handler = { _, _, _ -> McpHttpResponse(500, "x") }
        assertFalse(client().ping())
    }
}
