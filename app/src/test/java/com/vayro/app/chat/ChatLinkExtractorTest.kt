package com.vayro.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLinkExtractorTest {

    @Test
    fun extractsHttpsUrlFromProse() {
        val links = ChatLinkExtractor.extract("Read the guide at https://example.com/guide for details.")
        assertEquals(listOf("https://example.com/guide"), links)
    }

    @Test
    fun extractsLocalhostPreviewUrlWithPortAndPath() {
        val links = ChatLinkExtractor.extract("Dev server is live: http://localhost:5173/dashboard — open it.")
        assertEquals(listOf("http://localhost:5173/dashboard"), links)
    }

    @Test
    fun normalisesBareLocalhostWithPort() {
        val links = ChatLinkExtractor.extract("Preview running on localhost:3000 right now")
        assertEquals(listOf("http://localhost:3000"), links)
    }

    @Test
    fun normalisesBareLoopbackIpWithPath() {
        val links = ChatLinkExtractor.extract("Try 127.0.0.1:8080/api/health when ready")
        assertEquals(listOf("http://127.0.0.1:8080/api/health"), links)
    }

    @Test
    fun schemeLocalhostIsNotDuplicatedByBareMatch() {
        val links = ChatLinkExtractor.extract("Open http://localhost:3000 and http://localhost:3000 again")
        assertEquals(listOf("http://localhost:3000"), links)
    }

    @Test
    fun multipleLinksKeepTextOrderAndDedupe() {
        val text = "First https://a.example.com/one then localhost:8080/x and again https://a.example.com/one"
        assertEquals(
            listOf("https://a.example.com/one", "http://localhost:8080/x"),
            ChatLinkExtractor.extract(text),
        )
    }

    @Test
    fun markdownLinkYieldsTargetWithoutClosingParen() {
        val links = ChatLinkExtractor.extract("See the [docs](https://example.com/docs) page")
        assertEquals(listOf("https://example.com/docs"), links)
    }

    @Test
    fun trailingSentencePeriodIsTrimmed() {
        val links = ChatLinkExtractor.extract("It is hosted at https://example.com.")
        assertEquals(listOf("https://example.com"), links)
    }

    @Test
    fun normalisesBareWwwDomain() {
        val links = ChatLinkExtractor.extract("Mirror: www.example.com/files")
        assertEquals(listOf("https://www.example.com/files"), links)
    }

    @Test
    fun textWithoutLinksYieldsEmptyList() {
        assertTrue(ChatLinkExtractor.extract("The build finished with no errors.").isEmpty())
        assertTrue(ChatLinkExtractor.extract("").isEmpty())
    }

    @Test
    fun ellipsizeMiddleKeepsShortTextUntouched() {
        assertEquals("http://localhost:3000", ChatLinkExtractor.ellipsizeMiddle("http://localhost:3000", 44))
    }

    @Test
    fun ellipsizeMiddleShortensLongUrlToMaxChars() {
        val url = "https://example.com/some/very/long/path/to/the/preview/page?token=abcdef"
        val shortened = ChatLinkExtractor.ellipsizeMiddle(url, 30)
        assertEquals(30, shortened.length)
        assertTrue(shortened.startsWith("https://example"))
        assertTrue(shortened.endsWith("bcdef"))
        assertTrue(shortened.contains("…"))
    }
}
