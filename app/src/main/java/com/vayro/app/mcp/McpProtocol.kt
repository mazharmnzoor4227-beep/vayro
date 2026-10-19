package com.vayro.app.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP protocol version negotiated during the initialize handshake.
 * Kept as a constant so client and tests agree on the wire value.
 */
object McpProtocolVersion {
    const val PROTOCOL_VERSION = "2024-11-05"
}

/** Thrown when a message is not valid JSON-RPC 2.0 or violates the MCP wire shape. */
class McpProtocolException(message: String) : Exception(message)

/** Thrown when the transport itself fails (process died, HTTP error, timeout). */
class McpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when remote authentication fails or credentials are missing/misconfigured. */
class McpAuthException(message: String) : Exception(message)

/** Thrown when the remote/local server answers a JSON-RPC request with an error object. */
class McpRpcException(val code: Int, message: String) : Exception("MCP RPC error $code: $message")

/** Thrown when a server's environment cannot be resolved (e.g. a required secret is missing). */
open class McpConfigurationException(message: String) : Exception(message)

/** A tool advertised by an MCP server via tools/list. */
data class McpToolDefinition(
    val name: String,
    val description: String = "",
    val inputSchema: JSONObject = JSONObject()
)

/** The text outcome of a tools/call invocation. */
data class McpToolCallOutcome(
    val isError: Boolean,
    val text: String
)

/** Parsed JSON-RPC 2.0 response: either a result or an error. */
sealed class McpRpcResult {
    data class Success(val id: Any?, val result: JSONObject) : McpRpcResult()
    data class Failure(val id: Any?, val code: Int, val message: String, val data: JSONObject?) : McpRpcResult()
}

/**
 * Builders and parsers for the MCP JSON-RPC 2.0 wire protocol.
 * Pure JVM and deterministic; shared by the stdio process manager and the remote HTTP transport.
 */
object McpProtocol {

    private var nextId = 1L

    @Synchronized
    fun nextRequestId(): Long = nextId++

    /** Builds a JSON-RPC 2.0 request object. */
    fun request(method: String, params: JSONObject? = null, id: Any? = nextRequestId()): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    /** Builds a JSON-RPC 2.0 notification (no id, no response expected). */
    fun notification(method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }

    fun initializeRequest(clientName: String, clientVersion: String): JSONObject {
        val params = JSONObject()
            .put("protocolVersion", McpProtocolVersion.PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", clientName).put("version", clientVersion)
            )
        return request("initialize", params)
    }

    fun initializedNotification(): JSONObject = notification("notifications/initialized")

    fun toolsListRequest(cursor: String? = null): JSONObject {
        val params = if (cursor != null) JSONObject().put("cursor", cursor) else null
        return request("tools/list", params)
    }

    fun toolsCallRequest(toolName: String, arguments: JSONObject): JSONObject {
        val params = JSONObject()
            .put("name", toolName)
            .put("arguments", arguments)
        return request("tools/call", params)
    }

    fun pingRequest(): JSONObject = request("ping")

    /**
     * Parses a raw JSON-RPC 2.0 response object.
     * @throws McpProtocolException when the message is not JSON-RPC 2.0 shaped.
     */
    fun parseResponse(json: JSONObject): McpRpcResult {
        if (json.optString("jsonrpc", "") != "2.0") {
            throw McpProtocolException("Not a JSON-RPC 2.0 message: missing or invalid 'jsonrpc' field")
        }
        val id: Any? = if (json.isNull("id")) null else json.opt("id")
        val errorObj = json.optJSONObject("error")
        if (errorObj != null) {
            return McpRpcResult.Failure(
                id = id,
                code = errorObj.optInt("code", -32000),
                message = errorObj.optString("message", "Unknown error"),
                data = errorObj.optJSONObject("data")
            )
        }
        return McpRpcResult.Success(id, json.optJSONObject("result") ?: JSONObject())
    }

    /**
     * Extracts tool definitions from a tools/list result object.
     * Entries without a name are skipped rather than failing the whole list.
     */
    fun parseToolsList(result: JSONObject): List<McpToolDefinition> {
        val tools = result.optJSONArray("tools") ?: return emptyList()
        val out = mutableListOf<McpToolDefinition>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val name = t.optString("name", "")
            if (name.isBlank()) continue
            out.add(
                McpToolDefinition(
                    name = name,
                    description = t.optString("description", ""),
                    inputSchema = t.optJSONObject("inputSchema") ?: JSONObject()
                )
            )
        }
        return out
    }

    /**
     * Extracts the text outcome of a tools/call result object.
     * Non-text content blocks are ignored; an empty content array yields empty text.
     */
    fun parseToolsCallResult(result: JSONObject): McpToolCallOutcome {
        val isError = result.optBoolean("isError", false)
        val texts = mutableListOf<String>()
        val content: JSONArray? = result.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") {
                    texts.add(item.optString("text", ""))
                }
            }
        }
        return McpToolCallOutcome(isError = isError, text = texts.joinToString("\n"))
    }

    /**
     * Parses one Server-Sent Events line. Returns the data payload, or null when the
     * line is not a data line or carries the [DONE] terminator.
     */
    fun parseSseDataLine(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return payload
    }

    /**
     * QA Medium: legal SSE splits one event's JSON across several
     * consecutive `data:` lines — joined with "\n" per the SSE spec, a
     * blank line ends the event. Returns one payload per event, skipping
     * [DONE] terminators. The old per-line parsing returned the first
     * line alone, which is not valid JSON for a split event, so the
     * payload was silently dropped.
     */
    fun parseSseEvents(body: String): List<String> {
        val events = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isEmpty()) return
            val payload = current.toString()
            current.clear()
            if (payload != "[DONE]") events += payload
        }
        for (rawLine in body.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                flush()
                continue
            }
            val payload = parseSseDataLine(rawLine) ?: continue
            if (current.isNotEmpty()) current.append('\n')
            current.append(payload)
        }
        flush()
        return events
    }

    /**
     * Extracts the first JSON-RPC payload from an SSE stream body:
     * the first non-empty data event. Returns null when no data event exists.
     */
    fun firstSsePayload(body: String): String? = parseSseEvents(body).firstOrNull()
}
