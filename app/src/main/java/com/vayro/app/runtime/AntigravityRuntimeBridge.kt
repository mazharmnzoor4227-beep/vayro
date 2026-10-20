package com.vayro.app.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ChangeItem
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.RuntimeEvent
import com.vayro.app.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class AntigravityAuthStatus { SIGNED_OUT, STARTING, AWAITING_CODE, COMPLETING, SIGNED_IN, ERROR }

data class AntigravityAccount(
    val email: String,
    val isActive: Boolean = false,
    val isQuotaExhausted: Boolean = false,
    val quotaExhaustedAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val isAuthExpired: Boolean = false,
    val authExpiredAt: Long = 0L,
    // v3.2.50: Google refused this account itself ("not eligible for
    // Antigravity"). Permanent until the user signs in again, so the
    // round-robin rotation skips it in every tier.
    val isIneligible: Boolean = false,
    val ineligibleAt: Long = 0L,
)

data class AntigravityAuthState(
    val status: AntigravityAuthStatus = AntigravityAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val accountEmail: String? = null,
    val accounts: List<AntigravityAccount> = emptyList(),
    val autoRoundRobin: Boolean = true,
)

internal sealed interface AntigravityParsedEvent {
    data class Initialized(val conversationId: String) : AntigravityParsedEvent
    data class Text(val value: String) : AntigravityParsedEvent
    data class ToolStarted(val name: String, val detail: String) : AntigravityParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : AntigravityParsedEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?,
    ) : AntigravityParsedEvent
}

internal object AntigravityEventParser {
    fun parse(line: String): AntigravityParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("event")) {
            "init" -> root.optString("conversation_id")
                .takeIf(String::isNotBlank)
                ?.let(AntigravityParsedEvent::Initialized)
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return null
                val delta = step.optString("text_delta")
                if (delta.isNotEmpty()) return AntigravityParsedEvent.Text(delta)
                val type = step.optString("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val rawName = step.optString("tool_name").ifBlank {
                        step.optJSONObject("tool_info")?.optString("name").orEmpty()
                    }.ifBlank { type.ifBlank { "Tool" } }
                    val name = antigravityToolDisplayName(rawName)
                    val detail = antigravityToolDetail(step, rawName).ifBlank { name }
                    if (step.optString("state") == "DONE") {
                        AntigravityParsedEvent.ToolCompleted(name, detail)
                    } else {
                        AntigravityParsedEvent.ToolStarted(name, detail)
                    }
                } else null
            }
            "result" -> {
                val result = root.optJSONObject("result") ?: root
                AntigravityParsedEvent.Result(
                    result.optString("conversation_id").takeIf(String::isNotBlank),
                    result.optString("status", "ERROR"),
                    result.optString("response").takeIf(String::isNotBlank),
                    result.optString("error").takeIf(String::isNotBlank),
                )
            }
            else -> null // Forward compatible with new agy event types.
        }
    }
}

private fun antigravityToolDisplayName(name: String): String = when (name.lowercase()) {
    "run_command" -> "Bash"
    "write_to_file", "replace_file_content", "multi_replace_file_content" -> "Write"
    "view_file" -> "Read"
    "list_dir" -> "List files"
    "grep_search", "search_files" -> "Search"
    "schedule" -> "Wait"
    "manage_task" -> "Manage task"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun antigravityToolDetail(step: JSONObject, rawName: String): String {
    step.optString("description").takeIf(String::isNotBlank)?.let { return redactToolDetail(it) }
    val info = step.optJSONObject("tool_info")
    val parameters = info?.optJSONObject("parameters")
    val preferredKeys = when (rawName.lowercase()) {
        "run_command" -> listOf("CommandLine", "command", "cmd")
        "write_to_file", "replace_file_content", "multi_replace_file_content" ->
            listOf("TargetFile", "AbsolutePath", "path", "file")
        "view_file" -> listOf("AbsolutePath", "TargetFile", "path", "file")
        "list_dir" -> listOf("DirectoryPath", "AbsolutePath", "path")
        "grep_search", "search_files" -> listOf("Query", "Pattern", "query", "pattern")
        "schedule" -> listOf("Prompt", "DurationSeconds")
        "manage_task" -> listOf("Action", "TaskId")
        else -> emptyList()
    }
    preferredKeys.forEach { key ->
        parameters?.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let {
            val prefix = when {
                rawName.equals("schedule", true) && key == "DurationSeconds" -> "Wait "
                rawName.equals("manage_task", true) -> "$key: "
                else -> ""
            }
            val suffix = if (rawName.equals("schedule", true) && key == "DurationSeconds") "s" else ""
            return redactToolDetail(prefix + it + suffix)
        }
    }
    return parameters?.toString()?.takeUnless { it == "{}" }?.let(::redactToolDetail)
        ?: step.optJSONObject("tool")?.toString()?.let(::redactToolDetail)
        .orEmpty()
}

private fun redactToolDetail(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
    .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(500)

/** Official Antigravity CLI bridge. OAuth and credentials remain owned by agy. */
private const val HELLO_TIMEOUT_MILLIS = 60_000L
class AntigravityRuntimeBridge(
    private val context: Context,
    private val model: () -> String,
    private val effort: () -> String,
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String) -> Unit,
    private val safetyGateway: com.vayro.app.security.SafetyGateway,
    private val interactiveApprovals: Boolean = true,
    private val geminiApiConfig: () -> GeminiApiConfig? = { null },
) : RuntimeBridge {
    override val supportsSessionRecovery: Boolean = true
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val launchGate = FullAccessLaunchGate(safetyGateway)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val geminiApiClient = GeminiApiClient()
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeToolExecutor: WorkspaceToolExecutor? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

    /**
     * Lightweight connectivity probe: sends a tiny hello to agy and returns its
     * reply text. No foreground service, no checkpoints, no saved conversation.
     *
     * v3.2.50 (Item 1): the probe stays a real end-to-end hello (there is no
     * cheaper agy check that proves sign-in — a version probe would prove the
     * install only), but it no longer fails silently or generically:
     * - [onStage] reports legible stages (starting / signing in / saying
     *   hello) so a long test is not a silent spinner.
     * - Plain (non-JSON) CLI output is kept as the last diagnostic instead
     *   of being dropped, and a terminal eligibility refusal in ANY output
     *   line ends the probe immediately with the provider's own words —
     *   the CLI gives up on an ineligible account only after long internal
     *   retries, which is why the old probe burned its whole timeout and
     *   then reported a content-free "did not answer".
     * - A timeout carries the last diagnostic when there is one; the bare
     *   "did not answer" text is reserved for a probe that truly heard
     *   nothing at all.
     */
    suspend fun hello(
        timeoutMillis: Long = HELLO_TIMEOUT_MILLIS,
        onStage: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(com.vayro.app.model.AgentKind.ANTIGRAVITY)) {
            throw IllegalStateException("Antigravity CLI is not installed.")
        }
        var lastDiagnostic = ""
        try {
            withTimeout(timeoutMillis) {
                val installed = installer.installedRuntime()
                val probeDir = File(context.cacheDir, "agy-hello").apply { mkdirs() }
                val command = buildList {
                    add(RuntimeInstaller.AGY_GUEST_PATH)
                    addAll(listOf("--input-format", "stream-json"))
                    addAll(listOf("--output-format", "stream-json"))
                    addAll(listOf("--print-timeout", "2m"))
                    addAntigravitySelection(model(), effort())
                    add("--new-project")
                }
                val outputFile = File(context.cacheDir, "agy-hello-${System.nanoTime()}.log")
                onStage("Starting Antigravity…")
                val process = installer.process(
                    installed.proot,
                    installed.rootfs,
                    probeDir,
                    emptyMap(),
                    command,
                    guestWorkspacePath = "/workspace/hello",
                    emulateHardLinks = false,
                    outputFile = outputFile,
                    extraBinds = installer.antigravityCredentialBinds(),
                )
                try {
                    onStage("Signing in…")
                    val request = JSONObject()
                        .put("event", "user")
                        .put("message", JSONObject().put("content", "Reply with exactly: ok"))
                        .toString() + "\n"
                    process.outputStream.write(request.toByteArray())
                    process.outputStream.flush()
                    process.outputStream.close()
                    var offset = 0L
                    val pending = StringBuilder()
                    var reply: String? = null
                    var helloStageSent = false
                    fun handleLine(line: String): Boolean {
                        // Terminal provider refusals end the probe at
                        // once, in the provider's own words: plain output
                        // lines are diagnostics (startSession keeps them
                        // the same way), and a Result error is checked
                        // before friendlyError can paraphrase it.
                        if (!line.startsWith("{") && line.isNotBlank()) {
                            lastDiagnostic = line.trim().take(500)
                            if (AntigravityFailover.isEligibilityFailure(line)) {
                                throw AntigravitySessionException(line.trim().take(500))
                            }
                        }
                        when (val event = AntigravityEventParser.parse(line)) {
                            is AntigravityParsedEvent.Text -> if (reply == null && event.value.isNotBlank()) reply = event.value
                            is AntigravityParsedEvent.Result -> {
                                if (event.status.equals("SUCCESS", ignoreCase = true)) {
                                    if (reply == null && !event.response.isNullOrBlank()) reply = event.response
                                    return true
                                }
                                val errorText = event.error ?: event.status
                                if (AntigravityFailover.isEligibilityFailure(errorText)) {
                                    throw AntigravitySessionException(errorText.trim().take(500))
                                }
                                throw AntigravitySessionException(friendlyError(errorText))
                            }
                            null -> Unit
                            else -> Unit
                        }
                        if (!helloStageSent) {
                            helloStageSent = true
                            onStage("Saying hello to Antigravity…")
                        }
                        return false
                    }
                    var done = false
                    while (!done && (process.isAlive || outputFile.length() > offset)) {
                        val available = outputFile.length() - offset
                        if (available <= 0) {
                            delay(100)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(outputFile, "r").use { file ->
                            file.seek(offset)
                            file.read(bytes)
                        }
                        if (count <= 0) continue
                        offset += count
                        pending.append(bytes.decodeToString(0, count))
                        var newline = pending.indexOf("\n")
                        while (newline >= 0) {
                            val line = pending.substring(0, newline).trimEnd('\r')
                            pending.delete(0, newline + 1)
                            if (handleLine(line)) {
                                done = true
                                break
                            }
                            newline = pending.indexOf("\n")
                        }
                    }
                    pending.toString().trim().takeIf(String::isNotEmpty)?.let { if (!done) done = handleLine(it) }
                    // Drain process exit without hanging past the timeout.
                    withContext(NonCancellable) {
                        runCatching { process.waitFor() }
                    }
                    check(done) {
                        friendlyError(
                            lastDiagnostic.ifBlank {
                                pending.toString().takeLast(500)
                            }.ifBlank { "Antigravity exited without answering" },
                        )
                    }
                    reply?.trim().takeUnless { it.isNullOrEmpty() } ?: "ok"
                } finally {
                    runCatching { process.destroy() }
                    runCatching {
                        if (process.isAlive) process.destroyForcibly()
                    }
                    runCatching { outputFile.delete() }
                    runCatching { probeDir.deleteRecursively() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // v3.2.50: a timeout surfaces whatever the CLI actually said
            // (e.g. a network or eligibility diagnostic); the generic
            // line is only for a probe that heard nothing at all.
            throw AntigravitySessionException(
                lastDiagnostic.takeIf { it.isNotBlank() } ?: "Antigravity did not answer. Try again.",
            )
        }
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
        clientSessionId: String?,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = clientSessionId ?: UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        activeSessionId = sessionId
        userStopRequested = false
        foregroundResultPosted = false
        var assistantTextSeen = false
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        val apiConfig = geminiApiConfig()
        if (apiConfig != null) {
            return@withContext startGeminiApiSession(sessionId, projectId, projectSlug, prompt, conversationHistory, apiConfig)
        }
        if (!installer.isAgentInstalled(com.vayro.app.model.AgentKind.ANTIGRAVITY)) {
            emitFailure(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it.")
            return@withContext sessionId
        }
        val launchAcknowledged = launchGate.authorizeLaunch(
            agentLabel = "Antigravity CLI",
            sessionId = sessionId,
            projectId = projectSlug,
            operation = com.vayro.app.security.SafetyOperation.ShellExec(
                "agy --dangerously-skip-permissions",
                "/workspace/$projectSlug",
            ),
            explanation = "Antigravity CLI runs with --dangerously-skip-permissions: it can run any shell command " +
                "and read, write or delete any file in the project workspace without asking again. " +
                "Approve only if you trust this task.",
            affectedPaths = listOf("/workspace/$projectSlug"),
            interactive = interactiveApprovals,
            emit = { eventBus.emit(it) },
        )
        if (!launchAcknowledged) {
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.registerStop(sessionId) {
                userStopRequested = true
                activeProcess?.destroy()
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val command = antigravityCommand(model(), effort(), conversationId(projectId))
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                emptyMap(),
                command,
                guestWorkspacePath = "/workspace/$projectSlug",
                emulateHardLinks = false,
                extraBinds = installer.antigravityCredentialBinds(),
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            val request = JSONObject()
                .put("event", "user")
                .put("message", JSONObject().put("content", antigravityWorkspacePrompt(projectSlug, prompt)))
                .toString() + "\n"
            process.outputStream.write(request.toByteArray())
            process.outputStream.flush()
            process.outputStream.close()

            val native = process as? NativeSpawnProcess ?: error("Unsupported Antigravity process")
            var offset = 0L
            val pending = StringBuilder()
            var resultSeen = false
            var lastDiagnostic = ""
            suspend fun handleLine(line: String) {
                when (val event = AntigravityEventParser.parse(line)) {
                    is AntigravityParsedEvent.Initialized -> saveConversationId(projectId, event.conversationId)
                    is AntigravityParsedEvent.Text -> {
                        assistantTextSeen = true
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.value))
                    }
                    is AntigravityParsedEvent.ToolStarted -> {
                        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                        pushForegroundProgress(
                            projectSlug,
                            "Running ${event.name} · ${event.detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { event.name }}",
                        )
                    }
                    is AntigravityParsedEvent.ToolCompleted -> eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    is AntigravityParsedEvent.Result -> {
                        event.conversationId?.let { saveConversationId(projectId, it) }
                        if (event.status.equals("SUCCESS", ignoreCase = true)) {
                            if (!assistantTextSeen && !event.response.isNullOrBlank()) {
                                assistantTextSeen = true
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.response))
                            }
                            resultSeen = true
                            val paths = checkpoints.changedFiles(workspace, before)
                            checkpoints.saveChangedPaths(projectId, paths)
                            if (paths.isNotEmpty()) {
                                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
                            }
                            val durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
                            val durationText = formatDurationText(durationSeconds)
                            emitCompleted(sessionId)
                            finishForegroundRuntime(true, projectSlug, "Antigravity finished the task in $projectSlug in $durationText.")
                        } else if (assistantTextSeen) {
                            // Assistant already produced complete text output; trailing errors during cleanup
                            // should not fail the session.
                            resultSeen = true
                            val paths = checkpoints.changedFiles(workspace, before)
                            checkpoints.saveChangedPaths(projectId, paths)
                            if (paths.isNotEmpty()) {
                                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
                            }
                            val durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
                            val durationText = formatDurationText(durationSeconds)
                            emitCompleted(sessionId)
                            finishForegroundRuntime(true, projectSlug, "Antigravity finished the task in $projectSlug in $durationText.")
                        } else throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                    }
                    null -> {
                        if (line.isNotBlank() && !line.startsWith("{")) {
                            lastDiagnostic = line.take(500)
                        }
                    }
                }
            }
            while (!resultSeen && (process.isAlive || native.outputFile.length() > offset)) {
                val available = native.outputFile.length() - offset
                if (available <= 0) {
                    delay(50)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(native.outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                offset += count
                pending.append(bytes.decodeToString(0, count))
                var newline = pending.indexOf("\n")
                while (newline >= 0) {
                    val line = pending.substring(0, newline).trimEnd('\r')
                    pending.delete(0, newline + 1)
                    handleLine(line)
                    if (resultSeen) break
                    newline = pending.indexOf("\n")
                }
            }
            pending.toString().trim().takeIf(String::isNotEmpty)?.let {
                handleLine(it)
                pending.setLength(0)
            }
            if (resultSeen) {
                runCatching { process.destroy() }
            }
            val exit = runCatching { process.waitFor() }.getOrDefault(0)
            if (userStopRequested) {
                throw AntigravitySessionException("Stopped by user")
            }
            if (exit != 0 && !resultSeen) {
                if (assistantTextSeen) {
                    resultSeen = true
                } else {
                    val failureDetail = lastDiagnostic.ifBlank { "Antigravity exited with code $exit" }
                    throw AntigravitySessionException(friendlyError(failureDetail))
                }
            }
            val paths = checkpoints.changedFiles(workspace, before)
            checkpoints.saveChangedPaths(projectId, paths)
            if (paths.isNotEmpty()) {
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
            }
            val durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
            val durationText = formatDurationText(durationSeconds)
            emitCompleted(sessionId)
            finishForegroundRuntime(true, projectSlug, "Antigravity finished the task in $projectSlug in $durationText.")
        }.onFailure {
            if (assistantTextSeen && !userStopRequested) {
                val durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
                val durationText = formatDurationText(durationSeconds)
                emitCompleted(sessionId)
                finishForegroundRuntime(true, projectSlug, "Antigravity finished the task in $projectSlug in $durationText.")
            } else {
                val rawError = it.message.orEmpty()
                val isTransient = RuntimeFailureClassifier.isTransientNetworkFailure(rawError)
                val message = if (userStopRequested) "Stopped by user"
                    else if (isTransient) RuntimeFailureClassifier.friendlyNetworkErrorMessage(rawError)
                    else friendlyError(rawError)
                emitFailure(sessionId, message)
                if (userStopRequested) {
                    cancelForegroundRuntime()
                } else if (!isTransient) {
                    finishForegroundRuntime(false, projectSlug, message)
                }
            }
        }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.unregisterStop(sessionId)
        sessionId
    }

    private suspend fun startGeminiApiSession(
        sessionId: String,
        projectId: String,
        projectSlug: String,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        config: GeminiApiConfig,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        userStopRequested = false
        foregroundResultPosted = false
        finished.remove(sessionId)
        RuntimeTaskController.registerStop(sessionId) {
            userStopRequested = true
            geminiApiClient.cancel()
            activeToolExecutor?.cancel()
        }
        val launchAcknowledged = launchGate.authorizeLaunch(
            agentLabel = "Gemini API",
            sessionId = sessionId,
            projectId = projectSlug,
            operation = com.vayro.app.security.SafetyOperation.ShellExec(
                "gemini-api-agent",
                "/workspace/$projectSlug",
            ),
            explanation = "The Gemini API agent can run shell commands and read, write or delete " +
                "files in the project workspace without asking again. " +
                "Approve only if you trust this task.",
            affectedPaths = listOf("/workspace/$projectSlug"),
            interactive = interactiveApprovals,
            emit = { eventBus.emit(it) },
        )
        if (!launchAcknowledged) {
            activeProcess = null
            activeSessionId = null
            RuntimeTaskController.unregisterStop(sessionId)
            return@withContext sessionId
        }
        startForegroundRuntime(projectSlug, "Gemini API")
        try {
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val history = conversationHistory.mapNotNull { message ->
                message.text.takeIf { it.isNotBlank() }
                    ?.let { (if (message.fromUser) "user" else "model") to it }
            }
            val contents = buildGeminiContents(history, prompt)
            val executor = WorkspaceToolExecutor(
                installer = installer,
                workspace = workspace,
                checkpoints = checkpoints,
                projectSlug = projectSlug,
                cacheDir = context.cacheDir,
            )
            activeToolExecutor = executor
            val loop = GeminiAgentLoop(executor)
            var assistantTextSeen = false
            loop.run(
                generateTurn = { body, onDelta ->
                    geminiApiClient.generateAgentTurn(config.apiKey, config.model, body, onDelta)
                },
                systemPrompt = geminiApiAgentSystemPrompt(projectSlug),
                contents = contents,
                onTextDelta = { delta ->
                    assistantTextSeen = true
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, delta))
                },
                onToolStarted = { name, detail ->
                    eventBus.emit(RuntimeEvent.ToolStarted(sessionId, name, detail))
                    pushForegroundProgress(
                        projectSlug,
                        "Running $name ${detail.take(80)}".trim(),
                    )
                },
                onToolCompleted = { name, summary ->
                    eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, name, summary))
                },
                isCancelled = { userStopRequested },
            )
            if (userStopRequested) throw AntigravitySessionException("Stopped by user")
            val paths = checkpoints.changedFiles(workspace, before)
            checkpoints.saveChangedPaths(projectId, paths)
            if (paths.isNotEmpty()) {
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
            }
            if (!assistantTextSeen) {
                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, "Done."))
            }
            emitCompleted(sessionId)
            finishForegroundRuntime(true, projectSlug, "Gemini API finished the task in $projectSlug.")
        } catch (e: Exception) {
            val message = if (userStopRequested || e is CancellationException) "Stopped by user"
            else e.message.orEmpty().ifBlank { "Gemini API request failed." }
            emitFailure(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(false, projectSlug, message)
            }
        }
        activeProcess = null
        activeToolExecutor = null
        activeSessionId = null
        RuntimeTaskController.unregisterStop(sessionId)
        sessionId
    }

    private fun geminiApiAgentSystemPrompt(projectSlug: String): String =
        "You are the coding assistant inside the Kodex Android app. The active project workspace is " +
            "/workspace/$projectSlug. You have tools: run_shell to execute shell commands in the workspace, " +
            "read_file to read files, write_file to create or overwrite files, edit_file to make surgical " +
            "edits, and list_dir to explore directories. When the user asks for a code change, use the " +
            "tools to implement it end to end: inspect the project, make the edits, then build or run " +
            "tests to verify. Work only inside the project workspace. Do not ask for confirmation for " +
            "normal file operations. Explain briefly what you did after the tools finish. " +
            com.vayro.app.agent.AgentChatController.DELIVERY_CONTRACT

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        launchGate.respondToApproval(request.approvalId, approved)
    }

    override suspend fun stopSession(sessionId: String) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            launchGate.abortPending()
            activeProcess?.destroy()
            geminiApiClient.cancel()
            activeToolExecutor?.cancel()
            emitFailure(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() = activeSessionId?.let { stopSession(it) } ?: Unit

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val paths = checkpoints.readChangedPaths(projectId)
        if (!backup.isDirectory || paths.isEmpty()) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        paths.forEach { restore(workspace, backup, it) }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        checkpoints.checkpointDir(projectId).deleteRecursively()
        Unit
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        restore(checkpoints.ensureWorkspace(projectId), File(checkpoints.checkpointDir(projectId), "project"), path)
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else baseline.delete()
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private fun restore(workspace: File, backup: File, path: String) {
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else target.delete()
    }

    private suspend fun emitCompleted(sessionId: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun emitFailure(sessionId: String, reason: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
    }

    private fun startForegroundRuntime(projectName: String, agentName: String = "Antigravity CLI") {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                .putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, agentName)
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL, "$agentName is working in $projectName")
                .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, activeSessionId),
        )
    }

    private fun pushForegroundProgress(projectName: String, detail: String) {
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, "Antigravity CLI")
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, activeSessionId),
            )
        }
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(if (completed) RuntimeExecutionService.ACTION_COMPLETE else RuntimeExecutionService.ACTION_FAILED)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, "Antigravity CLI")
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, activeSessionId),
            )
        }.onFailure { context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java)) }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, activeSessionId),
            )
        }.onFailure { context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java)) }
    }

    private fun friendlyError(raw: String): String {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            RuntimeFailureClassifier.isTransientNetworkFailure(value) ->
                RuntimeFailureClassifier.friendlyNetworkErrorMessage(value)
            value.contains("unauthenticated", true) ||
                value.contains("code 401", true) ||
                value.contains("invalid authentication credentials", true) ||
                value.contains("oauth 2 access token", true) ||
                value.contains("login cookie", true) ||
                value.contains("token expired", true) ||
                value.contains("authentication required", true) ||
                value.contains("authentication failed", true) ||
                value.contains("not signed in", true) ->
                "Antigravity session authentication expired or invalid (401). Please reconnect your Google account in Settings."
            value.contains("out of credits", true) || value.contains("quota", true) ->
                "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset."
            value.contains("timed out", true) || value.contains("timeout", true) ->
                "Antigravity reached the 60-minute task limit. Your files were kept."
            value.contains("model", true) && (value.contains("invalid", true) || value.contains("unknown", true)) ->
                "The selected Antigravity model is unavailable. Refresh models in Settings."
            value.isBlank() -> "Antigravity could not complete the task."
            else -> value.take(500)
        }
    }
}

private class AntigravitySessionException(message: String) : IllegalStateException(message)

internal fun antigravityCommand(model: String, effort: String, conversationId: String?): List<String> = buildList {
    add(RuntimeInstaller.AGY_GUEST_PATH)
    addAll(listOf("--input-format", "stream-json"))
    addAll(listOf("--output-format", "stream-json"))
    addAll(listOf("--print-timeout", "60m"))
    // Covered by tests. Tool calls themselves have no approval channel in headless
    // agy; the bridge gates the whole launch behind an explicit user
    // acknowledgment (FullAccessLaunchGate) before this flag is used.
    add("--dangerously-skip-permissions")
    addAntigravitySelection(model, effort)
    conversationId?.takeIf(String::isNotBlank)?.let {
        addAll(listOf("--conversation", it))
    } ?: add("--new-project")
}

/**
 * `agy models` returns complete configuration IDs such as
 * `gemini-3.8-flash-medium`. Supplying a second, different --effort makes the
 * official CLI reject an otherwise valid model as conflicting. An explicit
 * model ID therefore owns its effort; --effort is used only with agy's default
 * model selection.
 */
private fun MutableList<String>.addAntigravitySelection(model: String, effort: String) {
    if (model.isNotBlank()) {
        addAll(listOf("--model", model))
    } else if (effort in setOf("low", "medium", "high")) {
        addAll(listOf("--effort", effort))
    }
}

internal fun antigravityWorkspacePrompt(projectSlug: String, prompt: String): String = """
    <pocketdev_workspace>
    The active project workspace is /workspace/$projectSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.
    Do not enter a clarification or ambiguity waiting loop. For normal ambiguity, inspect the repository and make the safest reasonable assumption, then implement and verify the requested change. Ask the user only when a missing choice is truly blocking or an irreversible/destructive action requires explicit confirmation.
    If the request is concrete, execute it end-to-end rather than replying with a plan and waiting for another message.
    </pocketdev_workspace>

    $prompt
""".trimIndent()
