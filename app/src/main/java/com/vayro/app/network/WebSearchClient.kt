package com.vayro.app.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** One result from the keyless web search. */
data class WebSearchHit(val title: String, val url: String, val snippet: String)

/**
 * v3.2.51: the app-side web search for home chat — keyless
 * DuckDuckGo (Lite first, HTML second), per the design spec §D.
 * There is no official keyless search API left standing (Bing's was
 * retired; Jina's search needs a key), so this is a gentle client of
 * DDG's public endpoints: a handful of queries per reply, an honest
 * user agent, and graceful emptiness on any failure — a bot wall or
 * a markup change degrades to "search didn't work", never a crash.
 */
class WebSearchClient {

    suspend fun search(query: String): List<WebSearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val lite = fetchHtml("https://lite.duckduckgo.com/lite/?q=$encoded")
        val liteHits = lite?.let { parseResults(it) }.orEmpty()
        if (liteHits.isNotEmpty()) return@withContext liteHits
        val html = fetchHtml("https://html.duckduckgo.com/html/?q=$encoded")
        html?.let { parseResults(it) }.orEmpty()
    }

    private fun fetchHtml(url: String): String? {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", WEB_USER_AGENT)
                setRequestProperty("Accept", "text/html,*/*;q=0.5")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream?.use { stream ->
                val reader = stream.bufferedReader(Charsets.UTF_8)
                val sb = StringBuilder()
                val buffer = CharArray(8192)
                while (sb.length < MAX_HTML_CHARS) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, MAX_HTML_CHARS - sb.length))
                    if (read <= 0) break
                    sb.append(buffer, 0, read)
                }
                sb.toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val MAX_HTML_CHARS = 1_000_000
        private const val MAX_HITS = 10

        /**
         * Parses a DDG Lite or HTML results page into hits. Both
         * shapes wrap result links in `duckduckgo.com/l/?uddg=`
         * redirects; snippets live in `result__snippet` /
         * `result-snippet` elements in the same document order, so
         * they pair with the (de-duplicated) links by index. A bot
         * wall parses to an empty list, by design.
         */
        internal fun parseResults(html: String): List<WebSearchHit> {
            if (html.isBlank()) return emptyList()
            val lower = html.lowercase()
            if ("anomaly" in lower && ("captcha" in lower || "bots use duckduckgo" in lower)) {
                return emptyList()
            }
            val snippets = SNIPPET.findAll(html)
                .map { cleanText(it.groupValues[1]) }
                .toList()
            val hits = mutableListOf<WebSearchHit>()
            val seen = LinkedHashSet<String>()
            for (match in ANCHOR.findAll(html)) {
                val target = resolveTarget(decodeHtmlEntities(match.groupValues[1])) ?: continue
                if (!seen.add(target)) continue
                val title = cleanText(match.groupValues[2])
                if (title.isBlank()) continue
                hits += WebSearchHit(title = title, url = target, snippet = "")
                if (hits.size >= MAX_HITS) break
            }
            return hits.mapIndexed { index, hit ->
                hit.copy(snippet = snippets.getOrElse(index) { "" })
            }
        }

        /** The real destination behind a DDG redirect link, or null for internal links. */
        private fun resolveTarget(href: String): String? {
            val trimmed = href.trim()
            val uddgAt = trimmed.indexOf("uddg=")
            if (uddgAt >= 0) {
                val raw = trimmed.substring(uddgAt + "uddg=".length).substringBefore('&')
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault("")
                return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            val absolute = when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> return null
            }
            // DDG's own pages (settings, images, the redirect host) are not results.
            if (absolute.contains("duckduckgo.com")) return null
            return absolute
        }

        private fun cleanText(raw: String): String =
            decodeHtmlEntities(stripHtmlTags(raw)).replace(WHITESPACE_RUN, " ").trim()

        private val WHITESPACE_RUN = Regex("\\s+")
        private val ANCHOR = Regex(
            "<a\\s[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val SNIPPET = Regex(
            "<[^>]*class=\"[^\"]*(?:result__snippet|result-snippet)[^\"]*\"[^>]*>(.*?)</(?:a|td|div)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
