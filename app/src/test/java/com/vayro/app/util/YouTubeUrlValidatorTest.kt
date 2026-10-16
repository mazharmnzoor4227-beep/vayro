package com.vayro.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlValidatorTest {

    @Test
    fun validatesStandardWatchUrls() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = YouTubeUrlValidator.validateAndExtract(url)
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("dQw4w9WgXcQ", info.videoId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", info.normalizedUrl)
        assertFalse(info.isShort)
        assertFalse(info.isLive)
    }

    @Test
    fun validatesWatchUrlsWithExtraQueryParams() {
        val url = "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=120s"
        val result = YouTubeUrlValidator.validateAndExtract(url)
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("dQw4w9WgXcQ", info.videoId)
    }

    @Test
    fun validatesYouTuBeShortLinks() {
        val url = "https://youtu.be/dQw4w9WgXcQ?si=abcdef12345"
        val result = YouTubeUrlValidator.validateAndExtract(url)
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("dQw4w9WgXcQ", info.videoId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", info.normalizedUrl)
    }

    @Test
    fun validatesShortsAndLiveStreams() {
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val shortsResult = YouTubeUrlValidator.validateAndExtract(shortsUrl)
        assertTrue(shortsResult.isSuccess)
        assertTrue(shortsResult.getOrThrow().isShort)

        val liveUrl = "https://www.youtube.com/live/dQw4w9WgXcQ"
        val liveResult = YouTubeUrlValidator.validateAndExtract(liveUrl)
        assertTrue(liveResult.isSuccess)
        assertTrue(liveResult.getOrThrow().isLive)
    }

    @Test
    fun strictlyRejectsNonYouTubeUrls() {
        val nonYouTubeUrls = listOf(
            "https://vimeo.com/123456789",
            "https://www.tiktok.com/@user/video/71234567890",
            "https://www.dailymotion.com/video/x7tgad0",
            "https://twitter.com/user/status/123456",
            "https://x.com/user/status/123456",
            "https://google.com/search?q=youtube",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://example.com/video.mp4",
        )

        for (url in nonYouTubeUrls) {
            val result = YouTubeUrlValidator.validateAndExtract(url)
            assertTrue("Expected failure for non-YouTube URL: $url", result.isFailure)
            assertFalse(YouTubeUrlValidator.isValid(url))
        }
    }

    @Test
    fun rejectsMalformedAndEmptyInputs() {
        assertFalse(YouTubeUrlValidator.isValid(null))
        assertFalse(YouTubeUrlValidator.isValid(""))
        assertFalse(YouTubeUrlValidator.isValid("   "))
        assertFalse(YouTubeUrlValidator.isValid("not a url"))
        assertFalse(YouTubeUrlValidator.isValid("https://www.youtube.com/watch?v=short"))
        assertFalse(YouTubeUrlValidator.isValid("https://www.youtube.com/channel/UC12345"))
    }

    @Test
    fun buildsGeminiVideoPromptWithStructure() {
        val info = YouTubeUrlValidator.validateAndExtract("https://youtu.be/dQw4w9WgXcQ").getOrThrow()
        val prompt = YouTubeUrlValidator.buildGeminiVideoPrompt(
            info = info,
            userQuery = "Summarize the key mathematical formulas",
            focusMode = "Technical Breakdown",
        )

        assertTrue(prompt.contains("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(prompt.contains("Technical Breakdown"))
        assertTrue(prompt.contains("Summarize the key mathematical formulas"))
        assertTrue(prompt.contains("Detailed Chronological Breakdown"))
    }
}
