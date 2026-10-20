package com.vayro.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v3.2.52: the persistent last-known connection state for one agent,
 * shown as ONE quiet line under the Test button on the AI Agent screen
 * ("Last check: Connected · 6:12 PM").
 *
 * Why this exists: a connection test's verdict used to live only in
 * the transient ping state — leave the screen and the app forgot
 * whether the agent had ever answered, so a slow-but-working agent
 * looked identical to a broken one. The record survives restarts
 * (stored via AppPreferences, keyed by the agent's stable id) and is
 * refreshed by both proofs of life: a finished connection test and a
 * finished workspace turn.
 *
 * Pure JVM (no Android types) so the format is unit-tested.
 */
object AgentConnectionState {

    enum class Outcome {
        /** A connection test finished successfully. */
        TEST_CONNECTED,

        /** A workspace turn on this agent completed with a reply. */
        TASK_ANSWERED,

        /** A connection test finished with a failure. */
        TEST_FAILED,

        /** A workspace turn ended on a substantive failure. */
        TASK_FAILED,
    }

    data class Record(
        val outcome: Outcome,
        /** Short, single-line reason for failures; blank otherwise. */
        val reason: String,
        val atMillis: Long,
    )

    /** Reasons are status-line summaries, never full error dumps. */
    private const val MAX_REASON_CHARS = 90

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** Collapses a raw failure reason into one short, single line. */
    fun shortenReason(raw: String): String {
        val collapsed = raw.replace(Regex("\\s+"), " ").trim()
        if (collapsed.length <= MAX_REASON_CHARS) return collapsed
        return collapsed.take(MAX_REASON_CHARS - 1).trimEnd() + "…"
    }

    /** `OUTCOME|millis|reason` — the reason is sanitised before joining. */
    fun encode(record: Record): String {
        val safeReason = shortenReason(record.reason)
            .replace("|", "/")
        return "${record.outcome.name}|${record.atMillis}|$safeReason"
    }

    fun decode(raw: String?): Record? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("|", limit = 3)
        if (parts.size < 2) return null
        val outcome = runCatching { Outcome.valueOf(parts[0]) }.getOrNull() ?: return null
        val atMillis = parts[1].toLongOrNull() ?: return null
        return Record(outcome, parts.getOrNull(2).orEmpty(), atMillis)
    }

    /** The one quiet line rendered under the agent's Test button. */
    fun statusLine(record: Record, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochMilli(record.atMillis).atZone(zone).format(TIME_FORMAT)
        val reasonSuffix = record.reason
            .takeIf { it.isNotBlank() }
            ?.let { " — $it" }
            .orEmpty()
        return when (record.outcome) {
            Outcome.TEST_CONNECTED -> "Last check: Connected · $time"
            Outcome.TASK_ANSWERED -> "Last task answered · $time"
            Outcome.TEST_FAILED -> "Last check failed · $time$reasonSuffix"
            Outcome.TASK_FAILED -> "Last task failed · $time$reasonSuffix"
        }
    }
}
