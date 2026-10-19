package com.vayro.app.data

import com.vayro.app.model.ProviderKind

/**
 * v3.2.37 (Part C): the "Connect with API key" catalog.
 *
 * Every entry was checked at build time for signup availability from
 * Pakistan (official supported-countries / terms pages): OpenAI's official
 * list and Anthropic's official list both name Pakistan; OpenRouter, Groq,
 * Google AI Studio, DeepSeek, Mistral, Cerebras, Cohere and xAI run global
 * self-serve signup with sanctions-only exclusions (Pakistan is not
 * excluded). NVIDIA NIM is deliberately NOT here — Pakistani phone
 * verification fails there, and the user ordered it removed.
 *
 * A catalog entry is not a parallel system: connecting a provider writes
 * the key into the same encrypted [ApiKeyVault] pool the agent providers
 * use, and the provider becomes selectable through the existing provider
 * system (agent provider pickers, DeepSeek harness, Custom agent) as well
 * as powering home chat (Part F2).
 */
data class CatalogProvider(
    val kind: ProviderKind,
    /** Honest one-line tier note shown on the row. */
    val note: String,
    /** The provider's OFFICIAL key-creation page. */
    val keyPageUrl: String,
    /**
     * v3.2.38 (L): model for the catalog / home-chat path when it must differ
     * from the kind's agent default. OPENAI is the case: the kind default
     * (gpt-5-codex) is a Responses-only model the Codex agent uses, while
     * chat completions need a chat model.
     */
    val chatModel: String? = null,
)

object ProviderCatalog {
    val entries: List<CatalogProvider> = listOf(
        CatalogProvider(
            ProviderKind.LLM_ROUTER,
            "Free models (50/day) · many providers, one key",
            "https://openrouter.ai/keys",
        ),
        CatalogProvider(
            ProviderKind.GROQ,
            "Free tier · very fast replies",
            "https://console.groq.com/keys",
        ),
        CatalogProvider(
            ProviderKind.GOOGLE_AI_STUDIO,
            "Free tier · Gemini models",
            "https://aistudio.google.com/apikey",
        ),
        CatalogProvider(
            ProviderKind.ANTHROPIC,
            "Paid · Claude models",
            "https://console.anthropic.com/settings/keys",
        ),
        CatalogProvider(
            ProviderKind.DEEPSEEK,
            "Paid · low cost",
            "https://platform.deepseek.com/api_keys",
        ),
        CatalogProvider(
            ProviderKind.MISTRAL,
            "Free Experiment plan · phone verification required",
            "https://console.mistral.ai/api-keys",
        ),
        CatalogProvider(
            ProviderKind.CEREBRAS,
            "Trial credit · very fast replies",
            "https://cloud.cerebras.ai/",
        ),
        CatalogProvider(
            ProviderKind.COHERE,
            "Trial key · 1,000 calls/month, not for production",
            "https://dashboard.cohere.com/api-keys",
        ),
        CatalogProvider(
            ProviderKind.OPENAI,
            "Paid · GPT models",
            "https://platform.openai.com/api-keys",
            chatModel = "gpt-5-mini",
        ),
        CatalogProvider(
            ProviderKind.XAI,
            "Paid · Grok models",
            "https://console.x.ai/",
        ),
        // v3.2.38 (Part L3): expansion — each re-verified at build time for
        // a real free tier, Pakistan signup, and an OpenAI-compatible API.
        CatalogProvider(
            ProviderKind.Z_AI,
            "Free tier · GLM Flash models cost \$0",
            "https://z.ai/model-api",
        ),
        CatalogProvider(
            ProviderKind.REQUESTY,
            "Free models · 200 requests/day, no card",
            "https://requesty.ai",
        ),
        CatalogProvider(
            ProviderKind.OLLAMA_CLOUD,
            "Free tier · light usage, few models",
            "https://ollama.com/settings/keys",
        ),
        CatalogProvider(
            ProviderKind.HUGGING_FACE,
            "Free tier · small monthly credit — light use",
            "https://huggingface.co/settings/tokens",
        ),
        CatalogProvider(
            ProviderKind.LLM7,
            "Free token · data policy unpublished — avoid private code",
            "https://dash.llm7.io",
        ),
    )

    /** Providers that can power home chat, in catalog order. */
    val chatKinds: List<ProviderKind> = entries.map { it.kind }

    fun entry(kind: ProviderKind): CatalogProvider? = entries.firstOrNull { it.kind == kind }
}
