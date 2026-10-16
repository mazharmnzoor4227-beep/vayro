package com.vayro.app.runtime

import com.vayro.app.workspace.SafeFileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class MemoryCategory {
    PROJECT,
    ARCHITECTURE,
    USER_PREFERENCE,
    SESSION,
    CONVERSATION,
    ERROR,
    PREVIOUS_IMPLEMENTATION,
    ERROR_FIX,
    CONVENTION,
    DEPENDENCY,
}

data class ProjectMemoryItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val category: MemoryCategory,
    val key: String,
    val content: String,
    val confidence: Float = 1.0f,
    val timestampMillis: Long = System.currentTimeMillis(),
    /** Milestone 4: where this memory came from (file path, conversation ref, URL...). */
    val sourceRef: String? = null,
)

data class ProjectMemorySnapshot(
    val projectId: String,
    val items: List<ProjectMemoryItem>,
)

/**
 * Durable project memory store. Persists architectural rules, previous error fixes,
 * conventions, and preferences across sessions.
 */
class ProjectMemoryStore(private val baseDir: File) {

    private fun memoryFile(projectId: String): File =
        File(baseDir, "project_memory/$projectId.json")

    fun load(projectId: String): ProjectMemorySnapshot {
        val file = memoryFile(projectId)
        if (!file.isFile) return ProjectMemorySnapshot(projectId, emptyList())
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val array = json.optJSONArray("items") ?: JSONArray()
            val items = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { obj ->
                    ProjectMemoryItem(
                        id = obj.optString("id", UUID.randomUUID().toString().take(8)),
                        category = runCatching { MemoryCategory.valueOf(obj.getString("category")) }.getOrDefault(MemoryCategory.CONVENTION),
                        key = obj.optString("key"),
                        content = obj.optString("content"),
                        confidence = obj.optDouble("confidence", 1.0).toFloat(),
                        timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                        sourceRef = obj.optString("sourceRef").takeIf { it.isNotEmpty() },
                    )
                }
            }
            ProjectMemorySnapshot(projectId, items)
        }.getOrDefault(ProjectMemorySnapshot(projectId, emptyList()))
    }

    fun record(
        projectId: String,
        category: MemoryCategory,
        key: String,
        content: String,
        confidence: Float = 1.0f,
        sourceRef: String? = null,
    ): ProjectMemoryItem {
        val existing = load(projectId)
        val filtered = existing.items.filterNot { it.category == category && it.key.equals(key, ignoreCase = true) }
        val newItem = ProjectMemoryItem(
            category = category,
            key = key,
            content = content,
            confidence = confidence,
            sourceRef = sourceRef,
        )
        val updatedList = (filtered + newItem).takeLast(MAX_ITEMS_PER_PROJECT)
        persist(projectId, updatedList)
        return newItem
    }

    /**
     * Milestone 4: explicit edit control for a memory item. Only the supplied
     * fields change; the id, category and original timestamp are preserved and
     * a fresh edit is NOT re-stamped as new knowledge (confidence may be
     * adjusted by the caller). Returns the updated item, or null when the id
     * does not exist.
     */
    fun updateMemory(
        projectId: String,
        id: String,
        key: String? = null,
        content: String? = null,
        confidence: Float? = null,
        sourceRef: String? = null,
    ): ProjectMemoryItem? {
        val snapshot = load(projectId)
        val existing = snapshot.items.firstOrNull { it.id == id } ?: return null
        val updated = existing.copy(
            key = key ?: existing.key,
            content = content ?: existing.content,
            confidence = confidence ?: existing.confidence,
            sourceRef = sourceRef ?: existing.sourceRef,
        )
        val updatedList = snapshot.items.map { if (it.id == id) updated else it }
        persist(projectId, updatedList)
        return updated
    }

    fun recordProjectMetadata(projectId: String, key: String, content: String, confidence: Float = 1.0f): ProjectMemoryItem =
        record(projectId, MemoryCategory.PROJECT, key, content, confidence)

    fun recordArchitectureRule(projectId: String, key: String, content: String, confidence: Float = 1.0f): ProjectMemoryItem =
        record(projectId, MemoryCategory.ARCHITECTURE, key, content, confidence)

    fun recordUserPreference(projectId: String, key: String, content: String, confidence: Float = 1.0f): ProjectMemoryItem =
        record(projectId, MemoryCategory.USER_PREFERENCE, key, content, confidence)

    fun recordSessionGoal(projectId: String, key: String, content: String, confidence: Float = 1.0f): ProjectMemoryItem =
        record(projectId, MemoryCategory.SESSION, key, content, confidence)

    fun recordConversationDecision(projectId: String, key: String, content: String, confidence: Float = 1.0f): ProjectMemoryItem =
        record(projectId, MemoryCategory.CONVERSATION, key, content, confidence)

    fun recordErrorFix(projectId: String, key: String, content: String, confidence: Float = 1.0f): ProjectMemoryItem =
        record(projectId, MemoryCategory.ERROR, key, content, confidence)

    fun recordPreviousImplementation(projectId: String, key: String, content: String, confidence: Float = 1.0f): ProjectMemoryItem =
        record(projectId, MemoryCategory.PREVIOUS_IMPLEMENTATION, key, content, confidence)

    fun getMemories(projectId: String, category: MemoryCategory? = null): List<ProjectMemoryItem> {
        val snapshot = load(projectId)
        return if (category == null) snapshot.items else snapshot.items.filter { it.category == category }
    }

    /**
     * Searches memories by query string across key and content, ranked by relevance and confidence.
     */
    fun search(projectId: String, query: String, limit: Int = 10): List<ProjectMemoryItem> {
        val tokens = query.lowercase().split(Regex("[^a-zA-Z0-9_]")).filter { it.length > 2 }
        if (tokens.isEmpty()) return getMemories(projectId).take(limit)

        val items = load(projectId).items
        return items.map { item ->
            val keyLower = item.key.lowercase()
            val contentLower = item.content.lowercase()
            var score = 0
            tokens.forEach { token ->
                if (keyLower.contains(token)) score += 3
                if (contentLower.contains(token)) score += 1
            }
            item to (score * item.confidence)
        }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
    }

    fun deleteMemory(projectId: String, id: String): Boolean {
        val snapshot = load(projectId)
        val filtered = snapshot.items.filterNot { it.id == id }
        if (filtered.size == snapshot.items.size) return false
        persist(projectId, filtered)
        return true
    }

    fun clear(projectId: String) {
        val file = memoryFile(projectId)
        if (file.exists()) file.delete()
    }

    private fun persist(projectId: String, items: List<ProjectMemoryItem>) {
        val file = memoryFile(projectId)
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("category", item.category.name)
                    .put("key", item.key)
                    .put("content", item.content)
                    .put("confidence", item.confidence.toDouble())
                    .put("timestampMillis", item.timestampMillis)
                    .put("sourceRef", item.sourceRef ?: "")
            )
        }
        val rootJson = JSONObject()
            .put("projectId", projectId)
            .put("items", array)

        SafeFileOps.atomicWriteText(file, rootJson.toString())
    }

    companion object {
        private const val MAX_ITEMS_PER_PROJECT = 100
    }
}
