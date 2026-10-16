package com.vayro.app.ui.coordinators

import com.vayro.app.model.DevStack
import com.vayro.app.model.Project
import com.vayro.app.runtime.NativeSpawnProcess
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.terminal.TerminalProcessHandle
import com.vayro.app.terminal.TerminalProcessRegistry
import com.vayro.app.terminal.TerminalSessionController
import com.vayro.app.ui.TerminalOutputLine
import com.vayro.app.ui.TerminalSessionTab
import com.vayro.app.ui.sanitizeTerminalOutput
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ProjectTerminalSnapshot(
    val lines: List<TerminalOutputLine> = emptyList(),
    val cwd: String = "/workspace",
    /** All persisted session tabs (Milestone 2). Empty when none were saved. */
    val sessions: List<TerminalSessionTab> = emptyList(),
    val activeSessionId: String? = null,
)

internal data class ProjectTerminalResult(
    val output: String,
    val exitCode: Int,
    val cwd: String,
)

/**
 * Terminal domain coordinator (Milestone 1).
 *
 * Explicit owner of the terminal state machine: the standalone shell,
 * per-project terminal sessions (via [TerminalSessionController]), process
 * lifecycle (input/interrupt/kill), history persistence and destructive-
 * command gating. All execution goes through the real [RuntimeInstaller]
 * Linux runtime; outputs and exit codes are genuine.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged. `buildAndRunAndroidApp` stays in the
 * ViewModel — it orchestrates the runtime health check and belongs to the
 * Runtime domain.
 */
class TerminalCoordinator(
    private val host: CoordinatorHost,
    private val installer: RuntimeInstaller,
    private val terminalSessionController: TerminalSessionController,
    private val workspaceRootFor: (Project) -> File,
    private val refreshProjectFiles: () -> Unit,
) {
    private val _terminalLines = MutableStateFlow<List<TerminalOutputLine>>(
        listOf(
            TerminalOutputLine(
                command = "uname -a",
                output = "Linux pocket-dev 6.1.0-arm64 #1 SMP aarch64 GNU/Linux (PRoot Sandbox)",
                exitCode = 0,
            ),
        ),
    )
    val terminalLines: StateFlow<List<TerminalOutputLine>> = _terminalLines.asStateFlow()

    private val _isTerminalRunning = MutableStateFlow(false)
    val isTerminalRunning: StateFlow<Boolean> = _isTerminalRunning.asStateFlow()

    private val _terminalLiveOutput = MutableStateFlow("")
    val terminalLiveOutput: StateFlow<String> = _terminalLiveOutput.asStateFlow()

    private val _terminalCurrentCommand = MutableStateFlow<String?>(null)
    val terminalCurrentCommand: StateFlow<String?> = _terminalCurrentCommand.asStateFlow()

    @Volatile private var terminalProcess: Process? = null
    @Volatile private var projectTerminalProcess: Process? = null
    @Volatile private var projectTerminalProjectId: String? = null
    @Volatile private var projectTerminalStopRequested: Boolean = false

    /**
     * Milestone 2 — attach/detach registry binding running processes to their
     * originating session. A process keeps running when the user switches tabs
     * (detach); switching back re-attaches. [projectTerminalProcess] is kept
     * as a legacy alias updated alongside the registry for the active session.
     */
    private val processRegistry = TerminalProcessRegistry()

    fun runTerminalCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isBlank() || _isTerminalRunning.value) return
        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }
        _isTerminalRunning.value = true
        _terminalCurrentCommand.value = command
        _terminalLiveOutput.value = ""
        host.scope.launch {
            val (output, exitCode) = withContext(Dispatchers.IO) {
                runCatching {
                    if (!installer.isInstalled()) {
                        return@runCatching "Linux environment is not ready yet." to 1
                    }
                    val runtime = installer.installedRuntime()
                    // Sync with chat agent: when a project is active, run in its
                    // workspace (same dir the AI chat works in). Otherwise fall
                    // back to the shared throwaway terminal dir.
                    val project = host.state().activeProject
                    val workspace = project?.let { workspaceRootFor(it) }
                        ?: File(host.appContext.filesDir, "workspaces/terminal").apply { mkdirs() }
                    val guestPath = project?.let { "/workspace/${it.slug}" } ?: "/workspace"
                    val preparedCommand = prepareInteractiveShellCommand(command)
                    val proc = installer.process(
                        proot = runtime.proot,
                        rootfs = runtime.rootfs,
                        workspace = workspace,
                        environment = emptyMap(),
                        guestWorkspacePath = guestPath,
                        guestCommand = listOf("/usr/bin/bash", "-c", preparedCommand),
                    )
                    terminalProcess = proc
                    val native = proc as? NativeSpawnProcess
                    var offset = 0L
                    val streamed = StringBuilder()
                    var autoConfirmed = false
                    while (proc.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                        val file = native?.outputFile
                        val available = (file?.length() ?: 0L) - offset
                        if (file == null || available <= 0) {
                            Thread.sleep(50)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            input.read(bytes)
                        }
                        if (count > 0) {
                            offset += count
                            streamed.append(bytes.decodeToString(0, count))
                            _terminalLiveOutput.value = sanitizeTerminalOutput(streamed.toString())
                                .trimEnd()
                                .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                            if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, streamed.toString())) {
                                proc.outputStream.write("y\n".toByteArray())
                                proc.outputStream.flush()
                                autoConfirmed = true
                            }
                        }
                    }
                    val exit = proc.waitFor()
                    runCatching { proc.outputStream.close() }
                    val out = sanitizeTerminalOutput(streamed.toString()).trim()
                    val finalOut = if (out.isNotEmpty() || exit == 0) out else "Process exited with code $exit"
                    finalOut to exit
                }.getOrElse { "Error: ${it.message}" to 1 }
            }
            _terminalLines.update { it + TerminalOutputLine(command = command, output = output, exitCode = exitCode) }
            _terminalLiveOutput.value = ""
            _terminalCurrentCommand.value = null
            _isTerminalRunning.value = false
            terminalProcess = null
        }
    }

    fun sendTerminalInput(text: String) {
        sendProcessInput(terminalProcess, text)
    }

    fun interruptTerminalCommand() {
        interruptProcess(terminalProcess)
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    fun requestProjectTerminalCommand(command: String) {
        val normalized = command.trim()
        if (normalized.isBlank() || host.state().projectTerminalRunning || projectTerminalProcess?.isAlive == true) return
        if (requiresAndroidToolchain(normalized) && !installer.isStackInstalled(DevStack.ANDROID)) {
            host.updateState {
                it.copy(toastMessage = "Android build tools are not installed. Add Android in Settings → Development stacks.")
            }
            return
        }
        if (isDestructiveTerminalCommand(normalized)) {
            host.updateState { it.copy(pendingTerminalCommand = normalized) }
        } else {
            runProjectTerminalCommand(normalized)
        }
    }

    fun prepareProjectTerminalCommand(command: String) {
        val project = host.state().activeProject ?: return
        if (command.isBlank() || host.state().projectTerminalRunning) return
        host.updateState {
            it.copy(
                projectTerminalCwd = projectGuestRoot(project),
                projectTerminalDraft = command.trim(),
            )
        }
    }

    fun consumeProjectTerminalDraft() {
        host.updateState { it.copy(projectTerminalDraft = null) }
    }

    fun openProjectTerminal() {
        val project = host.state().activeProject ?: return
        if (host.state().projectTerminalRunning) return
        host.updateState { it.copy(projectTerminalCwd = projectGuestRoot(project)) }
    }

    fun confirmProjectTerminalCommand() {
        val command = host.state().pendingTerminalCommand ?: return
        host.updateState { it.copy(pendingTerminalCommand = null) }
        runProjectTerminalCommand(command)
    }

    fun cancelProjectTerminalCommand() {
        host.updateState { it.copy(pendingTerminalCommand = null) }
    }

    private fun runProjectTerminalCommand(command: String) {
        val project = host.state().activeProject ?: return
        if (host.state().projectTerminalRunning) return
        val startingCwd = host.state().projectTerminalCwd
        // Milestone 2: the command belongs to the session that was active when
        // it started. If the user switches tabs mid-run, the process detaches
        // and keeps running; completion is routed back to THIS session.
        val originatingSessionId = host.state().activeTerminalSessionId
        projectTerminalStopRequested = false
        val requestedPreviewUrl = detectServerUrl(command)
        host.updateState {
            it.copy(
                projectTerminalRunning = true,
                projectTerminalLiveOutput = "",
                projectTerminalCommand = command,
                pendingTerminalCommand = null,
                previewReady = it.previewReady || requestedPreviewUrl != null,
                previewUrl = requestedPreviewUrl ?: it.previewUrl,
                terminalSessions = it.terminalSessions.map { s ->
                    if (s.id == originatingSessionId) s.copy(isRunning = true) else s
                },
            )
        }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { runProjectTerminalProcess(project.id, command, startingCwd, originatingSessionId) }
                    .getOrElse { error ->
                        ProjectTerminalResult(
                            output = "Terminal error: ${error.message ?: error::class.java.simpleName}",
                            exitCode = 1,
                            cwd = startingCwd,
                        )
                    }
            }
            val completedLine = TerminalOutputLine(
                command = command,
                output = result.output.ifBlank {
                    if (result.exitCode == 0) "" else "Process exited with code ${result.exitCode}"
                },
                exitCode = result.exitCode,
            )
            processRegistry.detach(originatingSessionId)
            // Route completion to the originating session, wherever the UI is now.
            host.updateState { current ->
                val updatedSessions = current.terminalSessions.map { s ->
                    if (s.id == originatingSessionId) {
                        s.copy(
                            lines = (s.lines + completedLine).takeLast(MAX_PROJECT_TERMINAL_HISTORY),
                            cwd = result.cwd,
                            isRunning = false,
                        )
                    } else s
                }
                val isActiveSession = originatingSessionId == current.activeTerminalSessionId
                val isActiveProject = current.activeProject?.id == project.id
                val sessionLines = updatedSessions.firstOrNull { it.id == originatingSessionId }?.lines.orEmpty()
                current.copy(
                    terminalSessions = updatedSessions,
                    projectTerminalLines = if (isActiveSession && isActiveProject) sessionLines else current.projectTerminalLines,
                    projectTerminalLiveOutput = if (isActiveSession) "" else current.projectTerminalLiveOutput,
                    projectTerminalRunning = if (isActiveSession) false else current.projectTerminalRunning,
                    projectTerminalCwd = if (isActiveSession && isActiveProject) result.cwd else current.projectTerminalCwd,
                    projectTerminalCommand = if (isActiveSession) null else current.projectTerminalCommand,
                )
            }
            if (host.state().activeProject?.id == project.id) {
                persistTerminalState(project.id)
                refreshProjectFiles()
            }
            projectTerminalProcess = null
            projectTerminalProjectId = null
            projectTerminalStopRequested = false
        }
    }

    fun stopProjectTerminalCommand() {
        if (!host.state().projectTerminalRunning) return
        projectTerminalStopRequested = true
        // Milestone 2: stop targets the active session's process via the registry.
        val handle = processRegistry.handleFor(host.state().activeTerminalSessionId)
        host.scope.launch(Dispatchers.IO) {
            handle?.destroy()
            delay(400)
            if (handle?.isAlive == true) handle.destroyForcibly()
        }
    }

    fun sendProjectTerminalInput(text: String) {
        if (text.isBlank()) return
        // Milestone 2: input goes to the ACTIVE session's process. A detached
        // background session keeps running but no longer receives UI input.
        val handle = processRegistry.handleFor(host.state().activeTerminalSessionId)
        if (handle == null) {
            host.updateState { current -> current.copy(toastMessage = "No running process in this terminal session.") }
            return
        }
        host.scope.launch(Dispatchers.IO) {
            if (!handle.sendInput(text)) {
                host.updateState { current -> current.copy(toastMessage = "This process is no longer accepting input.") }
            }
        }
    }

    fun interruptProjectTerminalCommand() {
        // Milestone 2: interrupt targets the active session's process.
        processRegistry.handleFor(host.state().activeTerminalSessionId)?.interrupt()
    }

    /**
     * Milestone 2 — explicit lifecycle control for any session's process,
     * including background (detached) sessions.
     */
    fun stopTerminalSessionProcess(sessionId: String) {
        val handle = processRegistry.handleFor(sessionId) ?: return
        host.scope.launch(Dispatchers.IO) {
            handle.destroy()
            delay(400)
            if (handle.isAlive) handle.destroyForcibly()
        }
    }

    fun clearProjectTerminal() {
        val project = host.state().activeProject ?: return
        if (host.state().projectTerminalRunning) return
        host.updateState { state ->
            state.copy(
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                terminalSessions = state.terminalSessions.map { s ->
                    if (s.id == state.activeTerminalSessionId) s.copy(lines = emptyList()) else s
                },
            )
        }
        persistTerminalState(project.id)
    }

    fun createTerminalSession() {
        val (updated, newSession) = terminalSessionController.createSession(
            sessions = host.state().terminalSessions,
            defaultCwd = host.state().projectTerminalCwd,
        )
        host.updateState {
            it.copy(
                terminalSessions = updated,
                activeTerminalSessionId = newSession.id,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
            )
        }
        host.state().activeProject?.let { persistTerminalState(it.id) }
    }

    fun selectTerminalSession(sessionId: String) {
        val current = host.state()
        val (updated, target) = terminalSessionController.selectSession(
            sessions = current.terminalSessions,
            currentActiveId = current.activeTerminalSessionId,
            currentLines = current.projectTerminalLines,
            currentCwd = current.projectTerminalCwd,
            currentRunning = current.projectTerminalRunning,
            targetSessionId = sessionId,
        )
        if (target == null) return
        host.updateState {
            it.copy(
                terminalSessions = updated,
                activeTerminalSessionId = target.id,
                projectTerminalLines = target.lines,
                projectTerminalCwd = target.cwd,
                projectTerminalRunning = target.isRunning,
                projectTerminalLiveOutput = "",
            )
        }
        // Milestone 2: the newly attached session may have a live background
        // process; reflect the registry truth in the UI running flag.
        if (processRegistry.hasRunningProcess(target.id)) {
            host.updateState { it.copy(projectTerminalRunning = true) }
        }
        host.state().activeProject?.let { persistTerminalState(it.id) }
    }

    fun closeTerminalSession(sessionId: String) {
        if (host.state().terminalSessions.size <= 1) {
            clearProjectTerminal()
            return
        }
        val (remaining, newActive) = terminalSessionController.closeSession(
            sessions = host.state().terminalSessions,
            currentActiveId = host.state().activeTerminalSessionId,
            targetSessionId = sessionId,
        )
        val active = newActive ?: remaining.firstOrNull() ?: return
        // Milestone 2: closing a session with a live process kills it — the
        // process cannot outlive its session tab.
        processRegistry.handleFor(sessionId)?.let { processRegistry.detach(sessionId)?.destroy() }
        host.updateState {
            it.copy(
                terminalSessions = remaining,
                activeTerminalSessionId = active.id,
                projectTerminalLines = active.lines,
                projectTerminalCwd = active.cwd,
                projectTerminalRunning = active.isRunning || processRegistry.hasRunningProcess(active.id),
                projectTerminalLiveOutput = "",
            )
        }
        host.state().activeProject?.let { persistTerminalState(it.id) }
    }

    /** Loads persisted terminal history; called by openProject in the ViewModel. */
    internal fun loadProjectTerminal(project: Project): ProjectTerminalSnapshot {
        val file = terminalHistoryFile(project.id)
        val guestRoot = projectGuestRoot(project)
        if (!file.isFile) return ProjectTerminalSnapshot(cwd = guestRoot)
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TerminalOutputLine(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    command = item.optString("command"),
                    output = item.optString("output"),
                    exitCode = item.optInt("exitCode"),
                )
            }
            // Milestone 2: restore the full session tab list. A process can
            // never survive app death, so any session still marked running is
            // honestly reported as interrupted — never silently resurrected.
            val sessionsArray = root.optJSONArray("sessions") ?: JSONArray()
            val sessions = (0 until sessionsArray.length()).mapNotNull { index ->
                val item = sessionsArray.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { return@mapNotNull null }
                val wasRunning = item.optBoolean("isRunning", false)
                val sessionLines = readSessionLines(item.optJSONArray("lines"))
                val recoveredLines = if (wasRunning) {
                    sessionLines + TerminalOutputLine(
                        command = "",
                        output = "Session recovered after app restart — the running process did not survive.",
                        exitCode = 1,
                    )
                } else {
                    sessionLines
                }
                TerminalSessionTab(
                    id = id,
                    title = item.optString("title").ifBlank { "Terminal" },
                    isRunning = false,
                    cwd = item.optString("cwd", guestRoot).takeIf {
                        it == guestRoot || it.startsWith("$guestRoot/")
                    } ?: guestRoot,
                    lines = recoveredLines.takeLast(MAX_PROJECT_TERMINAL_HISTORY),
                )
            }
            val activeSessionId = root.optString("activeSessionId").takeIf { id ->
                id.isNotBlank() && sessions.any { it.id == id }
            }
            val active = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.lastOrNull()
            ProjectTerminalSnapshot(
                lines = active?.lines ?: lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY),
                cwd = active?.cwd ?: root.optString("cwd", guestRoot).takeIf {
                    it == guestRoot || it.startsWith("$guestRoot/")
                } ?: guestRoot,
                sessions = sessions,
                activeSessionId = active?.id,
            )
        }.getOrDefault(ProjectTerminalSnapshot(cwd = guestRoot))
    }

    private fun readSessionLines(array: JSONArray?): List<TerminalOutputLine> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            TerminalOutputLine(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                command = item.optString("command"),
                output = item.optString("output"),
                exitCode = item.optInt("exitCode"),
            )
        }
    }

    /**
     * Milestone 2 — persists the full terminal state for a project: the
     * session tab list, the active session, and the active session's
     * lines/cwd (legacy top-level fields kept for compatibility).
     */
    internal fun persistTerminalState(projectId: String) {
        val state = host.state()
        runCatching {
            val file = terminalHistoryFile(projectId)
            file.parentFile?.mkdirs()
            val sessionsArray = JSONArray()
            state.terminalSessions.forEach { session ->
                val linesArray = JSONArray()
                session.lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY).forEach { line ->
                    linesArray.put(
                        JSONObject()
                            .put("id", line.id)
                            .put("command", line.command)
                            .put("output", line.output.takeLast(MAX_PROJECT_TERMINAL_OUTPUT))
                            .put("exitCode", line.exitCode),
                    )
                }
                sessionsArray.put(
                    JSONObject()
                        .put("id", session.id)
                        .put("title", session.title)
                        .put("isRunning", session.isRunning || processRegistry.hasRunningProcess(session.id))
                        .put("cwd", session.cwd)
                        .put("lines", linesArray),
                )
            }
            val active = state.terminalSessions.firstOrNull { it.id == state.activeTerminalSessionId }
            val root = JSONObject()
                .put("cwd", active?.cwd ?: state.projectTerminalCwd)
                .put("activeSessionId", state.activeTerminalSessionId)
                .put("sessions", sessionsArray)
            // Legacy top-level lines for readers that only know the old shape.
            val linesArray = JSONArray()
            (active?.lines ?: state.projectTerminalLines).takeLast(MAX_PROJECT_TERMINAL_HISTORY).forEach { line ->
                linesArray.put(
                    JSONObject()
                        .put("id", line.id)
                        .put("command", line.command)
                        .put("output", line.output.takeLast(MAX_PROJECT_TERMINAL_OUTPUT))
                        .put("exitCode", line.exitCode),
                )
            }
            root.put("lines", linesArray)
            file.writeText(root.toString())
        }
    }

    /** Destroys terminal processes; called from the ViewModel's onCleared. */
    fun onCleared() {
        // Milestone 2: kill every tracked session process, not just the active one.
        processRegistry.destroyAll()
        runCatching {
            projectTerminalProcess?.destroy()
            terminalProcess?.destroy()
        }
    }

    /**
     * Milestone 2 — adapts a real [Process] to the registry's handle interface.
     */
    private fun Process.asTerminalHandle(): TerminalProcessHandle {
        val process = this
        return object : TerminalProcessHandle {
            override val isAlive: Boolean get() = process.isAlive
            override fun destroy() {
                runCatching { process.destroy() }
            }
            override fun destroyForcibly() {
                runCatching { process.destroyForcibly() }
            }
            override fun interrupt() {
                (process as? NativeSpawnProcess)?.interrupt() ?: runCatching { process.destroy() }
            }
            override fun sendInput(text: String): Boolean = runCatching {
                process.outputStream.write((text + "\n").toByteArray())
                process.outputStream.flush()
                true
            }.getOrDefault(false)
        }
    }

    private fun runProjectTerminalProcess(
        projectId: String,
        command: String,
        cwd: String,
        sessionId: String,
    ): ProjectTerminalResult {
        if (!installer.isInstalled()) return ProjectTerminalResult("Linux environment is not ready yet.", 1, cwd)
        val installed = installer.installedRuntime()
        val project = host.state().projects.firstOrNull { it.id == projectId }
            ?: host.state().activeProject?.takeIf { it.id == projectId }
            ?: return ProjectTerminalResult("Project is no longer available.", 1, cwd)
        val workspace = workspaceRootFor(project)
        val guestWorkspacePath = projectGuestRoot(project)
        val marker = "__POCKETDEV_CWD_${UUID.randomUUID()}__"
        val preparedCommand = prepareInteractiveShellCommand(command)
        val script = """
            cd -- ${shellQuote(cwd)} || exit 1
            $preparedCommand
            pocket_status=${'$'}?
            printf '\n$marker%s\n' "${'$'}PWD"
            exit ${'$'}pocket_status
        """.trimIndent()
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspace,
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/bash", "-lc", script),
            guestWorkspacePath = guestWorkspacePath,
        )
        projectTerminalProcess = process
        projectTerminalProjectId = projectId
        if (projectTerminalStopRequested) process.destroy()
        // Milestone 2: bind the live process to its session for attach/detach.
        processRegistry.attach(sessionId, process.asTerminalHandle())
        val native = process as? NativeSpawnProcess
            ?: return ProjectTerminalResult("Unsupported terminal process.", 1, cwd)
        var offset = 0L
        val output = StringBuilder()
        var autoConfirmed = false
        while (process.isAlive || native.outputFile.length() > offset) {
            val available = native.outputFile.length() - offset
            if (available <= 0) {
                Thread.sleep(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(native.outputFile, "r").use { file ->
                file.seek(offset)
                file.read(bytes)
            }
            if (count > 0) {
                offset += count
                output.append(bytes.decodeToString(0, count))
                val visible = sanitizeTerminalOutput(output.toString().substringBefore(marker))
                    .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, visible)) {
                    process.outputStream.write("y\n".toByteArray())
                    process.outputStream.flush()
                    autoConfirmed = true
                }
                val detectedPreviewUrl = detectPreviewUrl(visible)
                host.updateState { current ->
                    // Milestone 2: live output only drives the UI while this
                    // session is attached (active). A detached session's
                    // process keeps running; its output lands on completion.
                    if (current.activeProject?.id == projectId && current.activeTerminalSessionId == sessionId) {
                        current.copy(
                            projectTerminalLiveOutput = visible,
                            previewReady = current.previewReady || detectedPreviewUrl != null,
                            previewUrl = detectedPreviewUrl ?: current.previewUrl,
                        )
                    } else current
                }
            }
        }
        val exitCode = process.waitFor()
        runCatching { process.outputStream.close() }
        val raw = output.toString()
        val cwdAfter = raw.substringAfter(marker, "")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it == guestWorkspacePath || it.startsWith("$guestWorkspacePath/") }
            ?: cwd
        val cleanOutput = sanitizeTerminalOutput(raw.substringBefore(marker))
            .trim()
            .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
        return ProjectTerminalResult(cleanOutput, exitCode, cwdAfter)
    }

    private fun sendProcessInput(process: Process?, text: String) {
        if (process?.isAlive != true || text.isBlank()) return
        host.scope.launch(Dispatchers.IO) {
            runCatching {
                process.outputStream.write((text + "\n").toByteArray())
                process.outputStream.flush()
            }.onFailure {
                host.updateState { current -> current.copy(toastMessage = "This process is no longer accepting input.") }
            }
        }
    }

    private fun interruptProcess(process: Process?) {
        if (process?.isAlive != true) return
        host.scope.launch(Dispatchers.IO) {
            (process as? NativeSpawnProcess)?.interrupt() ?: process.destroy()
        }
    }

    /**
     * Package management must never block on Y/N, locale, timezone, service-restart,
     * or config-file dialogs in the phone UI. Other commands remain interactive and
     * can receive input through [sendProcessInput].
     */
    private fun prepareInteractiveShellCommand(command: String): String {
        val normalizedApt = command
            .replace(Regex("(?<![\\w-])sudo\\s+apt(?:-get)?\\s+"), "apt-get ")
            .replace(Regex("(?<![\\w-])apt\\s+"), "apt-get ")
            .replace(
                Regex("(?<![\\w-])apt-get\\s+(install|upgrade|full-upgrade|dist-upgrade|remove|autoremove|fix-broken)\\b"),
                "apt-get -y -o Dpkg::Options::=--force-confold $1",
            )
        return "export DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a TZ=Etc/UTC LC_ALL=C.UTF-8; $normalizedApt"
    }

    private fun shouldAutoConfirmPackageCommand(command: String, output: String): Boolean {
        val packageCommand = Regex("(?i)(^|[;&|]\\s*)(sudo\\s+)?(apt|apt-get|dpkg)\\b").containsMatchIn(command)
        if (!packageCommand) return false
        val tail = output.takeLast(500)
        return Regex("(?i)(do you want to continue|continue\\?)\\s*\\[[Yy]/[Nn]\\]").containsMatchIn(tail)
    }

    private fun isDestructiveTerminalCommand(command: String): Boolean {
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        return listOf(
            "rm -rf", "rm -fr", "git reset --hard", "git clean -f", "git push --force",
            "mkfs", "dd if=", "chmod -r 777", "shutdown", "reboot", ":(){", "kill \$(pgrep", "pkill -f",
        ).any(normalized::contains) || Regex("(curl|wget).*(\\||>)\\s*(sh|bash)").containsMatchIn(normalized)
    }

    private fun detectPreviewUrl(output: String): String? {
        val match = Regex("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):(\\d{2,5})(?:/[^\\s]*)?")
            .findAll(output)
            .lastOrNull()
            ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return "http://127.0.0.1:$port/"
    }

    private fun detectServerUrl(command: String): String? {
        val match = Regex("""python(?:3)?\s+-m\s+http\.server(?:\s+(\d{2,5}))?""")
            .find(command)
            ?: return null
        val port = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8000
        return port.takeIf { it in 1..65535 }?.let { "http://127.0.0.1:$it/" }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun terminalHistoryFile(projectId: String): File =
        File(host.appContext.filesDir, "terminal-history/$projectId.json")

    internal fun saveProjectTerminal(projectId: String, cwd: String, lines: List<TerminalOutputLine>) {
        // Legacy entry point: the Milestone 2 session-aware persistTerminalState
        // is the canonical path; this keeps old callers compiling.
        persistTerminalState(projectId)
    }

    /** Deletes persisted terminal history for a project (e.g. on project deletion). */
    fun deleteTerminalHistory(projectId: String) {
        runCatching { terminalHistoryFile(projectId).delete() }
    }

    private fun projectGuestRoot(project: Project): String = guestRootFor(project)

    private fun requiresAndroidToolchain(command: String): Boolean =
        Regex("(?m)(^|[;&|]\\s*)(?:\\./)?gradle(?:w)?(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(command)

    companion object {
        private const val MAX_PROJECT_TERMINAL_HISTORY = 100
        private const val MAX_PROJECT_TERMINAL_OUTPUT = 200_000
    }
}

/**
 * Shared guest-workspace-root helper (Milestone 1).
 *
 * Identical logic to the former `MainViewModel.projectGuestRoot` private
 * helper; promoted to package-internal so both the coordinator and the
 * ViewModel share one definition.
 */
internal fun guestRootFor(project: com.vayro.app.model.Project): String = "/workspace/${project.slug}"
