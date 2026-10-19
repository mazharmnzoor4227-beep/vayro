package com.vayro.app.network

import java.net.URI

/**
 * v3.2.41 (Scope 2): Base URL hygiene for every provider endpoint field.
 *
 * The failure that motivated this: a user pasted a whole documentation
 * curl example into the Base URL field; the text was saved verbatim and
 * later fed to [java.net.URL], whose "no protocol: curl …" exception
 * dump became the user-facing "Network problem". A Base URL is one
 * clean web address and nothing else — anything with spaces, quotes,
 * braces or shell words in it is a paste mistake, and it must be
 * stopped at the field with a plain message, never saved and never
 * handed to the network layer.
 *
 * Blank is NOT garbage: it means "use the provider default", as before.
 */
object BaseUrlValidation {

    /** The one plain message shown wherever a bad Base URL is caught. */
    const val INVALID_MESSAGE = "That doesn't look like a web address — paste only the Base URL line."

    /**
     * Returns [INVALID_MESSAGE] when [raw] is non-blank but not a single
     * clean http(s) URL, or null when it is acceptable (including blank).
     */
    fun errorOrNull(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        // One address only: no whitespace, quotes, braces, brackets,
        // angle brackets, backslashes or backticks anywhere inside.
        if (value.any { it.isWhitespace() }) return INVALID_MESSAGE
        if (value.any { it in "\"'{}[]<>\\`" }) return INVALID_MESSAGE
        val uri = runCatching { URI(value) }.getOrNull() ?: return INVALID_MESSAGE
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return INVALID_MESSAGE
        if (uri.host.isNullOrBlank()) return INVALID_MESSAGE
        return null
    }

    fun isValid(raw: String): Boolean = errorOrNull(raw) == null
}
