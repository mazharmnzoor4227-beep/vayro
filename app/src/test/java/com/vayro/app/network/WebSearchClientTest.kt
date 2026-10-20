package com.vayro.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.51: DuckDuckGo Lite/HTML parsing — redirect decoding,
 * snippet pairing, de-duplication, and graceful emptiness on a
 * bot wall. Fixtures mirror the shapes the endpoints serve today.
 */
class WebSearchClientTest {

    @Test
    fun `lite results decode redirects and pair snippets`() {
        val html = """
            <html><body>
            <a class="result-link" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fgold&amp;rut=abc">Gold price today</a>
            <td class="result-snippet">Gold rose to a record high in early trade.</td>
            <a class="result-link" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fnews.site%2Fstory">Market news</a>
            <td class="result-snippet">Markets rallied on the news.</td>
            <a href="https://duckduckgo.com/settings">Settings</a>
            </body></html>
        """.trimIndent()
        val hits = WebSearchClient.parseResults(html)
        assertEquals(2, hits.size)
        assertEquals("https://example.com/gold", hits[0].url)
        assertEquals("Gold price today", hits[0].title)
        assertEquals("Gold rose to a record high in early trade.", hits[0].snippet)
        assertEquals("https://news.site/story", hits[1].url)
        assertEquals("Markets rallied on the news.", hits[1].snippet)
    }

    @Test
    fun `html endpoint shape parses with protocol relative redirects`() {
        val html = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fsite.example%2Fpage&amp;rut=z">A page</a>
              <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fsite.example%2Fpage">The snippet text.</a>
            </div>
        """.trimIndent()
        val hits = WebSearchClient.parseResults(html)
        assertEquals(1, hits.size)
        assertEquals("https://site.example/page", hits[0].url)
        assertEquals("A page", hits[0].title)
        assertEquals("The snippet text.", hits[0].snippet)
    }

    @Test
    fun `bot wall parses to empty`() {
        val wall = "<html><body><form id=\"challenge\">anomaly: bots use DuckDuckGo too much, captcha required</form></body></html>"
        assertTrue(WebSearchClient.parseResults(wall).isEmpty())
        assertTrue(WebSearchClient.parseResults("").isEmpty())
        assertTrue(WebSearchClient.parseResults("<html><body>no results here</body></html>").isEmpty())
    }

    @Test
    fun `duplicate targets collapse`() {
        val html = """
            <a href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fdup.example%2F">One</a>
            <a href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fdup.example%2F">One again</a>
        """.trimIndent()
        val hits = WebSearchClient.parseResults(html)
        assertEquals(1, hits.size)
        assertEquals("One", hits[0].title)
    }
}
