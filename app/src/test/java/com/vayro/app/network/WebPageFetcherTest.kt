package com.vayro.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.51: the hand-rolled HTML→text extractor — article content
 * survives, boilerplate and code do not, entities decode.
 */
class WebPageFetcherTest {

    @Test
    fun `article text survives and boilerplate is dropped`() {
        val html = """
            <html><head><style>.a{color:red}</style><script>var secret = 1;</script></head>
            <body>
              <nav>Home Products Contact</nav>
              <article><h1>Big Headline</h1>
              <p>First paragraph of the story.</p>
              <p>Second paragraph with details.</p></article>
              <aside>Buy this thing</aside>
              <footer>Copyright 2026</footer>
            </body></html>
        """.trimIndent()
        val text = WebPageFetcher.extractText(html)
        assertTrue(text.contains("Big Headline"))
        assertTrue(text.contains("First paragraph of the story."))
        assertTrue(text.contains("Second paragraph with details."))
        assertFalse(text.contains("Home Products Contact"))
        assertFalse(text.contains("var secret"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("Buy this thing"))
        assertFalse(text.contains("Copyright 2026"))
    }

    @Test
    fun `entities decode including numeric ones`() {
        val text = WebPageFetcher.extractText("<p>5 &lt; 6 &amp; 7 &gt; 2 &#8212; done &quot;yes&quot;</p>")
        assertEquals("5 < 6 & 7 > 2 — done \"yes\"", text)
    }

    @Test
    fun `paragraph breaks become lines and blank input stays blank`() {
        val text = WebPageFetcher.extractText("<p>one</p><p>two</p>")
        assertEquals(listOf("one", "two"), text.lines())
        assertEquals("", WebPageFetcher.extractText(""))
    }

    @Test
    fun `html decoder keeps double encoded text literal`() {
        assertEquals("&lt;", decodeHtmlEntities("&amp;lt;"))
        assertEquals("a & b", decodeHtmlEntities("a &amp; b"))
    }
}
