package com.vayro.app.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Decodes the HTML entities that matter for extracted text. */
internal fun decodeHtmlEntities(text: String): String {
    if (!text.contains('&')) return text
    var out = NUMERIC_ENTITY.replace(text) { match ->
        val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
    }
    out = HEX_ENTITY.replace(out) { match ->
        val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
        runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
    }
    NAMED_ENTITIES.forEach { (entity, replacement) ->
        out = out.replace(entity, replacement)
    }
    // &amp; decodes last so "&amp;lt;" stays the literal text "&lt;".
    return out.replace("&amp;", "&")
}

private val NUMERIC_ENTITY = Regex("&#(\\d+);")
private val HEX_ENTITY = Regex("&#[xX]([0-9a-fA-F]+);")
private val NAMED_ENTITIES = listOf(
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&#39;" to "'",
    "&nbsp;" to " ",
    "&rsquo;" to "’",
    "&lsquo;" to "‘",
    "&rdquo;" to "”",
    "&ldquo;" to "“",
    "&mdash;" to "—",
    "&ndash;" to "–",
    "&hellip;" to "…",
    "&copy;" to "©",
)

private val ANY_TAG = Regex("<[^>]+>")

/** Removes every remaining tag, leaving the text between them. */
internal fun stripHtmlTags(html: String): String = ANY_TAG.replace(html, "")

/**
 * v3.2.51: reads a web page for the home chat ("open this link").
 * Direct fetch first (browser-like UA, bounded in time and bytes)
 * with a small hand-rolled HTML→text extractor; when the direct
 * route fails or yields boilerplate only, the keyless Jina Reader
 * is the fallback. Returns null when neither route produced text —
 * the caller tells the model the page could not be read instead of
 * letting it guess from the URL.
 */
class WebPageFetcher {

    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        val target = normalize(url) ?: return@withContext null
        val direct = fetchDirect(target)
        val extracted = if (direct == null) "" else extractText(direct)
        if (extracted.length >= MIN_USEFUL_CHARS) {
            return@withContext extracted.take(MAX_PAGE_CHARS)
        }
        val jina = fetchJina(target)?.trim().orEmpty()
        return@withContext when {
            jina.length > extracted.length -> jina.take(MAX_PAGE_CHARS)
            extracted.isNotBlank() -> extracted.take(MAX_PAGE_CHARS)
            else -> null
        }
    }

    /** https only, except local preview addresses which stay http. */
    private fun normalize(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> {
                val host = runCatching { URL(trimmed).host }.getOrNull()?.lowercase().orEmpty()
                if (host == "localhost" || host == "127.0.0.1") trimmed
                else "https://" + trimmed.substring("http://".length)
            }
            else -> null
        }
    }

    private fun fetchDirect(url: String): String? {
        var current = url
        var redirects = 0
        while (true) {
            val connection = try {
                openGet(current, ACCEPT_HTML)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    if (++redirects > MAX_REDIRECTS) return null
                    val next = runCatching { URL(URL(current), location).toString() }.getOrNull()
                        ?: return null
                    if (!next.startsWith("https://", ignoreCase = true) &&
                        !next.startsWith("http://localhost", ignoreCase = true) &&
                        !next.startsWith("http://127.0.0.1", ignoreCase = true)
                    ) {
                        return null
                    }
                    current = next
                    continue
                }
                if (code !in 200..299) return null
                val contentType = connection.contentType?.lowercase().orEmpty()
                if (contentType.isNotBlank() &&
                    !contentType.contains("html") &&
                    !contentType.contains("text/plain") &&
                    !contentType.contains("xml")
                ) {
                    // PDFs and other binaries are the Jina route's job.
                    return null
                }
                return connection.inputStream?.use { readCapped(it, MAX_DOWNLOAD_CHARS) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun fetchJina(url: String): String? {
        val connection = try {
            openGet("https://r.jina.ai/$url", ACCEPT_TEXT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream?.use { readCapped(it, MAX_DOWNLOAD_CHARS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun openGet(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", WEB_USER_AGENT)
            setRequestProperty("Accept", accept)
        }

    private fun readCapped(stream: java.io.InputStream, capChars: Int): String {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        var totalBytes = 0L
        while (totalBytes < MAX_DOWNLOAD_BYTES) {
            val read = reader.read(buffer, 0, buffer.size)
            if (read <= 0) break
            totalBytes += read * 2L
            sb.append(buffer, 0, read)
            if (sb.length >= capChars) break
        }
        return sb.toString()
    }

    companion object {
        const val MAX_PAGE_CHARS = 12_000
        const val MIN_USEFUL_CHARS = 200
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L
        private const val MAX_DOWNLOAD_CHARS = 1_500_000
        private const val ACCEPT_HTML = "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.5"
        private const val ACCEPT_TEXT = "text/plain,text/markdown,*/*;q=0.5"

        /**
         * Hand-rolled HTML→text: drop the blocks that are never the
         * article (scripts, styles, nav, footer, asides, forms),
         * turn block-level tags into line breaks, strip the rest,
         * decode entities, and collapse to clean lines.
         */
        internal fun extractText(html: String): String {
            if (html.isBlank()) return ""
            var text = COMMENT.replace(html, " ")
            text = REMOVABLE_BLOCK.replace(text, " ")
            text = BLOCK_TAG.replace(text, "\n")
            text = stripHtmlTags(text)
            text = decodeHtmlEntities(text)
            return text.lineSequence()
                .map { it.replace(SPACE_RUN, " ").trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val REMOVABLE_BLOCK = Regex(
            "<(script|style|nav|footer|aside|form|noscript|iframe|svg|template)\\b[^>]*>.*?</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val BLOCK_TAG = Regex(
            "</?(p|div|br|li|ul|ol|tr|td|th|h[1-6]|section|article|blockquote|pre|table|header|main|figure|figcaption|dl|dt|dd|hr)\\b[^>]*>",
            RegexOption.IGNORE_CASE,
        )
        private val SPACE_RUN = Regex("[ \\t\\x0B\\f]+")
    }
}

/** Browser-compatible UA that still names the app honestly. */
internal const val WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36 Kodex/3.2.51"
