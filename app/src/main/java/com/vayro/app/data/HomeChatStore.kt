package com.vayro.app.data

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.2.37 (Part F): one message in the home (normal) chat.
 *
 * [kind] is one of:
 *  - "text"      — an ordinary user/assistant message,
 *  - "system"    — a small centered line (e.g. "Switched to key 2 — key 1 limit reached"),
 *  - "handoff"   — the "This is a job for Work" card; [taskText] holds the job text,
 *  - "work_note" — "Moved to Work · <projectName>" left in the thread after a handoff.
 */
data class HomeChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val kind: String = "text",
    val taskText: String = "",
    val projectName: String = "",
    // v3.2.40 (Scope F): long pasted content carried as the message's
    // TXT chip — rendered as a card, sent to the model in full.
    val pastedText: String = "",
) {
    /** The full text the model receives: typed text + pasted content. */
    fun modelText(): String =
        listOf(text.trim(), pastedText.trim()).filter { it.isNotBlank() }.joinToString("\n\n")
}

/** A persisted home conversation. Home threads are NOT workspace projects. */
data class HomeThread(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val messages: List<HomeChatMessage> = emptyList(),
)

/**
 * Persists home-chat threads as JSON under filesDir. Deliberately separate
 * from the project store: a home thread has no folder, no runtime session
 * and never appears in the workspace project list unless the user promotes
 * a task through the "Open in Work" handoff, which creates a real project
 * and leaves only a "Moved to Work" note here.
 */
class HomeChatStore(context: Context) {
    private val file = File(context.filesDir, "home_chat.json")

    fun load(): List<HomeThread> = runCatching {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        val array = root.optJSONArray("threads") ?: return emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val thread = array.optJSONObject(i) ?: continue
                val messages = buildList {
                    val msgs = thread.optJSONArray("messages") ?: JSONArray()
                    for (j in 0 until msgs.length()) {
                        val m = msgs.optJSONObject(j) ?: continue
                        add(
                            HomeChatMessage(
                                id = m.optString("id").ifBlank { UUID.randomUUID().toString() },
                                fromUser = m.optBoolean("fromUser"),
                                text = m.optString("text"),
                                createdAtMillis = m.optLong("createdAtMillis"),
                                kind = m.optString("kind").ifBlank { "text" },
                                taskText = m.optString("taskText"),
                                projectName = m.optString("projectName"),
                                pastedText = m.optString("pastedText"),
                            ),
                        )
                    }
                }
                add(
                    HomeThread(
                        id = thread.optString("id").ifBlank { UUID.randomUUID().toString() },
                        title = thread.optString("title").ifBlank { "New chat" },
                        updatedAtMillis = thread.optLong("updatedAtMillis"),
                        messages = messages,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(threads: List<HomeThread>) {
        runCatching {
            val array = JSONArray()
            threads.forEach { thread ->
                val messages = JSONArray()
                thread.messages.forEach { m ->
                    messages.put(
                        JSONObject()
                            .put("id", m.id)
                            .put("fromUser", m.fromUser)
                            .put("text", m.text)
                            .put("createdAtMillis", m.createdAtMillis)
                            .put("kind", m.kind)
                            .put("taskText", m.taskText)
                            .put("projectName", m.projectName)
                            .put("pastedText", m.pastedText),
                    )
                }
                array.put(
                    JSONObject()
                        .put("id", thread.id)
                        .put("title", thread.title)
                        .put("updatedAtMillis", thread.updatedAtMillis)
                        .put("messages", messages),
                )
            }
            val tmp = File(file.parentFile, "home_chat.json.tmp")
            tmp.writeText(JSONObject().put("threads", array).toString())
            tmp.renameTo(file)
        }
    }
}
