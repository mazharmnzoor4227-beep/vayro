package com.vayro.app.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.vayro.app.model.AgentKind
import com.vayro.app.model.ChangeItem
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.RuntimeEvent
import com.vayro.app.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal sealed interface CodexParsedEvent {
    data class ThreadStarted(val threadId: String) : CodexParsedEvent
    data class Text(val value: String) : CodexParsedEvent
    data class ToolStarted(val name: String, val detail: String) : CodexParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : CodexParsedEvent
    object TurnCompleted : CodexParsedEvent
    data class TurnFailed(val error: String) : CodexParsedEvent
}

internal object CodexEventParser {
    fun parse(line: String): CodexParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("type")) {
            "thread.started" -> root.optString("thread_id")
                .takeIf(String::isNotBlank)
                ?.let(CodexParsedEvent::ThreadStarted)
            "item.started" -> parseItem(root, completed = false)
            "item.completed" -> parseItem(root, completed = true)
            // item.updated payloads may repeat the message text; the whole
            // text arrives once on item.completed, so updated is ignored to
            // avoid duplicating it in chat.
            "item.updated" -> null
            "turn.completed" -> CodexParsedEvent.TurnCompleted
            "turn.failed" -> CodexParsedEvent.TurnFailed(turnFailedError(root))
            "error" -> CodexParsedEvent.TurnFailed(
                root.optString("message").ifBlank { "Codex reported an error" },
            )
            else -> null // Forward compatible with new codex event types.
        }
    }

    private fun parseItem(root: JSONObject, completed: Boolean): CodexParsedEvent? {
        val item = root.optJSONObject("item") ?: return null
        return when (item.optString("type")) {
            "agent_message" -> {
                if (!completed) return null
                item.optString("text").takeIf(String::isNotBlank)?.let(CodexParsedEvent::Text)
            }
            "command_execution" -> {
                val command = item.optString("command").take(500)
                val detail = command.ifBlank { "Shell command" }
                if (completed) CodexParsedEvent.ToolCompleted("Bash", detail)
                else CodexParsedEvent.ToolStarted("Bash", detail)
            }
            "file_change" -> {
                val path = item.optJSONArray("changes")
                    ?.optJSONObject(0)?.optString("path").orEmpty().take(500)
                val detail = path.ifBlank { "File change" }
                if (completed) CodexParsedEvent.ToolCompleted("Write", detail)
                else CodexParsedEvent.ToolStarted("Write", detail)
            }
            "mcp_tool_call" -> {
                val tool = item.optString("tool")
                    .ifBlank { item.optString("server") }
                    .ifBlank { "MCP tool" }
                    .take(500)
                if (completed) CodexParsedEvent.ToolCompleted("MCP", tool)
                else CodexParsedEvent.ToolStarted("MCP", tool)
            }
            "web_search_call" -> {
                val query = item.optString("query").take(500).ifBlank { "Web search" }
                if (completed) CodexParsedEvent.ToolCompleted("Search", query)
                else CodexParsedEvent.ToolStarted("Search", query)
            }
            else -> null
        }
    }

    private fun turnFailedError(root: JSONObject): String {
        return when (val error = root.opt("error")) {
            is JSONObject -> error.optString("message").ifBlank { "Codex turn failed" }
            is String -> error.ifBlank { "Codex turn failed" }
            else -> root.optString("message").ifBlank { "Codex turn failed" }
        }
    }
}

/**
 * OpenAI Codex CLI bridge. Drives `codex exec --json` in the Linux guest
 * and maps its JSONL events onto [RuntimeEvent]s. Auth is the user's own
 * OpenAI API key, passed to the CLI through its process environment
 * (`OPENAI_API_KEY` / `CODEX_API_KEY`); the key is never written to a file
 * or the CLI command line. A blank base URL means the OpenAI default; a
 * blank model means the Codex default.
 */
class CodexRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

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
        if (!installer.isAgentInstalled(AgentKind.CODEX)) {
            emitFailure(sessionId, "Codex CLI is not installed. Open Settings → Coding agent to install it.")
            return@withContext sessionId
        }
        val secret = secretFor(provider).orEmpty()
        if (secret.isBlank()) {
            emitFailure(sessionId, "No API key is saved. Add your OpenAI API key in Agent → Codex.")
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
            val guestWorkspacePath = "/workspace/$projectSlug"
            val environment = buildMap {
                put("OPENAI_API_KEY", secret)
                put("CODEX_API_KEY", secret)
                provider.resolvedBaseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }?.let {
                    put("OPENAI_BASE_URL", it)
                }
            }
            val command = codexCommand(provider.model.trim(), guestWorkspacePath, codexWorkspacePrompt(projectSlug, prompt))
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                emulateHardLinks = false,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()

            val native = process as? NativeSpawnProcess ?: error("Unsupported Codex process")
            var offset = 0L
            val pending = StringBuilder()
            var resultSeen = false
            var lastDiagnostic = ""
            suspend fun handleLine(line: String) {
                when (val event = CodexEventParser.parse(line)) {
                    is CodexParsedEvent.ThreadStarted -> Unit
                    is CodexParsedEvent.Text -> {
                        assistantTextSeen = true
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.value))
                    }
                    is CodexParsedEvent.ToolStarted -> {
                        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                        pushForegroundProgress(
                            projectSlug,
                            "Running ${event.name} · ${event.detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { event.name }}",
                        )
                    }
                    is CodexParsedEvent.ToolCompleted -> eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    is CodexParsedEvent.TurnCompleted -> {
                        resultSeen = true
                        val paths = checkpoints.changedFiles(workspace, before)
                        checkpoints.saveChangedPaths(projectId, paths)
                        if (paths.isNotEmpty()) {
                            eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
                        }
                        val durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
                        val durationText = formatDurationText(durationSeconds)
                        emitCompleted(sessionId)
                        finishForegroundRuntime(true, projectSlug, "Codex finished the task in $projectSlug in $durationText.")
                    }
                    is CodexParsedEvent.TurnFailed -> throw CodexSessionException(friendlyError(event.error))
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
                throw CodexSessionException("Stopped by user")
            }
            if (exit != 0 && !resultSeen) {
                if (assistantTextSeen) {
                    resultSeen = true
                } else {
                    val failureDetail = lastDiagnostic.ifBlank { "Codex exited with code $exit" }
                    throw CodexSessionException(friendlyError(failureDetail))
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
            finishForegroundRuntime(true, projectSlug, "Codex finished the task in $projectSlug in $durationText.")
        }.onFailure {
            if (assistantTextSeen && !userStopRequested) {
                val durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
                val durationText = formatDurationText(durationSeconds)
                emitCompleted(sessionId)
                finishForegroundRuntime(true, projectSlug, "Codex finished the task in $projectSlug in $durationText.")
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

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // codex exec runs headless with no approval-response channel wired in
        // this driver, so no Codex approval can be pending here.
    }

    override suspend fun stopSession(sessionId: String) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
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

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                .putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, "Codex CLI")
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL, "Codex CLI is working in $projectName")
                .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, activeSessionId),
        )
    }

    private fun pushForegroundProgress(projectName: String, detail: String) {
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, "Codex CLI")
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
                    .putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, "Codex CLI")
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
            value.contains("401", true) ||
                value.contains("incorrect api key", true) ||
                value.contains("invalid api key", true) ||
                value.contains("invalid authentication", true) ||
                value.contains("authentication failed", true) ||
                value.contains("unauthorized", true) ->
                "The OpenAI API key was rejected (401). Check the key in Agent → Codex."
            value.contains("429", true) || value.contains("rate limit", true) ->
                "OpenAI rate limit reached (429). Wait a moment and try again."
            value.contains("quota", true) || value.contains("insufficient_quota", true) ->
                "The OpenAI account is out of quota. Check billing or wait for the quota to reset."
            value.contains("model", true) && (value.contains("not found", true) || value.contains("does not exist", true) || value.contains("invalid", true)) ->
                "The selected Codex model is unavailable. Pick another model in Agent → Codex."
            value.isBlank() -> "Codex could not complete the task."
            else -> value.take(500)
        }
    }
}

private class CodexSessionException(message: String) : IllegalStateException(message)

internal fun codexCommand(model: String, workspacePath: String, prompt: String): List<String> = buildList {
    add(RuntimeInstaller.CODEX_GUEST_PATH)
    add("exec")
    add("--json")
    // Project workspaces are not always git repositories; do not fail a task for that.
    add("--skip-git-repo-check")
    addAll(listOf("-C", workspacePath))
    if (model.isNotBlank()) {
        addAll(listOf("-m", model))
    }
    add(prompt)
}

internal fun codexWorkspacePrompt(projectSlug: String, prompt: String): String = """
    <pocketdev_workspace>
    The active project workspace is /workspace/$projectSlug. Create, edit, read, run, and build project files only inside this directory.
    Do not enter a clarification or ambiguity waiting loop. For normal ambiguity, inspect the repository and make the safest reasonable assumption, then implement and verify the requested change. Ask the user only when a missing choice is truly blocking or an irreversible/destructive action requires explicit confirmation.
    If the request is concrete, execute it end-to-end rather than replying with a plan and waiting for another message.
    </pocketdev_workspace>

    $prompt
""".trimIndent()
