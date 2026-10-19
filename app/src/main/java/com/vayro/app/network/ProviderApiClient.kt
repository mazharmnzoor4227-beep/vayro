package com.vayro.app.network

import com.vayro.app.model.ProviderProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiscoveredModel(val id: String, val displayName: String = id, val isFree: Boolean = false)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
        /** v3.2.38 (L2): the real HTTP status (0 = no response) so the UI can show it in the small technical line. */
        val httpCode: Int = 0,
        /** v3.2.38 (L): true when the provider rejected the MODEL (dead/unknown model) — the catalog self-corrects on this. */
        val modelError: Boolean = false,
    ) : ConnectionValidation
}

class ProviderApiClient {
    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL first.")
        }

        var authError = false
        var lastMessage = "This provider did not expose a model list. You can enter a custom model name."
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(baseUrl, protocol)) {
            // OpenRouter's complete catalog is public. Fetch it anonymously even when
            // OpenRouter is configured through Custom API so an account-scoped key does
            // not reduce discovery to the models allowed by that key's preferences.
            // The saved key is still used for validation and all inference requests.
            val discoveryKey = if (isOpenRouterCatalogEndpoint(endpoint)) "" else apiKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = "The provider replied, but its model list was empty or unsupported."
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) "Check the saved API key, then try refreshing again." else lastMessage,
            lastProviderMessage,
        )
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        discoveredModels: List<DiscoveredModel>,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = validationBody(model, protocol, openRouterProviderOrder, openRouterAllowFallbacks)
        // Gateways may need to cold-start a model before returning the first token.
        // A ten-second validation timeout produced false "network" failures even
        // though discovery and the endpoint itself were healthy.
        val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 12_000, readTimeoutMs = 45_000)
        val providerMessage = providerErrorMessage(response.body)
        // v3.2.38 (L2): the old classifier blamed "the Base URL and selected
        // gateway protocol" for EVERY 404 — but Groq, Cerebras, xAI, Google
        // and OpenAI all report a dead or unknown MODEL as HTTP 404 too, so
        // one stale default made every provider look like the same URL
        // problem. Model errors are detected from the provider's own body
        // first and classified as what they are; every failure now carries
        // the real HTTP status for the technical line.
        val modelError = isModelError(response.code, response.body)
        when {
            // v3.2.38 (J): some gateways answer HTTP 200 with an error body.
            // A "success" whose body carries an error and no reply content
            // is not a success — classify it by the provider's own words.
            response.code in 200..299 && providerMessage != null &&
                !response.body.contains("\"choices\"") &&
                !response.body.contains("\"content\"") &&
                !response.body.contains("\"output\"") -> ConnectionValidation.Failure(
                "The provider replied with an error instead of a test answer.",
                providerMessage,
                "Provider error",
                response.code,
            )
            response.code in 200..299 -> ConnectionValidation.Success(
                if (protocol == ProviderProtocol.ANTHROPIC || protocol == ProviderProtocol.ANTHROPIC_GATEWAY || protocol == ProviderProtocol.OPENROUTER) {
                    "Anthropic Messages endpoint verified. Claude Code settings are ready."
                } else {
                    "Connection successful. Claude Code settings are ready."
                },
            )
            response.code == 401 || response.code == 403 -> ConnectionValidation.Failure(
                "This key was rejected. Check the key and try again.",
                providerMessage,
                "Rejected",
                response.code,
            )
            modelError -> ConnectionValidation.Failure(
                "That model is not available on this provider.",
                providerMessage,
                "Model error",
                response.code,
                modelError = true,
            )
            response.code == 404 -> ConnectionValidation.Failure(
                "The provider could not find that endpoint. If you changed the Base URL in Advanced, check it there.",
                providerMessage,
                "Endpoint error",
                response.code,
            )
            response.code == 429 -> ConnectionValidation.Failure(
                "Rate limited — wait a moment, then try again.",
                providerMessage,
                "Rate limited",
                response.code,
            )
            response.code in 500..599 -> ConnectionValidation.Failure(
                "The provider is having a problem right now. Try again shortly.",
                providerMessage,
                "Provider error",
                response.code,
            )
            response.code > 0 -> ConnectionValidation.Failure(
                "The provider could not complete that request.",
                providerMessage,
                "Request failed",
                response.code,
            )
            response.error?.contains("timeout", ignoreCase = true) == true ||
                response.error?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure("Network problem — check your connection and try again.", response.error, "Timed out")
            else -> ConnectionValidation.Failure(
                "Network problem — check your connection and try again.",
                response.error,
                "Network error",
            )
        }
    }

    /**
     * v3.2.38 (L2): a model-class failure. Providers report a dead/unknown
     * model as 400 (Anthropic-style) or 404 (OpenAI-compatible style) with
     * the word "model" plus a not-found / no-access phrase in the body.
     */
    private fun isModelError(code: Int, body: String): Boolean {
        if (code != 400 && code != 404) return false
        val b = body.lowercase()
        if (!b.contains("model")) return false
        if (code == 400) return true
        return b.contains("not_found") || b.contains("not found") ||
            b.contains("does not exist") || b.contains("not exist") ||
            b.contains("no access") || b.contains("not available") ||
            b.contains("not supported") || b.contains("invalid model")
    }

    /** One turn of a plain chat conversation for [chatCompletion]. */
    data class ChatTurn(val role: String, val content: String)

    /**
     * v3.2.37 (Part F2): one real chat completion against any catalog
     * provider, in that provider's own wire shape. Returns the reply text;
     * throws [ChatApiException] carrying the HTTP code so callers can run
     * key failover (the shared [com.vayro.app.data.ApiKeyFailoverCoordinator]
     * classifies quota/rate/auth failures from the code + message).
     */
    suspend fun chatCompletion(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        turns: List<ChatTurn>,
        systemPrompt: String? = null,
        maxTokens: Int = 2048,
    ): String = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            throw ChatApiException(0, "This provider is not fully set up yet.", null)
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = chatBody(model, protocol, turns, systemPrompt, maxTokens)
        val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 15_000, readTimeoutMs = 90_000)
        when {
            response.code in 200..299 -> {
                val text = parseChatText(response.body, protocol)
                if (text.isBlank()) throw ChatApiException(0, "The provider replied with an empty message.", null)
                text
            }
            response.code > 0 -> throw ChatApiException(
                response.code,
                friendlyHttpError(response.code),
                providerErrorMessage(response.body),
            )
            else -> throw ChatApiException(0, response.error ?: "Network connection failed", null)
        }
    }

    private fun chatBody(
        model: String,
        protocol: ProviderProtocol,
        turns: List<ChatTurn>,
        systemPrompt: String?,
        maxTokens: Int,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put("max_output_tokens", maxTokens)
            .put(
                "input",
                JSONArray().apply {
                    if (!systemPrompt.isNullOrBlank()) {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                    }
                    turns.forEach { turn ->
                        put(JSONObject().put("role", turn.role).put("content", turn.content))
                    }
                },
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray().apply {
                    if (!systemPrompt.isNullOrBlank()) {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                    }
                    turns.forEach { turn ->
                        put(JSONObject().put("role", turn.role).put("content", turn.content))
                    }
                },
            )
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .apply {
                if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
                put(
                    "messages",
                    JSONArray().apply {
                        turns.forEach { turn ->
                            put(JSONObject().put("role", turn.role).put("content", turn.content))
                        }
                    },
                )
            }
            .toString()
    }

    private fun parseChatText(body: String, protocol: ProviderProtocol): String = runCatching {
        val root = JSONObject(body)
        when (protocol) {
            ProviderProtocol.OPENAI_CHAT -> {
                val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                when (val content = message?.opt("content")) {
                    is String -> content
                    is JSONArray -> buildString {
                        for (i in 0 until content.length()) {
                            val part = content.optJSONObject(i) ?: continue
                            append(part.optString("text"))
                        }
                    }
                    else -> ""
                }
            }
            ProviderProtocol.OPENAI_RESPONSES -> {
                root.optString("output_text").ifBlank {
                    buildString {
                        val output = root.optJSONArray("output") ?: JSONArray()
                        for (i in 0 until output.length()) {
                            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                            for (j in 0 until content.length()) {
                                append(content.optJSONObject(j)?.optString("text").orEmpty())
                            }
                        }
                    }
                }
            }
            else -> buildString {
                val content = root.optJSONArray("content") ?: JSONArray()
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text"))
                }
            }
        }
    }.getOrDefault("")

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        // QA Medium: one transient 429/5xx used to be final. Retry with
        // backoff, honoring the server's Retry-After header.
        var attempt = 0
        while (true) {
            val result = runCatching {
                attemptRequest(endpoint, method, apiKey, body, protocol, connectTimeoutMs, readTimeoutMs)
            }.getOrElse {
                return HttpResult(0, "", it.message ?: "Network connection failed")
            }
            if (!HttpRetry.isRetryable(result.code) || attempt >= HttpRetry.MAX_ATTEMPTS - 1) {
                return HttpResult(result.code, result.body)
            }
            HttpRetry.sleepBeforeRetry(attempt, result.retryAfter)
            attempt++
        }
    }

    private data class AttemptResult(val code: Int, val body: String, val retryAfter: String?)

    private fun attemptRequest(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String?,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): AttemptResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            if (endpoint.startsWith("https://opencode.ai/zen/")) {
                // OpenCode Zen expects requests to identify the OpenCode client and session.
                setRequestProperty("User-Agent", "opencode/1.18.20")
                setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
            }
            if (apiKey.isNotBlank() && protocol != ProviderProtocol.OPENROUTER && protocol != ProviderProtocol.OPENAI_CHAT && protocol != ProviderProtocol.OPENAI_RESPONSES) {
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            if (body != null) doOutput = true
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.use { readCappedBody(it) }.orEmpty()
        val retryAfter = connection.getHeaderField("Retry-After")
        connection.disconnect()
        return AttemptResult(code, responseBody, retryAfter)
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$base/v1/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> listOf("$base/models")
            else -> listOf("$base/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    private fun messagesEndpoint(baseUrl: String, protocol: ProviderProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$base/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "$base/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "$base/responses"
            else -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }
    }

    private fun isOpenRouterCatalogEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.host.equals("openrouter.ai", ignoreCase = true) &&
            url.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", 1)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", 1)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
        else -> "The provider returned HTTP $code. Check the URL and account access."
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
                is String -> error
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)

    private fun readCappedBody(stream: java.io.InputStream): String {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        var total = 0
        while (total < MAX_RESPONSE_CHARS) {
            val read = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - total))
            if (read <= 0) break
            sb.append(buffer, 0, read)
            total += read
        }
        return sb.toString()
    }

    companion object {
        const val MAX_RESPONSE_CHARS = 10 * 1024 * 1024
    }
}

/** A chat call failed; [httpCode] 0 means the request never got a response. */
class ChatApiException(
    val httpCode: Int,
    message: String,
    val providerMessage: String? = null,
) : Exception(message)

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name").ifBlank { item.optString("displayName") }.ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())
}
