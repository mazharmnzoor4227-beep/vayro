package com.vayro.app.model

import java.util.UUID

/** Wire protocol for a user-defined custom provider. */
enum class CustomApiType(
    val title: String,
    val description: String,
) {
    OPENAI_COMPATIBLE(
        "OpenAI-compatible",
        "Chat Completions API (/v1/chat/completions, /v1/models)",
    ),
    CLAUDE_COMPATIBLE(
        "Claude-compatible",
        "Anthropic Messages API (/v1/messages, /v1/models)",
    ),
}

/**
 * A user-defined third-party API provider (OpenRouter, token routers, freemodel.dev,
 * any OpenAI- or Claude-compatible proxy).
 *
 * Multiple configs can be saved; each gets its own API keys in [com.vayro.app.data.ApiKeyVault]
 * under the key "CUSTOM_$id", so multi-key auto-failover works per custom provider.
 */
data class CustomProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    /** User-chosen label, e.g. "My OpenRouter". */
    val displayName: String = "",
    /** Base URL, e.g. https://openrouter.ai/api/v1 */
    val baseUrl: String = "",
    /** Exact model ID at the endpoint, e.g. "anthropic/claude-3.5-sonnet". */
    val modelId: String = "",
    /** Friendly name shown in the model list. */
    val modelDisplayName: String = "",
    val apiType: CustomApiType = CustomApiType.OPENAI_COMPATIBLE,
    /** User-overridden context limit (tokens). 0 = use auto-detected value. */
    val contextLimitOverride: Int = 0,
    /** Auto-detected capabilities (populated by ModelCapabilityDetector). */
    val detectedSupportsVision: Boolean = false,
    val detectedContextLimit: Int = 0,
    val detectedAtMillis: Long = 0L,
    val detectionNote: String = "",
    val isActive: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    /** Effective context limit: user override wins, then detected, then 0 (unknown). */
    val effectiveContextLimit: Int
        get() = when {
            contextLimitOverride > 0 -> contextLimitOverride
            detectedContextLimit > 0 -> detectedContextLimit
            else -> 0
        }

    val hasDetection: Boolean get() = detectedAtMillis > 0L

    /** Human-readable capability summary, e.g. "Text + Vision · 200K context". */
    fun capabilitySummary(): String {
        val modality = if (detectedSupportsVision) "Text + Vision" else "Text-only"
        val ctx = effectiveContextLimit
        val ctxText = if (ctx > 0) "${formatTokens(ctx)} context" else "context unknown"
        return "$modality · $ctxText"
    }

    companion object {
        fun formatTokens(tokens: Int): String = when {
            tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
            tokens >= 1_000 -> "${tokens / 1_000}K"
            else -> "$tokens"
        }
    }
}

/** Result of probing a custom endpoint for model capabilities. */
data class DetectedModelCapabilities(
    val supportsVision: Boolean,
    val contextLimit: Int,
    /** Raw note for the UI, e.g. which endpoint field the data came from. */
    val note: String = "",
)

/** Serializes a [CustomProviderConfig] to JSON for SharedPreferences storage. */
fun CustomProviderConfig.toJson(): org.json.JSONObject = org.json.JSONObject()
    .put("id", id)
    .put("displayName", displayName)
    .put("baseUrl", baseUrl)
    .put("modelId", modelId)
    .put("modelDisplayName", modelDisplayName)
    .put("apiType", apiType.name)
    .put("contextLimitOverride", contextLimitOverride)
    .put("detectedSupportsVision", detectedSupportsVision)
    .put("detectedContextLimit", detectedContextLimit)
    .put("detectedAtMillis", detectedAtMillis)
    .put("detectionNote", detectionNote)
    .put("isActive", isActive)
    .put("createdAtMillis", createdAtMillis)

fun customProviderFromJson(obj: org.json.JSONObject): CustomProviderConfig = CustomProviderConfig(
    id = obj.optString("id", UUID.randomUUID().toString()),
    displayName = obj.optString("displayName", ""),
    baseUrl = obj.optString("baseUrl", ""),
    modelId = obj.optString("modelId", ""),
    modelDisplayName = obj.optString("modelDisplayName", ""),
    apiType = runCatching { CustomApiType.valueOf(obj.optString("apiType", "OPENAI_COMPATIBLE")) }
        .getOrDefault(CustomApiType.OPENAI_COMPATIBLE),
    contextLimitOverride = obj.optInt("contextLimitOverride", 0),
    detectedSupportsVision = obj.optBoolean("detectedSupportsVision", false),
    detectedContextLimit = obj.optInt("detectedContextLimit", 0),
    detectedAtMillis = obj.optLong("detectedAtMillis", 0L),
    detectionNote = obj.optString("detectionNote", ""),
    isActive = obj.optBoolean("isActive", false),
    createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
)
