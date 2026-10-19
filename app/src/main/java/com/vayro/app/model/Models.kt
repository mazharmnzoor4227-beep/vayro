package com.vayro.app.model

import java.time.Instant
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

enum class ProviderProtocol { CLAUDE_LOGIN, ANTHROPIC, ANTHROPIC_GATEWAY, OPENROUTER, OPENAI_RESPONSES, OPENAI_CHAT }

enum class ProviderKind(
    val title: String,
    val subtitle: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val experimental: Boolean = false,
    val fixedBaseUrl: Boolean = false,
    val fixedProtocol: Boolean = false,
) {
    CLAUDE("Claude subscription", "Pro, Max, Team or Enterprise", ProviderProtocol.CLAUDE_LOGIN, "", "default"),
    ANTHROPIC("Anthropic API", "Usage billed through Console", ProviderProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4-6"),
    LLM_ROUTER("OpenRouter", "Use your OpenRouter API key", ProviderProtocol.OPENROUTER, "https://openrouter.ai/api", "~anthropic/claude-sonnet-latest"),
    DEEPSEEK("DeepSeek", "Use your DeepSeek API key", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.deepseek.com/anthropic", "deepseek-v4-flash"),
    KIMI("Kimi", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.moonshot.ai/anthropic", "kimi-k2.6", true),
    OPENCODE_ZEN(
        "OpenCode Zen",
        "Models through the OpenCode Zen gateway",
        ProviderProtocol.OPENAI_RESPONSES,
        "https://opencode.ai/zen/v1",
        "deepseek-v4-flash",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    NVIDIA_NIM(
        "NVIDIA NIM",
        "OpenAI-compatible models hosted by NVIDIA · free tier available",
        ProviderProtocol.OPENAI_CHAT,
        "https://integrate.api.nvidia.com/v1",
        "nvidia/llama-3.1-nemotron-70b-instruct",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    OPENAI("OpenAI API", "Use your OpenAI API key", ProviderProtocol.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5-codex"),
    CUSTOM("Custom API", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "", "", true),
    // v3.2.37 (Part C) — API-key catalog providers. All are OpenAI-compatible
    // chat endpoints (Google AI Studio via its OpenAI-compatible surface), so
    // one wire protocol covers them; Pakistan availability was verified at
    // build time for every entry here. NVIDIA NIM is deliberately absent
    // from the catalog (Pakistani phone verification fails there).
    GROQ("Groq", "Very fast replies · free tier available", ProviderProtocol.OPENAI_CHAT, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    GOOGLE_AI_STUDIO("Google AI Studio", "Gemini models · free tier available", ProviderProtocol.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    MISTRAL("Mistral AI", "Free tier available", ProviderProtocol.OPENAI_CHAT, "https://api.mistral.ai/v1", "mistral-small-latest"),
    CEREBRAS("Cerebras", "Very fast replies · free tier available", ProviderProtocol.OPENAI_CHAT, "https://api.cerebras.ai/v1", "llama-3.3-70b"),
    COHERE("Cohere", "Free trial tier available", ProviderProtocol.OPENAI_CHAT, "https://api.cohere.ai/compatibility/v1", "command-a-03-2025"),
    XAI("xAI Grok", "Paid · Grok models", ProviderProtocol.OPENAI_CHAT, "https://api.x.ai/v1", "grok-3-mini"),
}

/**
 * Coding agent engine installed in the private Linux runtime.
 * Each coding agent is installed independently on demand over the shared Core runtime.
 */
enum class AgentKind(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val downloadNote: String,
) {
    CLAUDE_CODE(
        "claude-code",
        "Claude Code",
        "Anthropic's coding agent · broad provider support",
        "71.8 MB",
    ),
    DEEPSEEK_HARNESS(
        "deepseek-harness",
        "DeepSeek Harness",
        "Official DeepSeek coding agent · API-key providers",
        "26.5 MB",
    ),
    ANTIGRAVITY(
        "antigravity",
        "Antigravity CLI",
        "Google's official coding agent · Google account",
        "39.9 MB",
    ),
    CODEX(
        "codex",
        "Codex CLI",
        "OpenAI's coding agent · OpenAI API key",
        "150 MB",
    ),
    CUSTOM(
        "custom",
        "Your API",
        "Your own API key · OpenAI / Claude-compatible",
        "",
    ),
    ;

    companion object {
        fun fromStored(value: String?): AgentKind = entries.firstOrNull {
            it.stableId == value || it.name == value
        } ?: CLAUDE_CODE
    }
}

/** Provider kinds usable with [AgentKind.DEEPSEEK_HARNESS]. Claude OAuth login has no dsh equivalent. */
val DEEPSEEK_HARNESS_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.DEEPSEEK,
    ProviderKind.ANTHROPIC,
    ProviderKind.LLM_ROUTER,
    ProviderKind.KIMI,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
    ProviderKind.GROQ,
    ProviderKind.GOOGLE_AI_STUDIO,
    ProviderKind.MISTRAL,
    ProviderKind.CEREBRAS,
    ProviderKind.COHERE,
    ProviderKind.XAI,
    ProviderKind.OPENAI,
)

val DSH_PROTOCOL_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.KIMI,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
    ProviderKind.GROQ,
    ProviderKind.GOOGLE_AI_STUDIO,
    ProviderKind.MISTRAL,
    ProviderKind.CEREBRAS,
    ProviderKind.COHERE,
    ProviderKind.XAI,
    ProviderKind.OPENAI,
)

fun defaultDshApiForProvider(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENCODE_ZEN -> "openai-responses"
    ProviderKind.NVIDIA_NIM -> "openai-completions"
    ProviderKind.GROQ,
    ProviderKind.GOOGLE_AI_STUDIO,
    ProviderKind.MISTRAL,
    ProviderKind.CEREBRAS,
    ProviderKind.COHERE,
    ProviderKind.XAI,
    ProviderKind.OPENAI -> "openai-completions"
    else -> "anthropic-messages"
}

/**
 * Best-effort protocol choice for a user-entered custom gateway URL.
 * The picker remains editable because a URL alone cannot prove a gateway's wire format.
 */
fun inferredDshApiForUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
    return when {
        normalized.endsWith("/responses") -> "openai-responses"
        "/anthropic" in normalized || "api.anthropic.com" in normalized -> "anthropic-messages"
        normalized.endsWith("/v1") -> "openai-completions"
        else -> "anthropic-messages"
    }
}

/** Resolves the protocol DeepSeek Harness will actually use for this saved profile. */
fun providerProtocolForAgent(profile: ProviderProfile, agent: AgentKind): ProviderProtocol {
    if (agent != AgentKind.DEEPSEEK_HARNESS || profile.kind !in DSH_PROTOCOL_PROVIDERS) {
        return profile.kind.protocol
    }
    val api = if (profile.kind.fixedProtocol) defaultDshApiForProvider(profile.kind) else profile.dshApi
    return when (api) {
        "openai-completions" -> ProviderProtocol.OPENAI_CHAT
        "openai-responses" -> ProviderProtocol.OPENAI_RESPONSES
        else -> ProviderProtocol.ANTHROPIC_GATEWAY
    }
}

/**
 * Free Mode allowlists. There is no per-item cost metadata in the catalog, so
 * these are conservative, explicit sets derived from the catalog itself:
 * agents whose standard route needs no paid subscription or paid API key, and
 * gateway providers with genuine free tiers / free models (NVIDIA NIM's own
 * catalog subtitle advertises its free tier; OpenCode Zen and OpenRouter both
 * serve free models). Everything else stays hidden while Free Mode is on.
 */
val FREE_MODE_AGENTS: Set<AgentKind> = setOf(
    AgentKind.ANTIGRAVITY,
    AgentKind.CLAUDE_CODE,
    AgentKind.DEEPSEEK_HARNESS,
)

val FREE_MODE_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.LLM_ROUTER,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
)

/** Agent choices for pickers, narrowed to [FREE_MODE_AGENTS] when Free Mode is on. */
fun agentsForPicker(freeModeOnly: Boolean): List<AgentKind> =
    AgentKind.entries.filter { !freeModeOnly || it in FREE_MODE_AGENTS }

/** Provider choices shown for the selected coding agent. */
fun providersForAgent(agent: AgentKind): List<ProviderKind> = when (agent) {
    AgentKind.DEEPSEEK_HARNESS -> ProviderKind.entries.filter { it in DEEPSEEK_HARNESS_PROVIDERS }
    AgentKind.CLAUDE_CODE -> ProviderKind.entries.filterNot {
        it == ProviderKind.OPENCODE_ZEN || it == ProviderKind.OPENAI ||
            it == ProviderKind.GROQ || it == ProviderKind.GOOGLE_AI_STUDIO ||
            it == ProviderKind.MISTRAL || it == ProviderKind.CEREBRAS ||
            it == ProviderKind.COHERE || it == ProviderKind.XAI
    }
    AgentKind.ANTIGRAVITY -> emptyList()
    // Codex authenticates with an OpenAI API key, stored under its own vault
    // slot ("OPENAI") so it can never overwrite the Custom agent's key.
    AgentKind.CODEX -> listOf(ProviderKind.OPENAI)
    // Custom agent is pure bring-your-own-API: OpenAI-compatible and Claude-compatible endpoints only.
    AgentKind.CUSTOM -> listOf(
        ProviderKind.CUSTOM, ProviderKind.ANTHROPIC, ProviderKind.LLM_ROUTER,
        ProviderKind.GROQ, ProviderKind.GOOGLE_AI_STUDIO, ProviderKind.MISTRAL,
        ProviderKind.CEREBRAS, ProviderKind.COHERE, ProviderKind.XAI,
        ProviderKind.OPENAI, ProviderKind.DEEPSEEK,
    )
}

/** [providersForAgent] narrowed to [FREE_MODE_PROVIDERS] when Free Mode is on. */
fun providersForAgent(agent: AgentKind, freeModeOnly: Boolean): List<ProviderKind> =
    providersForAgent(agent).filter { !freeModeOnly || it in FREE_MODE_PROVIDERS }

data class ProviderProfile(
    val kind: ProviderKind,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: String = kind.defaultModel,
    val hasSecret: Boolean = false,
    /** dsh custom-route wire protocol for CUSTOM: anthropic-messages | openai-completions | openai-responses. */
    val dshApi: String = defaultDshApiForProvider(kind),
    /** Comma-separated OpenRouter provider slugs, tried in order. Blank keeps automatic routing. */
    val openRouterProviderOrder: String = "",
    /** Whether OpenRouter may use providers outside [openRouterProviderOrder]. */
    val openRouterAllowFallbacks: Boolean = true,
) {
    /** Effective base URL: fixed kinds always resolve to their constant, ignoring stored drift. */
    val resolvedBaseUrl: String get() = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl

    val openRouterProviders: List<String>
        get() = openRouterProviderOrder.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
}

enum class ProjectKind { PROJECT, QUICK_PROJECT }

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val language: String,
    val slug: String = projectSlug(name),
    val rootPath: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val kind: ProjectKind = ProjectKind.PROJECT,
) {
    val formattedUpdatedAt: String
        get() {
            val diff = System.currentTimeMillis() - updatedAtMillis
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                diff < 0 || seconds < 60 -> "Just now"
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                days == 1L -> "Yesterday"
                days < 7 -> "${days}d ago"
                else -> {
                    val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(updatedAtMillis))
                }
            }
        }
}

fun projectSlug(name: String): String {
    val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(48)
        .trimEnd('-')
    return ascii.ifBlank { "project" }
}

data class QuickChatIdentity(val displayName: String, val slug: String)

fun generateQuickChatIdentity(usedSlugs: Set<String>, random: Random = Random.Default): QuickChatIdentity {
    val adjectives = listOf("bright", "calm", "clever", "curious", "gentle", "nimble", "quiet", "swift", "wise", "bold")
    val pioneers = listOf("turing", "lovelace", "hopper", "tesla", "curie", "ramanujan", "bose", "kalam", "faraday", "darwin")
    repeat(20) {
        val base = "${adjectives.random(random)}-${pioneers.random(random)}"
        if (base !in usedSlugs) return QuickChatIdentity(base.toDisplayName(), base)
    }
    val base = "${adjectives.random(random)}-${pioneers.random(random)}"
    val slug = generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in usedSlugs }
    return QuickChatIdentity(slug.toDisplayName(), slug)
}

private fun String.toDisplayName(): String = split('-').joinToString(" ") { word ->
    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
    val sizeBytes: Long = 0,
)

enum class RiskLevel { SAFE, REVIEW, HIGH }

/**
 * Optional development toolchains the user can pick during onboarding.
 * Node.js, npm, Git, and Claude Code itself are always installed because the
 * agent runtime depends on them; these stacks add heavier extras on demand.
 */
enum class DevStack(
    val label: String,
    val description: String,
    val installsSummary: String,
) {
    WEB(
        "Web (JavaScript / TypeScript)",
        "Websites and web apps with HTML, CSS, and JS frameworks.",
        "Node.js and npm (already included)",
    ),
    PYTHON(
        "Python",
        "Scripts, automation, data work, and Python backends.",
        "python3, pip, venv, and build tools",
    ),
    ANDROID(
        "Android (Java / Kotlin)",
        "Build Android app projects and install them directly on this phone.",
        "JDK 17, ARM64 Android SDK 36, Build Tools 35, Gradle 8.14.3, and an offline Maven cache",
    ),
    CPP(
        "C / C++",
        "Fast compiled programs, algorithms, and systems code.",
        "gcc, g++, make, cmake, gdb",
    ),
    PHP(
        "PHP",
        "Websites and apps with PHP — classic sites and Laravel projects.",
        "php-cli, common extensions, and Composer",
    ),
    BROWSER(
        "Browser Automation (Chromium & Puppeteer)",
        "Automated browser control, live preview inspection, and web interaction.",
        "chromium-browser, puppeteer-core, and pocket-browser CLI",
    ),
}

data class ToolRequest(
    val approvalId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val toolName: String,
    val explanation: String,
    val affectedPaths: List<String> = emptyList(),
    val commandPreview: String? = null,
    val risk: RiskLevel,
)

sealed interface RuntimeEvent {
    val sessionId: String

    data class SessionStarted(override val sessionId: String) : RuntimeEvent
    data class AssistantDelta(override val sessionId: String, val text: String) : RuntimeEvent
    data class ReasoningProgress(override val sessionId: String, val estimatedTokens: Int) : RuntimeEvent
    data class ReasoningSummary(
        override val sessionId: String,
        val summary: String,
        val blockId: Long,
        val startsNewBlock: Boolean = false,
        val isFinal: Boolean = false,
    ) : RuntimeEvent
    data class ToolStarted(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent
    data class RuntimeLog(
        override val sessionId: String,
        val title: String,
        val detail: String,
    ) : RuntimeEvent
    data class ToolRequested(override val sessionId: String, val request: ToolRequest) : RuntimeEvent
    data class ToolApproved(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolRejected(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolCompleted(override val sessionId: String, val toolName: String, val summary: String) : RuntimeEvent
    data class FilesChanged(override val sessionId: String, val changes: List<ChangeItem>) : RuntimeEvent {
        val paths: List<String> get() = changes.map { it.path }
    }
    data class PreviewStarted(override val sessionId: String, val url: String) : RuntimeEvent
    data class SessionCompleted(override val sessionId: String) : RuntimeEvent
    data class SessionFailed(override val sessionId: String, val reason: String) : RuntimeEvent
}

enum class ExecutionMode(
    val title: String,
    val description: String,
) {
    PLAN(
        "Plan",
        "Formulate a detailed architectural plan without making changes",
    ),
    BUILD(
        "Build",
        "Directly understand the task and implement changes end-to-end",
    ),
    UNIFIED(
        "Unified",
        "Plan first with an interactive roadmap for approval before building",
    ),
    ;

    companion object {
        fun fromStored(value: String?): ExecutionMode = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: UNIFIED
    }
}

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    SKIPPED
}

data class RoadmapComment(
    val id: String = UUID.randomUUID().toString(),
    val author: String = "User",
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val stepId: String? = null,
)

data class RoadmapStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val filesAffected: List<String> = emptyList(),
    val status: StepStatus = StepStatus.PENDING,
)

data class ActiveRoadmap(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val summary: String,
    val steps: List<RoadmapStep> = emptyList(),
    val comments: List<RoadmapComment> = emptyList(),
    val rawMarkdown: String = "",
    val isApproved: Boolean = false,
    val mode: ExecutionMode = ExecutionMode.UNIFIED,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

enum class TaskStatus {
    RUNNING,
    FINISHED,
    PENDING,
    FAILED
}

data class ScratchpadItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val detail: String = "",
    val durationSeconds: Long? = null,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val createdAt: Instant = Instant.now(),
    val attachments: List<ChatAttachment> = emptyList(),
    val workItems: List<ActivityItem> = emptyList(),
    val workedMillis: Long = 0L,
    val roadmap: ActiveRoadmap? = null,
)

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ProjectChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

enum class DiffLineType { CONTEXT, ADDITION, DELETION, INFO }

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class ChangeItem(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val diffLines: List<DiffLine> = emptyList(),
    val binary: Boolean = false,
    val accepted: Boolean? = null,
)

data class ActivityItem(
    val title: String,
    val detail: String,
    val isComplete: Boolean = true,
    val isCommand: Boolean = false,
)
