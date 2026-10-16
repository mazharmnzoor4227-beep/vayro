package com.vayro.app.network

import com.vayro.app.model.CustomApiType
import com.vayro.app.model.DetectedModelCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Probes a custom provider's `/models` endpoint to auto-detect a model's capabilities.
 *
 * OpenAI-compatible endpoints expose `GET {baseUrl}/models` returning a list with
 * per-model metadata. Anthropic-compatible endpoints expose `GET {baseUrl}/models`
 * (newer API versions) with a similar shape. When metadata is absent, well-known
 * model-id heuristics fill the gaps so the user still gets a useful answer.
 *
 * Network access runs on Dispatchers.IO with tight timeouts; every failure mode
 * returns null instead of throwing so the UI can fall back to manual entry.
 */
object ModelCapabilityDetector {

    /**
     * Detects capabilities for [modelId] at [baseUrl] using [apiKey].
     * Returns null when detection is impossible (network error, unknown shape).
     */
    suspend fun detect(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        apiType: CustomApiType,
    ): DetectedModelCapabilities? = withContext(Dispatchers.IO) {
        val trimmedId = modelId.trim()
        if (trimmedId.isEmpty() || apiKey.isBlank()) return@withContext null
        val modelsUrl = modelsEndpointFor(baseUrl, apiType) ?: return@withContext null
        val payload = runCatching { getJson(modelsUrl, apiKey, apiType) }.getOrNull()
        val fromEndpoint = payload?.let { findModel(it, trimmedId, apiType) }
        if (fromEndpoint != null) return@withContext fromEndpoint
        // Fall back to id heuristics so the user still gets an answer.
        heuristicFor(trimmedId)?.copy(note = "Estimated from model ID (endpoint gave no metadata)")
    }

    private fun modelsEndpointFor(baseUrl: String, apiType: CustomApiType): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        return when (apiType) {
            CustomApiType.OPENAI_COMPATIBLE -> if (base.endsWith("/models")) base else "$base/models"
            CustomApiType.CLAUDE_COMPATIBLE -> if (base.endsWith("/models")) base else "$base/models"
        }
    }

    private fun getJson(url: String, apiKey: String, apiType: CustomApiType): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            when (apiType) {
                CustomApiType.OPENAI_COMPATIBLE ->
                    setRequestProperty("Authorization", "Bearer $apiKey")
                CustomApiType.CLAUDE_COMPATIBLE -> {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // Some Claude-compatible proxies only serve /v1/models under the
                // versioned path; surface the status for the caller's note.
                throw IllegalStateException("HTTP $code from $url")
            }
            val body = connection.inputStream.bufferedReader().readText()
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun findModel(
        payload: JSONObject,
        modelId: String,
        apiType: CustomApiType,
    ): DetectedModelCapabilities? {
        val data = payload.optJSONArray("data") ?: return null
        val normalizedTarget = modelId.lowercase()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id", "")
            if (id.isEmpty()) continue
            // Match exact id, or the trailing segment (OpenRouter-style "org/model").
            val matches = id.equals(modelId, ignoreCase = true) ||
                id.substringAfterLast('/').equals(modelId.substringAfterLast('/'), ignoreCase = true) ||
                normalizedTarget in id.lowercase()
            if (!matches) continue
            return capabilitiesFromMetadata(item, apiType)
        }
        return null
    }

    private fun capabilitiesFromMetadata(
        item: JSONObject,
        apiType: CustomApiType,
    ): DetectedModelCapabilities {
        var vision = false
        var context = 0

        // OpenAI-style fields.
        val modalities = item.optJSONArray("modalities") ?: item.optJSONArray("input_modalities")
        if (modalities != null) {
            for (i in 0 until modalities.length()) {
                val m = modalities.optString(i, "").lowercase()
                if ("image" in m || "vision" in m) vision = true
            }
        }
        // OpenRouter exposes top-level context_length.
        context = item.optInt("context_length", 0)
        if (context <= 0) context = item.optInt("max_input_tokens", 0)
        // Anthropic-style: max_tokens on the model object is output-only; check input types.
        val inputTypes = item.optJSONArray("input_types")
        if (inputTypes != null) {
            for (i in 0 until inputTypes.length()) {
                val t = inputTypes.optJSONObject(i)?.optString("type", "")?.lowercase().orEmpty()
                if ("image" in t) vision = true
            }
        }
        // Some gateways nest capabilities.
        val caps = item.optJSONObject("capabilities")
        if (caps != null) {
            if (caps.optBoolean("vision", false)) vision = true
            if (context <= 0) context = caps.optInt("context_window", 0)
        }

        // Heuristic assist: if the endpoint gave a context window but no modality
        // info, still consult the id heuristics for vision.
        val heuristic = heuristicFor(item.optString("id", ""))
        if (!vision && heuristic?.supportsVision == true) vision = true
        if (context <= 0 && heuristic != null) context = heuristic.contextLimit

        val note = buildString {
            append(if (apiType == CustomApiType.OPENAI_COMPATIBLE) "Detected via /models" else "Detected via Anthropic /models")
            if (context <= 0) append(" (no context metadata)")
        }
        return DetectedModelCapabilities(
            supportsVision = vision,
            contextLimit = context,
            note = note,
        )
    }

    /**
     * Best-effort capability guess from a model ID when the endpoint exposes no
     * metadata. Covers the popular families; unknown IDs return null.
     */
    fun heuristicFor(modelId: String): DetectedModelCapabilities? {
        val id = modelId.lowercase()
        // Vision-capable families.
        val visionFamilies = listOf(
            "claude-3-5-sonnet" to 200_000,
            "claude-3-7-sonnet" to 200_000,
            "claude-sonnet-4" to 200_000,
            "claude-opus-4" to 200_000,
            "gpt-4o" to 128_000,
            "gpt-4-turbo" to 128_000,
            "gpt-5" to 400_000,
            "gemini-2" to 1_000_000,
            "gemini-1.5" to 1_000_000,
            "llama-3.2" to 128_000, // 90B/11B are vision; 1B/3B text fall through harmlessly as vision=false only if unmatched below
            "llama-4-scout" to 10_000_000,
            "llama-4-maverick" to 1_000_000,
            "qwen2.5-vl" to 128_000,
            "qwen3-vl" to 256_000,
            "mistral-large-24" to 128_000,
            "pixtral" to 128_000,
        )
        for ((family, ctx) in visionFamilies) {
            if (family in id) {
                // llama-3.2 1B/3B text models are not vision; only the 11B/90B vision ones are.
                if (family == "llama-3.2" && "vision" !in id) continue
                return DetectedModelCapabilities(true, ctx)
            }
        }
        // Generic fallback: any model id containing "vision" is vision-capable.
        if ("vision" in id) return DetectedModelCapabilities(true, 128_000)
        // Text-only families.
        val textFamilies = listOf(
            "deepseek-chat" to 128_000,
            "deepseek-reasoner" to 128_000,
            "deepseek-r1" to 128_000,
            "deepseek-v3" to 128_000,
            "llama-3.1" to 128_000,
            "llama-3.3" to 128_000,
            "nemotron" to 128_000,
            "qwen2.5-coder" to 128_000,
            "qwen3-coder" to 256_000,
            "kimi-k2" to 256_000,
            "mixtral" to 32_000,
        )
        for ((family, ctx) in textFamilies) {
            if (family in id) return DetectedModelCapabilities(false, ctx)
        }
        return null
    }
}
