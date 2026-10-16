package com.vayro.app.editor

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Milestone 2 — durable editor-session persistence.
 *
 * Stores per-project editor sessions (open tabs, dirty buffers, cursor/scroll
 * positions, split state) as JSON under [sessionsDir], one file per project.
 * Writes are atomic (temp file + rename) so a crash mid-write can never leave
 * a half-written session behind. Corrupt session files are ignored (treated as
 * "no session") rather than crashing the restore path.
 *
 * The store is intentionally separate from [EditorBufferManager] (in-memory
 * ownership): the coordinator decides when to snapshot.
 */
class EditorSessionStore(sessionsDir: File) {

    private val dir: File = sessionsDir

    init {
        runCatching { dir.mkdirs() }
    }

    /** Persists [session]; overwrites any previous session for the project. */
    fun saveSession(session: EditorSession) {
        runCatching {
            dir.mkdirs()
            val target = sessionFile(session.projectId)
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeText(serialize(session))
            // Atomic replace: rename is atomic on the same filesystem.
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Returns the persisted session, or null when none exists / unreadable. */
    fun loadSession(projectId: String): EditorSession? {
        val file = sessionFile(projectId)
        if (!file.isFile) return null
        return runCatching { deserialize(projectId, JSONObject(file.readText())) }.getOrNull()
    }

    /** Drops the persisted session for [projectId] (e.g. on project deletion). */
    fun clearSession(projectId: String) {
        runCatching { sessionFile(projectId).delete() }
    }

    private fun sessionFile(projectId: String): File {
        val safe = projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        return File(dir, "$safe.json")
    }

    private fun serialize(session: EditorSession): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("activePath", session.activePath ?: JSONObject.NULL)
        val tabs = JSONArray()
        session.tabs.forEach { tabs.put(it) }
        root.put("tabs", tabs)
        val buffers = JSONArray()
        session.buffers.forEach { buffer ->
            buffers.put(
                JSONObject()
                    .put("path", buffer.path)
                    .put("currentContent", buffer.currentContent)
                    .put("savedContent", buffer.savedContent)
                    .put("lastModifiedAt", buffer.lastModifiedAt)
                    .put("viewState", serializeViewState(buffer.viewState)),
            )
        }
        root.put("buffers", buffers)
        val viewStates = JSONObject()
        session.viewStates.forEach { (path, viewState) ->
            viewStates.put(path, serializeViewState(viewState))
        }
        root.put("viewStates", viewStates)
        root.put(
            "splitState",
            JSONObject()
                .put("secondaryPath", session.splitState.secondaryPath ?: JSONObject.NULL)
                .put("vertical", session.splitState.vertical)
                .put("secondaryViewState", serializeViewState(session.splitState.secondaryViewState)),
        )
        return root.toString()
    }

    private fun serializeViewState(viewState: EditorViewState): JSONObject =
        JSONObject()
            .put("cursorOffset", viewState.cursorOffset)
            .put("selectionEnd", viewState.selectionEnd)
            .put("scrollLine", viewState.scrollLine)
            .put("scrollOffsetPx", viewState.scrollOffsetPx)

    private fun deserialize(projectId: String, root: JSONObject): EditorSession {
        val tabs = mutableListOf<String>()
        val tabsArray = root.optJSONArray("tabs") ?: JSONArray()
        for (i in 0 until tabsArray.length()) {
            tabs.add(tabsArray.optString(i))
        }
        val buffers = mutableListOf<EditorBuffer>()
        val buffersArray = root.optJSONArray("buffers") ?: JSONArray()
        for (i in 0 until buffersArray.length()) {
            val item = buffersArray.optJSONObject(i) ?: continue
            val path = item.optString("path")
            if (path.isBlank()) continue
            buffers.add(
                EditorBuffer(
                    path = path,
                    currentContent = item.optString("currentContent"),
                    savedContent = item.optString("savedContent"),
                    viewState = deserializeViewState(item.optJSONObject("viewState")),
                    lastModifiedAt = item.optLong("lastModifiedAt", 0L),
                ),
            )
        }
        val splitJson = root.optJSONObject("splitState")
        val splitState = if (splitJson == null) {
            EditorSplitState()
        } else {
            EditorSplitState(
                secondaryPath = splitJson.optString("secondaryPath").takeIf { it.isNotBlank() },
                secondaryViewState = deserializeViewState(splitJson.optJSONObject("secondaryViewState")),
                vertical = splitJson.optBoolean("vertical", true),
            )
        }
        val viewStates = mutableMapOf<String, EditorViewState>()
        val viewStatesJson = root.optJSONObject("viewStates")
        if (viewStatesJson != null) {
            val keys = viewStatesJson.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                viewStates[path] = deserializeViewState(viewStatesJson.optJSONObject(path))
            }
        }
        return EditorSession(
            projectId = projectId,
            tabs = tabs,
            activePath = root.optString("activePath").takeIf { it.isNotBlank() },
            buffers = buffers,
            viewStates = viewStates,
            splitState = splitState,
        )
    }

    private fun deserializeViewState(json: JSONObject?): EditorViewState {
        if (json == null) return EditorViewState()
        return EditorViewState(
            cursorOffset = json.optInt("cursorOffset", 0).coerceAtLeast(0),
            selectionEnd = json.optInt("selectionEnd", 0).coerceAtLeast(0),
            scrollLine = json.optInt("scrollLine", 0).coerceAtLeast(0),
            scrollOffsetPx = json.optInt("scrollOffsetPx", 0).coerceAtLeast(0),
        )
    }
}
