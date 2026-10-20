package com.vayro.app.ui

import com.vayro.app.chat.ChatLinkExtractor
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * v3.2.51: decides whether a home-chat turn needs the live web, and
 * builds the "today" lines the model is given. Sibling of
 * [HomeTurnClassifier] — same pure-Kotlin, phrase-table style, and the
 * same law: this policy runs INSIDE the conversation branch only.
 * Classification (conversation vs Work) has already happened in
 * sendHomeMessage; a creation command never reaches this policy, and
 * this policy never creates tasks — it only plans web retrieval for a
 * conversational turn:
 *
 *  1. An explicit ask ("search", "look it up", "سर्च करो"...) fires a
 *     search with the user's own words.
 *  2. A time-sensitive marker (today / latest / news / price / آج /
 *     ताज़ा / aaj...) fires the same search plus one today-scoped
 *     variant query, so "what is happening today" is answered from
 *     today's web, not from training memory.
 *  3. A pasted link fires a page fetch regardless of freshness — the
 *     user handed the chat something to read.
 *
 * Everything else (maths, explanations, writing help, conversation)
 * plans nothing: no latency, no quota, exactly today's behaviour.
 */
object HomeFreshnessPolicy {

    /** What one home turn should retrieve from the web before answering. */
    data class WebPlan(
        val searchQueries: List<String> = emptyList(),
        val fetchUrls: List<String> = emptyList(),
    ) {
        val wantsWeb: Boolean get() = searchQueries.isNotEmpty() || fetchUrls.isNotEmpty()
        val wantsSearch: Boolean get() = searchQueries.isNotEmpty()
    }

    const val MAX_SEARCHES = 2
    const val MAX_FETCHES = 2
    const val MAX_QUERY_CHARS = 240

    /**
     * Plans the web work for [text] (the latest user turn's full model
     * text). [todayLabel] is today's date in "20 October 2026" form,
     * used to scope the second query when the user asked about today
     * without naming a date.
     */
    fun decide(text: String, todayLabel: String): WebPlan {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return WebPlan()
        val lower = trimmed.lowercase()

        val fetchUrls = ChatLinkExtractor.extract(trimmed)
            .filterNot { isLocalHost(it) }
            .take(MAX_FETCHES)

        val explicit = EXPLICIT_ASK.containsMatchIn(lower) ||
            SCRIPT_SEARCH_PHRASES.any { lower.contains(it) }
        val timeSensitive = TIME_MARKERS.containsMatchIn(lower) ||
            SCRIPT_TIME_PHRASES.any { lower.contains(it) }
        if (!explicit && !timeSensitive) return WebPlan(fetchUrls = fetchUrls)

        val base = trimmed.replace(WHITESPACE_RUN, " ").take(MAX_QUERY_CHARS)
        val queries = buildList {
            add(base)
            if (timeSensitive && !DATE_REFERENCE.containsMatchIn(lower)) {
                add("$base $todayLabel".take(MAX_QUERY_CHARS + 24))
            }
        }.distinct().take(MAX_SEARCHES)
        return WebPlan(searchQueries = queries, fetchUrls = fetchUrls)
    }

    // ------------------------------------------------------------------
    // "Today" lines — the model learns the date from the app, and the
    // fences record when the web content was retrieved.
    // ------------------------------------------------------------------

    private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    private val RETRIEVED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

    /** "20 October 2026" — scopes the today-variant search query. */
    fun todayLabel(now: ZonedDateTime): String = now.format(DATE_LABEL_FORMAT)

    /** The system-prompt date line, appended to every home request. */
    fun todayLine(now: ZonedDateTime): String =
        "Today is ${now.format(DAY_FORMAT)}. The current time is ${now.format(TIME_FORMAT)} " +
            "(${now.zone.id}). For anything that may have changed recently, prefer the web " +
            "results provided with the user's message over your training knowledge."

    /** "2026-10-20 17:05 PKT" — stamped into every web-context fence. */
    fun retrievedLabel(now: ZonedDateTime): String =
        now.format(RETRIEVED_FORMAT) + " " + now.zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    // ------------------------------------------------------------------
    // Signal tables (mirroring HomeTurnClassifier's phrase style)
    // ------------------------------------------------------------------

    private val WHITESPACE_RUN = Regex("\\s+")
    private val DATE_REFERENCE = Regex(
        "\\b(january|february|march|april|may|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b|\\b(19|20)\\d\\d\\b",
    )

    private val EXPLICIT_ASK = Regex(
        "\\b(search|searching|searched|google|look up|lookup|check online|find online|browse the web|" +
            "search karo|search kar|dhund|dhundo|dhoond|talash)\\b",
    )

    private val SCRIPT_SEARCH_PHRASES = listOf(
        // Devanagari
        "सर्च", "ढूंढ", "ढूँढ", "खोज करो", "तलाश",
        // Urdu script
        "سرچ", "تلاش کرو", "ڈھونڈ", "تلاش",
    )

    private val TIME_MARKERS = Regex(
        "\\b(today|now|right now|latest|current|currently|recent|recently|news|headline|headlines|" +
            "breaking|price|prices|weather|score|scores|live|tonight|yesterday|tomorrow|" +
            "this week|this morning|this evening|" +
            "aaj|aajkal|taza|taaza|abhi|khabar|khabrein|mausam|qeemat)\\b",
    )

    private val SCRIPT_TIME_PHRASES = listOf(
        // Devanagari
        "आज", "ताज़ा", "ताजा", "आजकल", "हाल ही", "खबर", "समाचार", "मौसम", "कीमत",
        // Urdu script
        "آج", "تازہ", "تازہ ترین", "خبر", "خبریں", "موسم", "قیمت", "آج کل",
    )

    /** Local preview addresses are the workspace's, not pages to read. */
    private fun isLocalHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }
}
