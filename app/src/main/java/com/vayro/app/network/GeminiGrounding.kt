package com.vayro.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v3.2.51: one home-chat completion against Google's NATIVE Gemini
 * API with its own web tools — `google_search` grounding and
 * `url_context` — for the GOOGLE_AI_STUDIO kind. The home wire for
 * Google is otherwise the OpenAI-compatible surface, which cannot
 * carry grounding at all; this dedicated call exists so a fresh-info
 * turn on a Google key is answered by Google's own search. The same
 * vault key works as `x-goog-api-key` (the pattern the app's native
 * Gemini client already ships). Failures throw [ChatApiException]
 * with the real HTTP code, exactly like [ProviderApiClient], so the
 * caller can fall back to the app-driven web route and, failing
 * that, the ordinary key/provider failover.
 */
class GeminiGrounding {

    data class GroundedReply(val text: String, val sources: List<String>)

    suspend fun groundedCompletion(
        apiKey: String,
        model: String,
        systemPrompt: String?,
        turns: List<ProviderApiClient.ChatTurn>,
        maxTokens: Int = 2048,
    ): GroundedReply = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || model.isBlank()) {
            throw ChatApiException(0, "This provider is not fully set up yet.", null)
        }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val body = buildGroundedBody(systemPrompt, turns, maxTokens)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(8192)
                var total = 0
                while (total < MAX_RESPONSE_CHARS) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - total))
                    if (read <= 0) break
                    sb.append(buffer, 0, read)
                    total += read
                }
                sb.toString()
            }.orEmpty()
            when {
                code in 200..299 -> {
                    val reply = parseGroundedResponse(responseBody)
                    if (reply.text.isBlank()) {
                        throw ChatApiException(0, "The provider replied with an empty message.", null)
                    }
                    reply
                }
                code > 0 -> throw ChatApiException(
                    code,
                    friendlyHttpError(code),
                    providerErrorMessage(responseBody),
                )
                else -> throw ChatApiException(0, "Network connection failed", null)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** The grounded request shape: contents + system + both web tools. */
    internal fun buildGroundedBody(
        systemPrompt: String?,
        turns: List<ProviderApiClient.ChatTurn>,
        maxTokens: Int,
    ): JSONObject {
        val body = JSONObject()
            .put("contents", geminiContents(turns))
            .put(
                "tools",
                JSONArray()
                    .put(JSONObject().put("google_search", JSONObject()))
                    .put(JSONObject().put("url_context", JSONObject())),
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        if (!systemPrompt.isNullOrBlank()) {
            body.put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
            )
        }
        return body
    }

    /** ChatTurns in Gemini's contents shape; images ride as inline data. */
    internal fun geminiContents(turns: List<ProviderApiClient.ChatTurn>): JSONArray {
        val contents = JSONArray()
        turns.forEach { turn ->
            val parts = JSONArray()
            if (turn.content.isNotBlank()) {
                parts.put(JSONObject().put("text", turn.content))
            }
            turn.images.forEach { image ->
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", image.mimeType).put("data", image.base64),
                    ),
                )
            }
            if (parts.length() > 0) {
                contents.put(
                    JSONObject()
                        .put("role", if (turn.role == "assistant") "model" else "user")
                        .put("parts", parts),
                )
            }
        }
        return contents
    }

    /**
     * Reply text plus the sources Google actually grounded on:
     * grounding chunks (web search) and URL-context metadata (pages
     * it read), in that order, de-duplicated.
     */
    internal fun parseGroundedResponse(body: String): GroundedReply = runCatching {
        val root = JSONObject(body)
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: return@runCatching GroundedReply("", emptyList())
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = buildString {
            for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        val sources = LinkedHashSet<String>()
        val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) {
            for (i in 0 until chunks.length()) {
                val uri = chunks.optJSONObject(i)?.optJSONObject("web")?.optString("uri").orEmpty()
                if (uri.startsWith("http")) sources += uri
            }
        }
        val urlMeta = candidate.optJSONObject("urlContextMetadata")?.optJSONArray("urlMetadata")
        if (urlMeta != null) {
            for (i in 0 until urlMeta.length()) {
                val uri = urlMeta.optJSONObject(i)?.optString("retrievedUrl").orEmpty()
                if (uri.startsWith("http")) sources += uri
            }
        }
        GroundedReply(text, sources.toList())
    }.getOrDefault(GroundedReply("", emptyList()))

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
        else -> "The provider returned HTTP $code. Check the URL and account access."
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        if (extracted.isBlank()) return null
        return extracted.replace(Regex("\\s+"), " ").trim().take(280)
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 10 * 1024 * 1024
    }
}
