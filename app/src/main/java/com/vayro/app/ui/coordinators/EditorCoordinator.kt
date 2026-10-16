package com.vayro.app.ui.coordinators

import com.vayro.app.editor.EditorBufferManager
import com.vayro.app.editor.EditorSession
import com.vayro.app.editor.EditorSessionStore
import com.vayro.app.editor.EditorSplitState
import com.vayro.app.editor.EditorViewState
import com.vayro.app.editor.RenameEngine
import com.vayro.app.model.Project
import com.vayro.app.model.WorkspaceEntry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor domain coordinator (Milestone 1).
 *
 * Explicit owner of the editor state machine: open editor tabs, the opened
 * file (path/content/loading), file content loading, file saves (with editor
 * checkpoints), and workspace file listing. All file mutations go through the
 * real [com.vayro.app.workspace.FileOperationsController]; editor saves go
 * through the real [com.vayro.app.runtime.WorkspaceCheckpoints].
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 *
 * Wiring notes (Milestone 1):
 * - `refreshProjectFiles`, `createFile`, `createFolder`, `renameEntry`,
 *   `deleteEntry`, `duplicateEntry` and `moveEntry` were intentionally NOT
 *   moved here. `refreshProjectFiles` needs `detectNestedProjectRoot` and
 *   `findAndroidGradleProjectRoot` (private in `MainViewModel`, not in this
 *   coordinator's constructor), and the six file-mutation functions call
 *   `refreshProjectFiles()`. They stay in the ViewModel until that dependency
 *   is provided (e.g. a `refreshProjectFiles: () -> Unit` constructor lambda,
 *   as `GitCoordinator` does).
 * - `MAX_EDITABLE_FILE_CHARS` / `MAX_VISIBLE_WORKSPACE_ENTRIES` are
 *   duplicated below with identical values because they are `private const`
 *   in `MainViewModel`'s companion object and cannot be referenced externally.
 * - `readWorkspace` calls `isClaudeRuntimeMetadata`, which stays private in
 *   `MainViewModel` per the extraction rules; the parent wiring must supply it
 *   (move the tiny helper or pass an equivalent predicate).
 */
class EditorCoordinator(
    private val host: CoordinatorHost,
    private val preferences: com.vayro.app.data.AppPreferences,
    private val fileOperationsController: com.vayro.app.workspace.FileOperationsController,
    private val editorCheckpoints: com.vayro.app.runtime.WorkspaceCheckpoints,
    private val workspaceRootFor: (com.vayro.app.model.Project) -> java.io.File,
) {
    companion object {
        // Duplicated from MainViewModel's private companion object (identical
        // values); see the class KDoc. Needed because the originals are
        // private to MainViewModel and this file must not modify it.
        private const val MAX_EDITABLE_FILE_CHARS = 512_000
        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
        private const val SESSION_PERSIST_DEBOUNCE_MS = 1_500L
    }

    /**
     * Milestone 2 — in-memory dirty-buffer ownership and durable session
     * persistence. Dirty buffers (unsaved edits) survive process death; only
     * dirty buffers are written to the session file.
     */
    private val bufferManager = EditorBufferManager()
    private val sessionStore: EditorSessionStore by lazy {
        EditorSessionStore(File(host.appContext.filesDir, "editor-sessions"))
    }
    private var sessionPersistJob: Job? = null

    /** Last rename transaction, for undo. Null when nothing to undo. */
    private var lastRenameTransaction: com.vayro.app.workspace.ReplaceTransaction? = null

    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDirectory) return
        openFileByPath(entry.path)
    }

    fun openFileByPath(path: String) {
        // Never fail silently: a tap with no active project used to do nothing
        // at all, which looks exactly like "file does not open".
        val project = host.state().activeProject
        if (project == null) {
            host.updateState { it.copy(toastMessage = "No project open. Open a project first, then tap the file again.") }
            return
        }
        val currentTabs = host.state().openEditorTabs
        val updatedTabs = if (path in currentTabs) currentTabs else currentTabs + path
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        scheduleSessionPersist(project.id, updatedTabs, path)
        // Milestone 2: a dirty buffer restores unsaved work immediately —
        // no disk read, no lost edits.
        val dirtyBuffer = bufferManager.getBuffer(path)?.takeIf { it.isDirty }
        if (dirtyBuffer != null) {
            host.updateState {
                it.copy(
                    openEditorTabs = updatedTabs,
                    openedFilePath = path,
                    openedFileContent = dirtyBuffer.currentContent,
                    fileContentLoading = false,
                    editorViewStates = it.editorViewStates + (path to dirtyBuffer.viewState),
                    editorDirtyPaths = bufferManager.dirtyBuffers().map { b -> b.path }.toSet(),
                )
            }
            return
        }
        host.updateState {
            it.copy(
                openEditorTabs = updatedTabs,
                openedFilePath = path,
                openedFileContent = null,
                fileContentLoading = true,
            )
        }
        loadFileContent(project, path)
    }

    fun selectEditorTab(path: String) {
        if (host.state().openedFilePath == path) return
        val project = host.state().activeProject ?: return
        host.updateState {
            it.copy(
                openedFilePath = path,
                openedFileContent = null,
                fileContentLoading = true,
            )
        }
        loadFileContent(project, path)
    }

    fun closeEditorTab(path: String) {
        val project = host.state().activeProject ?: return
        val currentTabs = host.state().openEditorTabs
        val updatedTabs = currentTabs.filter { it != path }
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        // Milestone 2: a dirty buffer survives tab close (it stays in the
        // buffer manager and the session store); clean buffers are dropped.
        val buffer = bufferManager.getBuffer(path)
        if (buffer == null || !buffer.isDirty) {
            bufferManager.closeBuffer(path)
        }
        val newActive = if (host.state().openedFilePath == path) updatedTabs.lastOrNull() else host.state().openedFilePath
        scheduleSessionPersist(project.id, updatedTabs, newActive)
        if (host.state().openedFilePath == path) {
            val nextPath = updatedTabs.lastOrNull()
            if (nextPath != null) {
                host.updateState {
                    it.copy(
                        openEditorTabs = updatedTabs,
                        openedFilePath = nextPath,
                        openedFileContent = null,
                        fileContentLoading = true,
                    )
                }
                loadFileContent(project, nextPath)
            } else {
                host.updateState {
                    it.copy(
                        openEditorTabs = emptyList(),
                        openedFilePath = null,
                        openedFileContent = null,
                        fileContentLoading = false,
                    )
                }
            }
        } else {
            host.updateState { it.copy(openEditorTabs = updatedTabs) }
        }
    }

    fun closeOtherEditorTabs(keepPath: String) {
        val project = host.state().activeProject ?: return
        val updatedTabs = listOf(keepPath)
        preferences.setOpenEditorTabs(project.id, updatedTabs)
        // Milestone 2: drop clean buffers for closed tabs; keep dirty ones.
        bufferManager.allPaths().forEach { path ->
            if (path != keepPath) {
                val buffer = bufferManager.getBuffer(path)
                if (buffer == null || !buffer.isDirty) bufferManager.closeBuffer(path)
            }
        }
        scheduleSessionPersist(project.id, updatedTabs, keepPath)
        if (host.state().openedFilePath != keepPath) {
            selectEditorTab(keepPath)
        }
        host.updateState { it.copy(openEditorTabs = updatedTabs) }
    }

    fun closeAllEditorTabs() {
        val project = host.state().activeProject
        if (project != null) {
            preferences.setOpenEditorTabs(project.id, emptyList())
            // Milestone 2: dirty buffers persist even with no tabs open.
            bufferManager.allPaths().forEach { path ->
                val buffer = bufferManager.getBuffer(path)
                if (buffer == null || !buffer.isDirty) bufferManager.closeBuffer(path)
            }
            scheduleSessionPersist(project.id, emptyList(), null)
        }
        host.updateState {
            it.copy(
                openEditorTabs = emptyList(),
                openedFilePath = null,
                openedFileContent = null,
                fileContentLoading = false,
            )
        }
    }

    fun closeFile() {
        closeAllEditorTabs()
    }

    private fun loadFileContent(project: Project, path: String) {
        host.scope.launch {
            val content = withContext(Dispatchers.IO) {
                val file = File(workspaceRootFor(project), path)
                runCatching {
                    if (file.length() > 512_000L) {
                        file.inputStream().use { stream ->
                            val buf = ByteArray(512_000)
                            val read = stream.read(buf)
                            String(buf, 0, read)
                        } + "\n\n[File truncated — too large to display fully]"
                    } else {
                        file.readText()
                    }
                }.getOrElse { "Could not read file: ${it.message}" }
            }
            if (host.state().openedFilePath == path) {
                // Milestone 2: register the buffer so edits are tracked.
                // A dirty buffer always wins over a fresh disk read.
                val existing = bufferManager.getBuffer(path)
                val buffer = if (existing?.isDirty == true) {
                    existing
                } else {
                    bufferManager.syncFromDisk(path, content)
                    bufferManager.getBuffer(path) ?: bufferManager.openBuffer(path, content)
                }
                val effectiveContent = buffer.currentContent
                host.updateState {
                    it.copy(
                        openedFileContent = effectiveContent,
                        fileContentLoading = false,
                        editorViewStates = it.editorViewStates + (path to buffer.viewState),
                        editorDirtyPaths = bufferManager.dirtyBuffers().map { b -> b.path }.toSet(),
                    )
                }
            }
        }
    }

    /**
     * Persists an edited workspace file as an explicit, reviewable change.
     * A checkpoint is created before the first manual edit so the existing
     * Changes tab can Undo/Keep the edit using the same durable mechanism as
     * agent-generated changes.
     */
    suspend fun saveFile(path: String, content: String): Boolean {
        val current = host.state()
        val project = current.activeProject ?: return false
        if (current.isRunning || current.projectTerminalRunning) {
            host.updateState { it.copy(toastMessage = "Stop the running task before editing files") }
            return false
        }
        if (path.isBlank() || path.startsWith('/') || editorCheckpoints.isInternalRuntimePath(path)) {
            host.updateState { it.copy(toastMessage = "This workspace path cannot be edited") }
            return false
        }
        if (path.split('/').any { it == ".." || it.isBlank() }) {
            host.updateState { it.copy(toastMessage = "Unsafe workspace path") }
            return false
        }
        if (content.length > MAX_EDITABLE_FILE_CHARS) {
            host.updateState { it.copy(toastMessage = "This file is too large to edit safely in the mobile editor") }
            return false
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val workspace = workspaceRootFor(project).canonicalFile
                val rootPath = workspace.toPath()
                val target = File(workspace, path).canonicalFile
                require(target.parentFile?.canonicalFile?.toPath()?.startsWith(rootPath) == true) { "Workspace path escapes project" }
                val existing = target.takeIf(File::isFile)
                if (existing != null && existing.length() > MAX_EDITABLE_FILE_CHARS) {
                    error("This file is too large to edit safely in the mobile editor")
                }
                if (existing != null && existing.readText() == content) return@runCatching Unit

                editorCheckpoints.createCheckpoint(project.id, workspace)
                target.parentFile?.mkdirs()
                val temporary = File(
                    target.parentFile,
                    ".${target.name}.mh-edit-${UUID.randomUUID()}.tmp",
                )
                try {
                    temporary.writeText(content)
                    runCatching {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.getOrElse {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                } finally {
                    temporary.delete()
                }
                editorCheckpoints.saveChangedPaths(project.id, listOf(path))
            }
        }
        return result.fold(
            onSuccess = {
                // Milestone 2: the buffer is clean again; persist the session.
                bufferManager.markSaved(path, content)
                val projectId = project.id
                val currentTabs = host.state().openEditorTabs
                val activePath = host.state().openedFilePath
                scheduleSessionPersist(projectId, currentTabs, activePath)
                val workspace = workspaceRootFor(project)
                val pendingPaths = editorCheckpoints.readChangedPaths(project.id)
                val changes = editorCheckpoints.buildChangeDetails(project.id, workspace, pendingPaths)
                host.updateState { state ->
                    state.copy(
                        openedFileContent = content,
                        changes = changes,
                        toastMessage = "Saved " + path,
                        editorDirtyPaths = bufferManager.dirtyBuffers().map { b -> b.path }.toSet(),
                        workspaceFiles = state.workspaceFiles.map { entry ->
                            if (entry.path == path) entry.copy(
                                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                            ) else entry
                        },
                    )
                }
                true
            },
            onFailure = { error ->
                host.updateState { it.copy(toastMessage = error.message ?: "Could not save " + path) }
                false
            },
        )
    }

    private fun readWorkspace(project: Project): List<WorkspaceEntry> {
        val root = workspaceRootFor(project)
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .maxDepth(12)
            .onEnter { directory ->
                val relative = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
                directory == root || (!isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .drop(1)
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                !isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .take(MAX_VISIBLE_WORKSPACE_ENTRIES)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                )
            }
            .sortedWith(compareBy<WorkspaceEntry> { it.path.lowercase() }.thenByDescending { it.isDirectory })
            .toList()
    }

    // ------------------------------------------------------------------
    // Milestone 2 — dirty-buffer tracking, session restore, rename.
    // ------------------------------------------------------------------

    /**
     * Called by the UI when the buffer text changes. Marks the buffer dirty
     * and debounces the session snapshot to disk.
     */
    fun onBufferContentChanged(path: String, content: String) {
        val before = bufferManager.isDirty(path)
        bufferManager.updateContent(path, content)
        val after = bufferManager.isDirty(path)
        if (before != after) {
            host.updateState {
                it.copy(editorDirtyPaths = bufferManager.dirtyBuffers().map { b -> b.path }.toSet())
            }
        }
        val project = host.state().activeProject ?: return
        scheduleSessionPersist(project.id, host.state().openEditorTabs, host.state().openedFilePath)
    }

    /** Called by the UI when cursor/selection/scroll moves. */
    fun onBufferViewStateChanged(path: String, viewState: EditorViewState) {
        bufferManager.updateViewState(path, viewState)
        val project = host.state().activeProject ?: return
        scheduleSessionPersist(project.id, host.state().openEditorTabs, host.state().openedFilePath)
    }

    /** Called by the UI when the split-editor configuration changes. */
    fun onSplitStateChanged(splitState: EditorSplitState) {
        host.updateState { it.copy(editorSplitState = splitState) }
        val project = host.state().activeProject ?: return
        scheduleSessionPersist(project.id, host.state().openEditorTabs, host.state().openedFilePath, splitState)
    }

    /**
     * Restores the persisted editor session for [project]: dirty buffers are
     * pre-loaded into the buffer manager so unsaved work reappears, and the
     * session is returned for the ViewModel to apply tabs/view state.
     * The buffer manager is cleared first (project switch).
     */
    fun restoreEditorSession(project: Project): EditorSession? {
        bufferManager.clear()
        val session = sessionStore.loadSession(project.id) ?: return null
        session.buffers.forEach { buffer ->
            bufferManager.openBuffer(buffer.path, buffer.savedContent)
            // Re-apply the persisted dirty state exactly.
            bufferManager.updateContent(buffer.path, buffer.currentContent)
            bufferManager.updateViewState(buffer.path, buffer.viewState)
        }
        // Restore view states for clean tabs too (they have no persisted buffer).
        // restoreViewState does not clamp: the buffer is a placeholder until
        // the file loads, and syncFromDisk clamps when real content arrives.
        session.viewStates.forEach { (path, viewState) ->
            bufferManager.restoreViewState(path, viewState)
        }
        host.updateState {
            it.copy(
                editorViewStates = bufferManager.allViewStates(),
                editorSplitState = session.splitState,
                editorDirtyPaths = bufferManager.dirtyBuffers().map { b -> b.path }.toSet(),
            )
        }
        return session
    }

    /** Drops all in-memory buffers (project switch/close). */
    fun clearBuffers() {
        sessionPersistJob?.cancel()
        bufferManager.clear()
        host.updateState {
            it.copy(
                editorViewStates = emptyMap(),
                editorSplitState = EditorSplitState(),
                editorDirtyPaths = emptySet(),
            )
        }
    }

    /** Deletes the persisted session (project deletion). */
    fun deleteEditorSession(projectId: String) {
        sessionStore.clearSession(projectId)
    }

    private fun scheduleSessionPersist(
        projectId: String,
        tabs: List<String>,
        activePath: String?,
        splitState: EditorSplitState = host.state().editorSplitState,
    ) {
        sessionPersistJob?.cancel()
        // Capture the buffers snapshot now; the debounced write uses it.
        val dirtyBuffers = bufferManager.dirtyBuffers()
        val viewStates = bufferManager.allViewStates()
        sessionPersistJob = host.scope.launch {
            delay(SESSION_PERSIST_DEBOUNCE_MS)
            val session = EditorSession(
                projectId = projectId,
                tabs = tabs,
                activePath = activePath,
                buffers = dirtyBuffers,
                viewStates = viewStates,
                splitState = splitState,
            )
            withContext(Dispatchers.IO) { sessionStore.saveSession(session) }
        }
    }

    /** Plans a rename; returns the preview for the UI to review. */
    suspend fun planRenameSymbol(
        oldName: String,
        newName: String,
    ): com.vayro.app.workspace.ReplacePreview? = withContext(Dispatchers.IO) {
        // QA H31: the preview walks the whole workspace tree + reads
        // every matching file. That used to run on the main thread
        // (called synchronously from the rename dialog's Preview button).
        val project = host.state().activeProject ?: return@withContext null
        RenameEngine.planRename(workspaceRootFor(project), oldName, newName)
    }

    /**
     * Applies a rename plan and refreshes the open buffer if it was affected.
     * Toasts the human-readable summary (the UI previously received the
     * summary as a return value and ignored it — no confirmation shown).
     * Only the disk work runs on IO: buffer refresh stays on the caller's
     * (main) thread because EditorBufferManager is not synchronized.
     */
    suspend fun applyRenameSymbol(
        preview: com.vayro.app.workspace.ReplacePreview,
        includeFiles: Set<String>? = null,
    ) {
        val project = host.state().activeProject ?: return
        if (preview.note != null && preview.totalFiles == 0) return
        val transaction = withContext(Dispatchers.IO) {
            // QA H31: file readText/writes across every matching file.
            RenameEngine.applyRename(workspaceRootFor(project), preview, includeFiles)
        }
        lastRenameTransaction = transaction
        // Refresh open buffers whose files changed on disk.
        transaction.appliedFiles.forEach { path ->
            val buffer = bufferManager.getBuffer(path)
            if (buffer != null && !buffer.isDirty) {
                val diskContent = runCatching {
                    File(workspaceRootFor(project), path).readText()
                }.getOrNull()
                if (diskContent != null) {
                    bufferManager.syncFromDisk(path, diskContent)
                    if (host.state().openedFilePath == path) {
                        host.updateState { it.copy(openedFileContent = diskContent) }
                    }
                }
            }
        }
        val summary = "Renamed ${transaction.matchesReplaced} occurrence(s) in ${transaction.filesChanged} file(s)"
        host.updateState {
            it.copy(
                editorDirtyPaths = bufferManager.dirtyBuffers().map { b -> b.path }.toSet(),
                toastMessage = summary,
            )
        }
    }

    /** Undoes the last rename; returns true when something was restored. */
    suspend fun undoLastRename(): Boolean {
        val transaction = lastRenameTransaction ?: return false
        val project = host.state().activeProject ?: return false
        // QA H31: revert writes every restored file — disk work on IO.
        val restored = withContext(Dispatchers.IO) {
            RenameEngine.undoRename(workspaceRootFor(project), transaction)
        }
        if (restored > 0) {
            lastRenameTransaction = null
            transaction.appliedFiles.forEach { path ->
                val buffer = bufferManager.getBuffer(path)
                if (buffer != null && !buffer.isDirty) {
                    val diskContent = runCatching {
                        File(workspaceRootFor(project), path).readText()
                    }.getOrNull()
                    if (diskContent != null) {
                        bufferManager.syncFromDisk(path, diskContent)
                        if (host.state().openedFilePath == path) {
                            host.updateState { it.copy(openedFileContent = diskContent) }
                        }
                    }
                }
            }
            host.updateState { it.copy(toastMessage = "Rename undone ($restored file(s) restored)") }
            return true
        }
        host.updateState { it.copy(toastMessage = "Nothing to undo") }
        return false
    }
}

/**
 * Shared workspace-metadata predicate (Milestone 1).
 *
 * Identical logic to the former `MainViewModel.isClaudeRuntimeMetadata`
 * private helper; promoted to package-internal so both the coordinator and
 * the ViewModel (e.g. `refreshProjectFiles`) share one definition.
 */
internal fun isClaudeRuntimeMetadata(relativePath: String): Boolean =
    relativePath == ".claude" ||
        relativePath == ".claude.json" ||
        relativePath.startsWith(".claude/")
