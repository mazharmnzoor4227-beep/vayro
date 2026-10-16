package com.vayro.app.editor

/**
 * Milestone 2 — persistent multi-tab/dirty-buffer editor model.
 *
 * Explicit owner of per-file editor state: the buffer content (including
 * unsaved/dirty edits), cursor/selection position and scroll position.
 * Dirty buffers survive process death via [EditorSessionStore]; the UI layer
 * (Compose) remains responsible only for rendering and reporting view-state
 * changes back through [EditorBufferManager].
 *
 * This model is deliberately UI-framework-free so it is fully unit-testable.
 */

/** Cursor/selection/scroll position for one editor buffer. */
data class EditorViewState(
    /** Cursor offset in characters from the start of the buffer. */
    val cursorOffset: Int = 0,
    /** Selection end offset; equals [cursorOffset] when there is no selection. */
    val selectionEnd: Int = 0,
    /** First visible line (0-based). */
    val scrollLine: Int = 0,
    /** Horizontal scroll offset in pixels. */
    val scrollOffsetPx: Int = 0,
) {
    /** Clamps offsets into [0, contentLength]; never throws on stale state. */
    fun clampedTo(contentLength: Int): EditorViewState {
        val safeCursor = cursorOffset.coerceIn(0, contentLength)
        val safeSelectionEnd = selectionEnd.coerceIn(0, contentLength)
        return copy(
            cursorOffset = safeCursor,
            selectionEnd = safeSelectionEnd,
            scrollLine = scrollLine.coerceAtLeast(0),
            scrollOffsetPx = scrollOffsetPx.coerceAtLeast(0),
        )
    }
}

/**
 * One open editor buffer.
 *
 * [currentContent] is what the user sees (may include unsaved edits);
 * [savedContent] is the last content written to disk. [isDirty] is derived —
 * never stored — so it cannot drift out of sync.
 */
data class EditorBuffer(
    val path: String,
    val currentContent: String,
    val savedContent: String,
    val viewState: EditorViewState = EditorViewState(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
) {
    val isDirty: Boolean get() = currentContent != savedContent
}

/** Split-editor state for the active file. */
data class EditorSplitState(
    val secondaryPath: String? = null,
    val secondaryViewState: EditorViewState = EditorViewState(),
    val vertical: Boolean = true,
)

/**
 * A restorable editor session for one project.
 *
 * Only dirty buffers are persisted ([buffers]); clean tabs are restored from
 * [tabs] and re-read from disk. [viewStates] carries cursor/scroll positions
 * for ALL open tabs (dirty or clean) so navigation state survives process
 * death. This keeps the session file small while guaranteeing no unsaved work
 * and no navigation state is ever lost.
 */
data class EditorSession(
    val projectId: String,
    val tabs: List<String> = emptyList(),
    val activePath: String? = null,
    val buffers: List<EditorBuffer> = emptyList(),
    val viewStates: Map<String, EditorViewState> = emptyMap(),
    val splitState: EditorSplitState = EditorSplitState(),
)
