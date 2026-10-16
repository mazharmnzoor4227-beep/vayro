package com.vayro.app.context

/**
 * Milestone 4 — context trace.
 *
 * Every retrieval decision is recorded: which signal fired, how much score it
 * contributed, whether the file was included, and why. The trace answers the
 * acceptance-gate question directly: "why was this file retrieved, and why
 * was that one excluded?"
 */
enum class TraceSignal {
    EXPLICIT_MENTION,
    ERROR_TARGET,
    GIT_MODIFIED,
    SYMBOL_GRAPH,
    DEPENDENCY,
    FILENAME_MATCH,
    SYMBOL_MATCH,
    IMPORT_MATCH,
    MEMORY_HIT,
    KEYWORD,
}

data class TraceEntry(
    val signal: TraceSignal,
    val filePath: String,
    /** Score points contributed by this signal (0 when the file was excluded). */
    val scoreContribution: Int,
    val included: Boolean,
    val detail: String,
)

/**
 * Mutable builder collected during ranking; call [build] for the immutable trace.
 */
class ContextTraceBuilder {
    private val entries = mutableListOf<TraceEntry>()

    fun record(
        signal: TraceSignal,
        filePath: String,
        scoreContribution: Int,
        included: Boolean,
        detail: String,
    ) {
        entries.add(TraceEntry(signal, filePath, scoreContribution, included, detail))
    }

    fun build(): ContextTrace = ContextTrace(entries.toList())
}

data class ContextTrace(val entries: List<TraceEntry>) {

    val signalsUsed: Set<TraceSignal> get() = entries.map { it.signal }.toSet()

    fun includedPaths(): List<String> =
        entries.filter { it.included }.map { it.filePath }.distinct()

    fun excludedPaths(): List<String> =
        entries.filter { !it.included }.map { it.filePath }.distinct()

    fun entriesFor(path: String): List<TraceEntry> = entries.filter { it.filePath == path }

    fun totalScoreFor(path: String): Int =
        entries.filter { it.filePath == path }.sumOf { it.scoreContribution }

    /**
     * Human-readable rendering for the acceptance gate: shows, per file,
     * which signals fired and why it was included or excluded.
     */
    fun renderText(): String = buildString {
        appendLine("=== Context Trace ===")
        appendLine("Signals used: ${signalsUsed.joinToString(", ")}")
        val byPath = entries.groupBy { it.filePath }
        val included = byPath.filter { (_, es) -> es.any { it.included } }
        val excluded = byPath.filter { (_, es) -> es.none { it.included } }
        appendLine("Included files: ${included.size}, excluded files: ${excluded.size}")
        for ((path, es) in included.toList().sortedByDescending { (_, e) -> e.sumOf { it.scoreContribution } }) {
            appendLine("- INCLUDED $path (score ${es.sumOf { it.scoreContribution }})")
            es.forEach { appendLine("    [${it.signal}] +${it.scoreContribution}: ${it.detail}") }
        }
        for ((path, es) in excluded.toList().sortedBy { it.first }) {
            appendLine("- excluded $path: ${es.first().detail}")
        }
    }.trimEnd()

    companion object {
        fun empty(): ContextTrace = ContextTrace(emptyList())
    }
}
