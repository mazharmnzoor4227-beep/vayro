package com.vayro.app.runtime

import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ChangeItem
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.RuntimeEvent
import com.vayro.app.model.ToolRequest
import com.vayro.app.security.PermissionLevel
import kotlinx.coroutines.flow.Flow

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>
    /** True only when restarting startSession can resume the provider-owned conversation safely. */
    val supportsSessionRecovery: Boolean get() = false
    suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile, clientSessionId: String? = null, chatId: String? = null): String
    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    /**
     * Level-aware approval: bridges whose launch gate records grants
     * (Antigravity, DeepSeek Harness) override this so a Chat approval is
     * recorded as a CHAT grant instead of being downgraded to SESSION.
     * Default keeps the old behavior for bridges without a launch gate.
     */
    suspend fun respondToApprovalWithLevel(request: ToolRequest, approved: Boolean, level: PermissionLevel) {
        respondToApproval(request, approved)
    }
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}

/** Keeps recent context while preventing an old chat from becoming an unbounded prompt allocation. */
internal fun List<ChatMessage>.recentWithinCharacterBudget(maxCharacters: Int): List<ChatMessage> {
    if (maxCharacters <= 0 || isEmpty()) return emptyList()
    var remaining = maxCharacters
    val selected = ArrayDeque<ChatMessage>()
    for (message in asReversed()) {
        if (remaining <= 0) break
        val text = if (message.text.length <= remaining) message.text else message.text.takeLast(remaining)
        selected.addFirst(message.copy(text = text))
        remaining -= text.length
        if (text.length < message.text.length) break
    }
    return selected.toList()
}

/**
 * Compacts historical messages to minimize token usage across multi-turn chats.
 * - Preserves the immediate preceding assistant response with high fidelity.
 * - In older turns, verbose code blocks and repetitive terminal outputs are collapsed.
 */
internal fun compactHistoryForPrompt(
    messages: List<ChatMessage>,
    maxCharacters: Int,
): List<ChatMessage> {
    if (messages.isEmpty() || maxCharacters <= 0) return emptyList()
    val budgeted = messages.recentWithinCharacterBudget(maxCharacters)
    if (budgeted.isEmpty()) return emptyList()

    val lastIndex = budgeted.lastIndex
    return budgeted.mapIndexed { index, msg ->
        val isImmediatePrecedingTurn = index == lastIndex
        if (msg.fromUser || isImmediatePrecedingTurn) {
            if (msg.text.length > 4000) {
                msg.copy(text = truncateHeadAndTail(msg.text, maxHeadLines = 25, maxTailLines = 25, maxTotalLines = 60))
            } else {
                msg
            }
        } else {
            msg.copy(text = compactOlderTurnText(msg.text))
        }
    }
}

internal fun compactOlderTurnText(text: String, maxCodeLines: Int = 6): String {
    if (text.isBlank()) return text

    // Compact fenced code blocks: ```lang ... ```
    val codeFenceRegex = Regex("```([a-zA-Z0-9_-]*)\\r?\\n([\\s\\S]*?)```")
    val compacted = codeFenceRegex.replace(text) { matchResult ->
        val lang = matchResult.groupValues[1]
        val code = matchResult.groupValues[2]
        val lines = code.lines()
        if (lines.size > maxCodeLines) {
            val head = lines.take(3).joinToString("\n")
            val tail = lines.takeLast(2).joinToString("\n")
            val omitted = lines.size - 5
            "```$lang\n$head\n... [$omitted lines of code collapsed] ...\n$tail\n```"
        } else {
            matchResult.value
        }
    }

    return if (compacted.length > 1500) {
        truncateHeadAndTail(compacted, maxHeadLines = 15, maxTailLines = 15, maxTotalLines = 35)
    } else {
        compacted
    }
}


object RuntimeLaunchConfigBuilder {
    fun build(profile: ProviderProfile, authToken: String? = null, localGatewayUrl: String? = null): RuntimeLaunchConfig {
        val environment = linkedMapOf("DISABLE_AUTOUPDATER" to "1")
        when (profile.kind.protocol) {
            com.vayro.app.model.ProviderProtocol.CLAUDE_LOGIN -> {
                require(!authToken.isNullOrBlank()) { "Enter a Claude subscription token first" }
                environment["CLAUDE_CODE_OAUTH_TOKEN"] = authToken
                // Claude Code gives API-key variables precedence over OAuth. Explicitly
                // clear them so a previous API provider can never shadow this token.
                environment["ANTHROPIC_API_KEY"] = ""
                environment["ANTHROPIC_AUTH_TOKEN"] = ""
            }
            com.vayro.app.model.ProviderProtocol.ANTHROPIC -> {
                environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.vayro.app.model.ProviderProtocol.ANTHROPIC_GATEWAY -> {
                environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.vayro.app.model.ProviderProtocol.OPENROUTER -> {
                environment["ANTHROPIC_BASE_URL"] = (localGatewayUrl ?: profile.resolvedBaseUrl).trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.vayro.app.model.ProviderProtocol.OPENAI_RESPONSES,
            com.vayro.app.model.ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = "claude-sonnet-4-6"
            }
        }
        val runtimeModel = environment["ANTHROPIC_MODEL"] ?: profile.model
        if (profile.kind.protocol != com.vayro.app.model.ProviderProtocol.CLAUDE_LOGIN) {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = runtimeModel
            environment["ANTHROPIC_SMALL_MODEL"] = runtimeModel
            environment["ANTHROPIC_FAST_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            environment["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            environment["DISABLE_TELEMETRY"] = "1"
            if (!authToken.isNullOrBlank()) {
                environment["ANTHROPIC_AUTH_TOKEN"] = authToken
                if (profile.kind == com.vayro.app.model.ProviderKind.LLM_ROUTER) {
                    environment["ANTHROPIC_API_KEY"] = ""
                    environment["OPENROUTER_API_KEY"] = authToken
                } else {
                    environment["ANTHROPIC_API_KEY"] = authToken
                }
            }
        }
        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
