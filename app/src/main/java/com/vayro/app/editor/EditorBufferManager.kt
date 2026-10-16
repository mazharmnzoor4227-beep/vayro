package com.vayro.app.editor

/**
 * Milestone 2 — in-memory owner of open editor buffers.
 *
 * Single source of truth for "what does each open tab contain right now",
 * including unsaved edits. The [EditorCoordinator] drives this manager from
 * UI events; [EditorSessionStore] snapshots it to disk.
 *
 * Threading: all methods are expected to be called from the main thread (via
 * the coordinator). No locking is added — keep it that way.
 */
class EditorBufferManager {

    private val buffers = mutableMapOf<String, EditorBuffer>()

    /** Opens (or returns the existing) buffer for [path], seeded from disk. */
    fun openBuffer(path: String, diskContent: String): EditorBuffer {
        return buffers.getOrPut(path) {
            EditorBuffer(path = path, currentContent = diskContent, savedContent = diskContent)
        }
    }

    /**
     * Records an edit. Creates the buffer if needed (seeded with [content] as
     * both current and saved — a brand-new buffer has nothing unsaved yet only
     * when the caller passes the on-disk content; prefer [openBuffer] first).
     */
    fun updateContent(path: String, content: String) {
        val existing = buffers[path]
        if (existing == null) {
            buffers[path] = EditorBuffer(path = path, currentContent = content, savedContent = content)
            return
        }
        if (existing.currentContent == content) return
        buffers[path] = existing.copy(
            currentContent = content,
            viewState = existing.viewState.clampedTo(content.length),
            lastModifiedAt = System.currentTimeMillis(),
        )
    }

    /** Records cursor/selection/scroll movement for [path]. */
    fun updateViewState(path: String, viewState: EditorViewState) {
        val existing = buffers[path] ?: return
        buffers[path] = existing.copy(
            viewState = viewState.clampedTo(existing.currentContent.length),
        )
    }

    /**
     * Restores a persisted view state without clamping (used for session
     * restore, where the buffer may be a placeholder until the file loads;
     * clamping happens when real content arrives via [syncFromDisk]).
     */
    fun restoreViewState(path: String, viewState: EditorViewState) {
        val existing = buffers[path] ?: run {
            buffers[path] = EditorBuffer(
                path = path,
                currentContent = "",
                savedContent = "",
                viewState = viewState,
            )
            return
        }
        buffers[path] = existing.copy(viewState = viewState)
    }

    /** Marks [path] as saved: disk and buffer agree again. */
    fun markSaved(path: String, content: String) {
        val existing = buffers[path] ?: run {
            buffers[path] = EditorBuffer(path = path, currentContent = content, savedContent = content)
            return
        }
        buffers[path] = existing.copy(currentContent = content, savedContent = content)
    }

    /** Marks [path] as matching the given on-disk content (e.g. after external change). */
    fun syncFromDisk(path: String, diskContent: String) {
        val existing = buffers[path] ?: run {
            buffers[path] = EditorBuffer(path = path, currentContent = diskContent, savedContent = diskContent)
            return
        }
        // Never clobber unsaved user edits with disk content.
        if (!existing.isDirty) {
            buffers[path] = existing.copy(
                currentContent = diskContent,
                savedContent = diskContent,
                viewState = existing.viewState.clampedTo(diskContent.length),
            )
        }
    }

    fun closeBuffer(path: String) {
        buffers.remove(path)
    }

    fun getBuffer(path: String): EditorBuffer? = buffers[path]

    fun isDirty(path: String): Boolean = buffers[path]?.isDirty == true

    /** All currently dirty buffers — the only ones persisted to the session store. */
    fun dirtyBuffers(): List<EditorBuffer> = buffers.values.filter { it.isDirty }

    fun allPaths(): Set<String> = buffers.keys.toSet()

    /** Cursor/scroll state for every open buffer (dirty or clean). */
    fun allViewStates(): Map<String, EditorViewState> =
        buffers.mapValues { (_, buffer) -> buffer.viewState }

    fun clear() {
        buffers.clear()
    }
}
