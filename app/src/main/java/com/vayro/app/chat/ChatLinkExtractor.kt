package com.vayro.app.chat

/**
 * Pulls openable links out of agent chat messages so the UI can offer each
 * one as a card with Copy / Open actions. Understands full http(s) URLs,
 * bare localhost / 127.0.0.1 preview addresses (with port and path), and
 * bare www. domains. Results are normalised (bare hosts gain a scheme),
 * de-duplicated, and kept in the order they appear in the text.
 */
object ChatLinkExtractor {

    // Characters that end a URL in prose/markdown and are never part of it here.
    private const val DELIMITERS = "\\s<>\"'`()\\[\\]{}"

    private val schemeUrlRegex = Regex("https?://[^$DELIMITERS]+")
    private val bareLocalRegex = Regex("(?:localhost|127\\.0\\.0\\.1)(?::\\d{1,5})?(?:/[^$DELIMITERS]*)?")
    private val bareWwwRegex = Regex("www\\.[^$DELIMITERS]+")

    /** Sentence punctuation that clings to the end of a pasted URL. */
    private const val TRAILING_JUNK = ".,;:!?"

    fun extract(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        data class Candidate(val start: Int, val url: String)

        val found = mutableListOf<Candidate>()
        val schemeRanges = mutableListOf<IntRange>()

        schemeUrlRegex.findAll(text).forEach { match ->
            schemeRanges += match.range
            clean(match.value)?.let { found += Candidate(match.range.first, it) }
        }

        fun addBare(match: MatchResult, prefix: String) {
            // Skip bare matches that are the tail of a full scheme URL.
            if (schemeRanges.any { match.range.first in it }) return
            clean(match.value)?.let { found += Candidate(match.range.first, prefix + it) }
        }

        bareLocalRegex.findAll(text).forEach { addBare(it, "http://") }
        bareWwwRegex.findAll(text).forEach { addBare(it, "https://") }

        val seen = LinkedHashSet<String>()
        return found
            .sortedBy { it.start }
            .map { it.url }
            .filter { seen.add(it) }
    }

    /** Shortens [text] in the middle so both the host and the tail stay readable. */
    fun ellipsizeMiddle(text: String, maxChars: Int): String {
        if (maxChars < 4 || text.length <= maxChars) return text
        val keep = maxChars - 1 // one char for the ellipsis
        val head = (keep * 0.6).toInt().coerceAtLeast(1)
        val tail = (keep - head).coerceAtLeast(1)
        return text.take(head) + "…" + text.takeLast(tail)
    }

    private fun clean(candidate: String): String? {
        val trimmed = candidate.trimEnd { it in TRAILING_JUNK }
        if (trimmed.isBlank()) return null
        // A bare host with no scheme and no content (e.g. just "www.") is not a link.
        if (!trimmed.contains("://") && !trimmed.contains('.') && !trimmed.contains(':')) return null
        return trimmed
    }
}
