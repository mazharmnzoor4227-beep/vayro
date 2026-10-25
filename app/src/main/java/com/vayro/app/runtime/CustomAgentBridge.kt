package com.vayro.app.runtime

import com.vayro.app.network.HttpRetry
import com.vayro.app.model.ChangeItem
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.RuntimeEvent
import com.vayro.app.model.ToolRequest
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bring-your-own-API agent: talks directly to an OpenAI-compatible
 * (`/chat/completions`) or Claude-compatible (`/messages`) endpoint using the
 * user's own API key. No binary install, no runtime updates.
 *
 * This is direct API chat — it answers via the configured model but does not
 * edit files or run tools. For full agentic code execution, install one of the
 * harness agents (Claude Code, DeepSeek, Antigravity).
 */
class CustomAgentBridge(
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus

    private enum class ApiType { OPENAI, ANTHROPIC }

    // QA Medium: stopSession used to be a no-op — a streaming request
    // could not be cancelled and kept reading until the server closed or
    // the 120s timeout. Active connections are now tracked per session
    // so stop disconnects the socket immediately.
    private val activeConnections = java.util.concurrent.ConcurrentHashMap<String, java.net.HttpURLConnection>()
    private val stoppedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
        clientSessionId: String?,
        chatId: String?,
    ): String = withContext(Dispatchers.IO) {
        val sessionId = clientSessionId ?: UUID.randomUUID().toString()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        val secret = secretFor(provider).orEmpty()
        if (secret.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No API key is saved. Add your key in Agent → Custom.",
                ),
            )
            return@withContext sessionId
        }
        val baseUrl = provider.resolvedBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No API endpoint is configured. Set the base URL in Agent → Custom.",
                ),
            )
            return@withContext sessionId
        }
        val model = provider.model.trim()
        if (model.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No model is configured. Set the model in Agent → Custom.",
                ),
            )
            return@withContext sessionId
        }
        try {
            // Streaming: deltas are emitted live as they arrive so the chat shows
            // the reply growing instead of one silent "deciding" line until the
            // whole response is done. Endpoints that ignore stream=true fall back
            // to a single full response, which is then emitted chunked as before.
            val (text, streamed) = when (resolveApiType(provider)) {
                ApiType.ANTHROPIC -> callAnthropic(baseUrl, secret, model, prompt, conversationHistory, sessionId)
                ApiType.OPENAI -> callOpenAi(baseUrl, secret, model, prompt, conversationHistory, sessionId)
            }
            if (text.isBlank()) {
                eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "The API returned an empty response."))
            } else {
                if (!streamed) {
                    text.chunked(600).forEach { eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it)) }
                }
                eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            }
        } catch (e: Exception) {
            // A session stopped by the user is completed, not failed: the
            // disconnect below breaks the read loop with a SocketException.
            if (stoppedSessions.remove(sessionId)) {
                eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            } else {
                eventBus.emit(
                    RuntimeEvent.SessionFailed(
                        sessionId,
                        "Custom API error: ${e.message?.take(200) ?: "unknown error"}",
                    ),
                )
            }
        }
        sessionId
    }

    private fun resolveApiType(provider: ProviderProfile): ApiType = when (provider.kind) {
        ProviderKind.ANTHROPIC -> ApiType.ANTHROPIC
        // OpenRouter and other OpenAI-protocol providers use chat completions.
        ProviderKind.LLM_ROUTER -> ApiType.OPENAI
        // Custom API: the API type is stored in dshApi ("anthropic-messages" | "openai-completions").
        else -> if (provider.dshApi == "anthropic-messages") ApiType.ANTHROPIC else ApiType.OPENAI
    }

    private fun historyMessages(history: List<ChatMessage>): JSONArray {
        val messages = JSONArray()
        history.takeLast(20).forEach { message ->
            if (message.text.isNotBlank()) {
                messages.put(
                    JSONObject()
                        .put("role", if (message.fromUser) "user" else "assistant")
                        .put("content", message.text),
                )
            }
        }
        return messages
    }

    private fun callOpenAi(
        baseUrl: String,
        secret: String,
        model: String,
        prompt: String,
        history: List<ChatMessage>,
        sessionId: String,
    ): Pair<String, Boolean> {
        val messages = historyMessages(history)
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 4096)
            .put("stream", true)
            .toString()
        val (response, streamed) = postStreaming(
            "$baseUrl/chat/completions", secret, body, anthropic = false, sessionId = sessionId,
            onDelta = { chunk -> eventBus.tryEmit(RuntimeEvent.AssistantDelta(sessionId, chunk)) },
            extractDelta = { json ->
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta")?.optString("content").orEmpty()
            },
        )
        if (!streamed) {
            val json = JSONObject(response)
            if (json.has("error")) {
                throw IllegalStateException(json.getJSONObject("error").optString("message", "API error"))
            }
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            return content to false
        }
        return response to true
    }

    private fun callAnthropic(
        baseUrl: String,
        secret: String,
        model: String,
        prompt: String,
        history: List<ChatMessage>,
        sessionId: String,
    ): Pair<String, Boolean> {
        val messages = historyMessages(history)
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("messages", messages)
            .put("stream", true)
            .toString()
        val (response, streamed) = postStreaming(
            "$baseUrl/messages", secret, body, anthropic = true, sessionId = sessionId,
            onDelta = { chunk -> eventBus.tryEmit(RuntimeEvent.AssistantDelta(sessionId, chunk)) },
            extractDelta = { json ->
                if (json.optString("type") == "content_block_delta") {
                    json.optJSONObject("delta")?.optString("text").orEmpty()
                } else {
                    ""
                }
            },
        )
        if (!streamed) {
            val json = JSONObject(response)
            if (json.has("error")) {
                val error = json.optJSONObject("error")
                throw IllegalStateException(error?.optString("message", "API error") ?: "API error")
            }
            val content = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") {
                    sb.append(block.optString("text"))
                }
            }
            return sb.toString() to false
        }
        return response to true
    }

    /**
     * POSTs [body] and returns the full response text plus whether it arrived as
     * a live SSE stream. When the server answers with `text/event-stream`, each
     * `data:` chunk is parsed with [extractDelta] and forwarded to [onDelta]
     * immediately so the UI streams. When the server ignores `stream=true` and
     * answers with plain JSON, the whole body is returned with streamed=false
     * and the caller parses it the old way.
     */
    private fun postStreaming(
        url: String,
        secret: String,
        body: String,
        anthropic: Boolean,
        sessionId: String,
        onDelta: (String) -> Unit,
        extractDelta: (JSONObject) -> String,
    ): Pair<String, Boolean> {
        // QA Medium: one transient 429/5xx used to be final. The initial
        // request is retried with backoff before the error path throws.
        var attempt = 0
        while (true) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                if (anthropic) {
                    setRequestProperty("x-api-key", secret)
                    setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    setRequestProperty("Authorization", "Bearer $secret")
                }
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (HttpRetry.isRetryable(code) &&
                    attempt < HttpRetry.MAX_ATTEMPTS - 1
                ) {
                    val retryAfter = conn.getHeaderField("Retry-After")
                    HttpRetry.sleepBeforeRetry(attempt, retryAfter)
                    attempt++
                    continue
                }
                activeConnections[sessionId] = conn
                try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) {
                // QA Medium: errorStream is null when the connection fails
                // before any response exists — reading it NPE'd and lost
                // the HTTP code. A null stream reads as an empty body.
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(friendlyError(response, code).take(200))
            }
            val isEventStream = (conn.contentType ?: "").contains("text/event-stream")
            if (!isEventStream) {
                return stream.bufferedReader().use { it.readText() } to false
            }
            val accumulated = StringBuilder()
            // Runs inside startSession's withContext(Dispatchers.IO), so these
            // blocking reads stay off the main thread.
            val reader = stream.bufferedReader()
            // QA Medium: legal SSE splits one event's JSON across several
            // consecutive `data:` lines — each line alone is not valid
            // JSON and used to be silently dropped. Lines are accumulated
            // per event (blank line = event boundary) and parsed joined.
            val eventData = StringBuilder()
            fun flushEvent() {
                if (eventData.isEmpty()) return
                val payload = eventData.toString()
                eventData.clear()
                if (payload == "[DONE]") return
                val delta = runCatching { extractDelta(JSONObject(payload)) }.getOrDefault("")
                if (delta.isNotEmpty()) {
                    accumulated.append(delta)
                    runCatching { onDelta(delta) }
                }
            }
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    flushEvent()
                    continue
                }
                if (!trimmed.startsWith("data:")) continue
                val payload = trimmed.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (eventData.isNotEmpty()) eventData.append('\n')
                eventData.append(payload)
            }
            flushEvent()
            return accumulated.toString() to true
            } finally {
                activeConnections.remove(sessionId, conn)
            }
        } finally {
            // Disconnects the attempt's connection on every path: the
            // retry-continue above, and the success path (already
            // removed from the map; disconnect is idempotent).
            conn.disconnect()
        }
        }
    }

    private fun friendlyError(response: String, code: Int): String {
        val parsed = runCatching {
            val json = JSONObject(response)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        return parsed ?: "HTTP $code"
    }

    // The Custom agent is direct-API chat: no tools, no file operations.
    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit
    override suspend fun stopSession(sessionId: String) {
        stoppedSessions += sessionId
        // Session ids are UUIDs and never repeat; still, keep the set
        // bounded for long-running processes.
        if (stoppedSessions.size > 200) stoppedSessions.clear()
        activeConnections[sessionId]?.disconnect()
    }
    override suspend fun stopActiveSession() {
        stoppedSessions += activeConnections.keys
        activeConnections.values.forEach { runCatching { it.disconnect() } }
    }
    override suspend fun undoLastChanges(projectId: String): Boolean = false
    override suspend fun acceptLastChanges(projectId: String) = Unit
    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()
    override suspend fun undoFileChange(projectId: String, path: String): Boolean = false
    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = false
}
