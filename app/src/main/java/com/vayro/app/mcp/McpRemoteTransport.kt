package com.vayro.app.mcp

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Authentication scheme for a remote MCP server. */
enum class McpAuthType {
    NONE,
    BEARER,
    API_KEY_HEADER,
    BASIC
}

/** Remote endpoint configuration for SSE / Streamable HTTP MCP servers. */
data class McpRemoteConfig(
    val url: String,
    val authType: McpAuthType = McpAuthType.NONE,
    /** Name of the credential in [McpCredentialStore] (serverId-scoped). Defaults per scheme. */
    val credentialName: String? = null,
    /** Header carrying the key for API_KEY_HEADER. */
    val apiKeyHeaderName: String = "X-API-Key"
)

/** Minimal HTTP response for the injectable HTTP layer. */
data class McpHttpResponse(
    val statusCode: Int,
    val body: String,
    val contentType: String? = null
)

/** Test seam around HTTP so unit tests never touch the network. */
interface McpHttpLayer {
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): McpHttpResponse
}

/** Production HTTP layer over HttpURLConnection. */
class UrlConnectionMcpHttpLayer : McpHttpLayer {
    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long
    ): McpHttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs.toInt().coerceAtMost(Int.MAX_VALUE)
            conn.readTimeout = timeoutMs.toInt().coerceAtMost(Int.MAX_VALUE)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val responseBody = if (stream != null) {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            } else {
                ""
            }
            return McpHttpResponse(status, responseBody, conn.getHeaderField("Content-Type"))
        } finally {
            conn.disconnect()
        }
    }
}

/** Remote MCP operations behind a test seam. */
interface McpRemoteOperations {
    fun listTools(): List<McpToolDefinition>
    fun callTool(toolName: String, arguments: JSONObject): McpToolCallOutcome
    fun ping(): Boolean
}

/**
 * JSON-RPC client for remote MCP servers (SSE and Streamable HTTP transports).
 *
 * Authentication material always comes from [McpCredentialStore] — never from
 * the server registry JSON. 401/403 responses surface as [McpAuthException];
 * other failures as [McpTransportException]; server-side RPC errors as [McpRpcException].
 */
class McpRemoteClient(
    private val server: McpServerInfo,
    private val remoteConfig: McpRemoteConfig,
    private val credentialStore: McpCredentialStore,
    private val httpLayer: McpHttpLayer = UrlConnectionMcpHttpLayer(),
    private val timeoutMs: Long = 20_000
) : McpRemoteOperations {
    init {
        require(remoteConfig.url.isNotBlank()) { "Remote MCP URL cannot be blank" }
        require(isHttpsOrLoopbackHttp(remoteConfig.url)) {
            "Remote MCP URL must use https — bearer credentials cannot go over plain http " +
                "(loopback http is allowed for local development): ${remoteConfig.url}"
        }
    }

    override fun listTools(): List<McpToolDefinition> {
        val result = rpc(McpProtocol.toolsListRequest())
        return McpProtocol.parseToolsList(result)
    }

    override fun callTool(toolName: String, arguments: JSONObject): McpToolCallOutcome {
        val result = rpc(McpProtocol.toolsCallRequest(toolName, arguments))
        return McpProtocol.parseToolsCallResult(result)
    }

    override fun ping(): Boolean {
        return try {
            rpc(McpProtocol.pingRequest())
            true
        } catch (t: Exception) {
            false
        }
    }

    /** Builds the Authorization / API-key headers for the configured scheme. */
    fun authHeaders(): Map<String, String> {
        return when (remoteConfig.authType) {
            McpAuthType.NONE -> emptyMap()
            McpAuthType.BEARER -> {
                val token = credentialStore.getCredential(
                    server.id,
                    remoteConfig.credentialName ?: "bearer_token"
                ) ?: throw McpAuthException(
                    "Missing bearer credential for MCP server '${server.id}'"
                )
                mapOf("Authorization" to "Bearer $token")
            }
            McpAuthType.API_KEY_HEADER -> {
                val key = credentialStore.getCredential(
                    server.id,
                    remoteConfig.credentialName ?: "api_key"
                ) ?: throw McpAuthException(
                    "Missing API key credential for MCP server '${server.id}'"
                )
                mapOf(remoteConfig.apiKeyHeaderName to key)
            }
            McpAuthType.BASIC -> {
                val pair = credentialStore.getCredential(
                    server.id,
                    remoteConfig.credentialName ?: "basic_auth"
                ) ?: throw McpAuthException(
                    "Missing basic-auth credential (user:password) for MCP server '${server.id}'"
                )
                val encoded = java.util.Base64.getEncoder()
                    .encodeToString(pair.toByteArray(Charsets.UTF_8))
                mapOf("Authorization" to "Basic $encoded")
            }
        }
    }

    private fun rpc(req: JSONObject): JSONObject {
        val headers = authHeaders()
        val response = try {
            httpLayer.post(remoteConfig.url, headers, req.toString(), timeoutMs)
        } catch (t: Exception) {
            throw McpTransportException(
                "HTTP request to MCP server '${server.id}' failed: ${t.message}", t
            )
        }
        when (response.statusCode) {
            401, 403 -> throw McpAuthException(
                "MCP server '${server.id}' rejected authentication (HTTP ${response.statusCode})"
            )
        }
        if (response.statusCode !in 200..299) {
            throw McpTransportException(
                "MCP server '${server.id}' returned HTTP ${response.statusCode}: " +
                    response.body.take(300)
            )
        }
        val payload = extractJsonPayload(response.body, response.contentType)
            ?: throw McpProtocolException(
                "MCP server '${server.id}' returned no JSON-RPC payload"
            )
        val json = runCatching { JSONObject(payload) }.getOrElse {
            throw McpProtocolException(
                "MCP server '${server.id}' returned non-JSON: ${payload.take(200)}"
            )
        }
        return when (val parsed = McpProtocol.parseResponse(json)) {
            is McpRpcResult.Success -> parsed.result
            is McpRpcResult.Failure -> throw McpRpcException(parsed.code, parsed.message)
        }
    }

    /**
     * Accepts either a plain JSON body (Streamable HTTP) or an SSE stream
     * (first data: line). Returns the JSON-RPC payload or null.
     */
    private fun extractJsonPayload(body: String, contentType: String?): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (contentType?.contains("text/event-stream") == true || hasSseDataLine(trimmed)) {
            return McpProtocol.firstSsePayload(body)
        }
        return trimmed
    }

    private fun hasSseDataLine(body: String): Boolean =
        body.lineSequence().any { it.trim().startsWith("data:") }
}

/**
 * Remote MCP endpoints must use https so bearer credentials are never sent
 * in the clear. Plain http is tolerated only for loopback, where there is
 * no network to snoop on (local dev servers rarely have TLS).
 */
internal fun isHttpsOrLoopbackHttp(url: String): Boolean {
    if (url.startsWith("https://")) return true
    if (!url.startsWith("http://")) return false
    val host = runCatching { java.net.URL(url).host.lowercase() }.getOrNull() ?: return false
    return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
}
