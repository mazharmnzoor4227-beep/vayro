package com.vayro.app.util

import java.net.URI

/**
 * Structured information about a validated YouTube video.
 */
data class YouTubeVideoInfo(
    val videoId: String,
    val normalizedUrl: String,
    val rawUrl: String,
    val isShort: Boolean = false,
    val isLive: Boolean = false,
)

/**
 * Strict validator that permits ONLY legitimate YouTube video URLs and rejects
 * arbitrary web domains, other video hosts (Vimeo, TikTok, etc.), or malformed inputs.
 */
object YouTubeUrlValidator {
    private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

    private val ALLOWED_YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtu.be",
    )

    /**
     * Validates whether [inputUrl] is strictly a YouTube video URL and extracts video details.
     */
    fun validateAndExtract(inputUrl: String?): Result<YouTubeVideoInfo> {
        val raw = inputUrl?.trim()
        if (raw.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("YouTube URL cannot be empty."))
        }

        val uri = try {
            val withScheme = if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
                "https://$raw"
            } else {
                raw
            }
            URI(withScheme)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid URL syntax: ${e.message}"))
        }

        val host = uri.host?.lowercase()
        if (host.isNullOrBlank() || !ALLOWED_YOUTUBE_HOSTS.contains(host)) {
            val displayHost = host ?: "unknown"
            return Result.failure(
                IllegalArgumentException("Strictly only YouTube video URLs are supported. Target host '$displayHost' is not allowed.")
            )
        }

        var isShort = false
        var isLive = false
        var extractedId: String? = null

        if (host == "youtu.be" || host == "www.youtu.be") {
            val path = uri.path?.trim('/') ?: ""
            if (path.isNotEmpty()) {
                val candidate = path.split('/')[0]
                if (VIDEO_ID_REGEX.matches(candidate)) {
                    extractedId = candidate
                }
            }
        } else {
            val path = uri.path ?: ""
            when {
                path.startsWith("/watch", ignoreCase = true) -> {
                    val query = uri.query ?: ""
                    val queryParams = query.split('&').mapNotNull { pair ->
                        val parts = pair.split('=', limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else null
                    }.toMap()
                    val candidate = queryParams["v"]
                    if (candidate != null && VIDEO_ID_REGEX.matches(candidate)) {
                        extractedId = candidate
                    }
                }
                path.startsWith("/shorts/", ignoreCase = true) -> {
                    val candidate = path.removePrefix("/shorts/").split('/', '?')[0]
                    if (VIDEO_ID_REGEX.matches(candidate)) {
                        extractedId = candidate
                        isShort = true
                    }
                }
                path.startsWith("/live/", ignoreCase = true) -> {
                    val candidate = path.removePrefix("/live/").split('/', '?')[0]
                    if (VIDEO_ID_REGEX.matches(candidate)) {
                        extractedId = candidate
                        isLive = true
                    }
                }
                path.startsWith("/embed/", ignoreCase = true) -> {
                    val candidate = path.removePrefix("/embed/").split('/', '?')[0]
                    if (VIDEO_ID_REGEX.matches(candidate)) {
                        extractedId = candidate
                    }
                }
            }
        }

        val videoId = extractedId
            ?: return Result.failure(
                IllegalArgumentException("Strictly only valid YouTube video URLs are supported. No valid 11-character video ID found.")
            )

        return Result.success(
            YouTubeVideoInfo(
                videoId = videoId,
                normalizedUrl = "https://www.youtube.com/watch?v=$videoId",
                rawUrl = raw,
                isShort = isShort,
                isLive = isLive,
            )
        )
    }

    /**
     * Returns true if [inputUrl] is strictly a valid YouTube video URL.
     */
    fun isValid(inputUrl: String?): Boolean = validateAndExtract(inputUrl).isSuccess

    /**
     * Builds a comprehensive structured prompt for Gemini multi-modal video understanding.
     */
    fun buildGeminiVideoPrompt(
        info: YouTubeVideoInfo,
        userQuery: String? = null,
        focusMode: String? = null,
    ): String {
        val customFocus = focusMode?.takeIf { it.isNotBlank() } ?: "Comprehensive Deep Analysis"
        val customQuery = userQuery?.trim()?.takeIf { it.isNotBlank() }

        return buildString {
            appendLine("### YouTube Video Understanding & Deep Analysis")
            appendLine("- **Video URL**: ${info.normalizedUrl}")
            appendLine("- **Video ID**: `${info.videoId}`")
            if (info.isShort) appendLine("- **Format**: YouTube Short")
            if (info.isLive) appendLine("- **Format**: YouTube Live / Stream")
            appendLine("- **Analysis Mode**: $customFocus")
            if (customQuery != null) {
                appendLine("- **Specific Inquiry**: \"$customQuery\"")
            }
            appendLine()
            appendLine("Please perform a deep multimodal comprehension of this YouTube video using Google Gemini's video understanding capabilities:")
            appendLine("1. **Executive Overview**: High-level synopsis of the video's core theme, primary speaker/author intent, and conclusions.")
            appendLine("2. **Detailed Chronological Breakdown**: Timestamped / sectional breakdown highlighting key discussion points, visual demonstrations, and transitions.")
            appendLine("3. **Core Insights & Key Takeaways**: Most impactful lessons, statistics, arguments, or revelations presented.")
            appendLine("4. **Technical & Practical Details**: Any code snippets, formulas, tools, hardware, frameworks, or step-by-step instructions demonstrated.")
            if (customQuery != null) {
                appendLine("5. **Direct Answer to User Inquiry**: Explicitly and thoroughly address the user's question: \"$customQuery\".")
            } else {
                appendLine("5. **Actionable Summary**: Final wrap-up with practical recommendations or follow-up actions.")
            }
        }.trim()
    }
}
