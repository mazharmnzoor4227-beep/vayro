package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v3.2.51: the freshness policy — which home turns earn web retrieval.
 * Batteries cover English, Roman Urdu, Devanagari Hindi and Urdu
 * script time markers, explicit search asks, pasted links, and the
 * timeless questions that must never trigger a search.
 */
class HomeFreshnessPolicyTest {

    private val label = "20 October 2026"

    private fun decide(text: String) = HomeFreshnessPolicy.decide(text, label)

    @Test
    fun `today line and labels are exact`() {
        val now = ZonedDateTime.of(2026, 10, 20, 17, 5, 0, 0, ZoneId.of("Asia/Karachi"))
        assertEquals("20 October 2026", HomeFreshnessPolicy.todayLabel(now))
        assertEquals(
            "Today is Tuesday, 20 October 2026. The current local time is 17:05 (Asia/Karachi).",
            HomeFreshnessPolicy.todayLine(now),
        )
        assertEquals("2026-10-20 17:05 PKT", HomeFreshnessPolicy.retrievedLabel(now))
    }

    @Test
    fun `blank and timeless questions stay offline`() {
        assertFalse(decide("").wantsWeb)
        assertFalse(decide("What is the capital of France?").wantsWeb)
        assertFalse(decide("write a poem about the rain").wantsWeb)
        assertFalse(decide("explain photosynthesis in simple words").wantsWeb)
        assertTrue(decide("What is the capital of France?").searchQueries.isEmpty())
    }

    @Test
    fun `english time markers fire with a date-scoped second query`() {
        val plan = decide("What is happening today?")
        assertTrue(plan.wantsWeb)
        assertTrue(plan.wantsSearch)
        assertEquals(listOf("What is happening today?", "What is happening today? 20 October 2026"), plan.searchQueries)

        assertTrue(decide("latest news").wantsSearch)
        assertTrue(decide("who won the match last night").wantsSearch)
        assertTrue(decide("LATEST NEWS").wantsSearch)
        assertTrue(decide("what is the gold rate this week").wantsSearch)
        assertTrue(decide("will it rain tomorrow").wantsSearch)
    }

    @Test
    fun `roman urdu markers fire`() {
        assertTrue(decide("aaj ka mausam kaisa hai").wantsSearch)
        assertTrue(decide("aaj kal kya trend chal raha hai").wantsSearch)
        assertTrue(decide("taza khabar sunao").wantsSearch)
        assertTrue(decide("dollar ki qeemat kya hai abhi").wantsSearch)
    }

    @Test
    fun `devanagari markers fire`() {
        assertTrue(decide("आज का मौसम कैसा है").wantsSearch)
        assertTrue(decide("आज की ताज़ा खबर क्या है").wantsSearch)
    }

    @Test
    fun `urdu script markers fire but timeless urdu does not`() {
        assertTrue(decide("آج کی تازہ خبریں کیا ہیں").wantsSearch)
        assertTrue(decide("آج موسم کیسا ہے").wantsSearch)
        assertFalse(decide("پاکستان کا دارالحکومت کیا ہے").wantsWeb)
    }

    @Test
    fun `explicit search asks use the users own words`() {
        val plan = decide("search the web for the best budget phones")
        assertTrue(plan.wantsSearch)
        assertEquals(listOf("search the web for the best budget phones"), plan.searchQueries)

        val dated = decide("google the latest iPhone price")
        assertEquals(2, dated.searchQueries.size)
        assertTrue(dated.searchQueries[1].endsWith("20 October 2026"))
    }

    @Test
    fun `already dated questions do not get a second dated query`() {
        val plan = decide("news from March 2026")
        assertTrue(plan.wantsSearch)
        assertEquals(1, plan.searchQueries.size)
    }

    @Test
    fun `pasted links fetch without searching`() {
        val plan = decide("summarize https://example.com/article")
        assertTrue(plan.wantsWeb)
        assertFalse(plan.wantsSearch)
        assertEquals(listOf("https://example.com/article"), plan.fetchUrls)
        assertTrue(plan.searchQueries.isEmpty())
    }

    @Test
    fun `local addresses are never fetched and links are capped at two`() {
        assertFalse(decide("open http://localhost:8080/preview").wantsWeb)
        assertTrue(decide("check http://127.0.0.1:3000/").fetchUrls.isEmpty())
        val plan = decide("compare https://a.com/1 and https://b.com/2 and https://c.com/3")
        assertEquals(2, plan.fetchUrls.size)
    }
}
