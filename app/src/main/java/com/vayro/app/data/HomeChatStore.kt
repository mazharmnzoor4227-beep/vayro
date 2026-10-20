package com.vayro.app.data

import android.content.Context
import androidx.compose.runtime.Immutable
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
// v3.2.42: @Immutable — home messages are snapshots (the store
// replaces them wholesale on change), so Compose can skip unchanged
// rows in the home thread list instead of recomposing them.
@Immutable
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
    // v3.2.40 (Scope G): images attached to this message — absolute
    // paths of downscaled JPEG copies under filesDir/home_images.
    // They are sent to the provider as real vision parts (latest
    // user turn) and rendered in the bubble.
    val imagePaths: List<String> = emptyList(),
    // v3.2.41 (Scope 3): a PDF/ZIP attached at home — [docName] is the
    // file's display name (rendered as a document card in the bubble)
    // and [docText] is the extracted content block that rides with
    // the message to the model, exactly like the pasted text.
    val docName: String = "",
    val docText: String = "",
    // v3.2.51: the web sources a reply actually rested on (injected
    // search results / fetched pages, or a native search route's own
    // citations) — rendered as link cards under the reply, so a
    // searched answer always shows where it came from.
    val sourceUrls: List<String> = emptyList(),
) {
    /** The full text the model receives: typed text + pasted content + attached document content. */
    fun modelText(): String =
        listOf(text.trim(), pastedText.trim(), docText.trim()).filter { it.isNotBlank() }.joinToString("\n\n")
}

/**
 * v3.2.45 (Item 3): history shaping for the home Regenerate action.
 * Regenerating re-answers the thread's LAST user turn: the last
 * assistant "text" reply is dropped and everything before it is kept
 * as the history for the fresh request (system lines and cards that
 * came after that reply go with it — they described the dropped
 * answer). Returns null when there is nothing to regenerate: no
 * assistant reply, or no user turn underneath it.
 */
object HomeRegenerate {
    fun historyWithoutLastReply(messages: List<HomeChatMessage>): List<HomeChatMessage>? {
        val replyIndex = messages.indexOfLast { !it.fromUser && it.kind == "text" }
        if (replyIndex < 0) return null
        val history = messages.subList(0, replyIndex)
        if (history.none { it.fromUser && it.kind == "text" }) return null
        return history
    }
}

/** v3.2.51: the message ↔ JSON mapping, extracted so the round-trip (incl. sourceUrls) is unit-testable. */
internal fun HomeChatMessage.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("fromUser", fromUser)
    .put("text", text)
    .put("createdAtMillis", createdAtMillis)
    .put("kind", kind)
    .put("taskText", taskText)
    .put("projectName", projectName)
    .put("pastedText", pastedText)
    .put("docName", docName)
    .put("docText", docText)
    .put("imagePaths", JSONArray(imagePaths))
    .put("sourceUrls", JSONArray(sourceUrls))

internal fun homeChatMessageFromJson(m: JSONObject): HomeChatMessage {
    fun stringList(key: String): List<String> = buildList {
        val arr = m.optJSONArray(key)
        if (arr != null) {
            for (k in 0 until arr.length()) {
                arr.optString(k).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }
    return HomeChatMessage(
        id = m.optString("id").ifBlank { UUID.randomUUID().toString() },
        fromUser = m.optBoolean("fromUser"),
        text = m.optString("text"),
        createdAtMillis = m.optLong("createdAtMillis"),
        kind = m.optString("kind").ifBlank { "text" },
        taskText = m.optString("taskText"),
        projectName = m.optString("projectName"),
        pastedText = m.optString("pastedText"),
        docName = m.optString("docName"),
        docText = m.optString("docText"),
        imagePaths = stringList("imagePaths"),
        sourceUrls = stringList("sourceUrls"),
    )
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
                        add(homeChatMessageFromJson(m))
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
                    messages.put(m.toJson())
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
