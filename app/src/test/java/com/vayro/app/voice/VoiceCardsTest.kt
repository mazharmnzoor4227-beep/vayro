package com.vayro.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.39 (Scope 4): voice cards are real, distinct voices only.
 * The dedupe key is the system voice NAME — two entries with the same
 * name would speak identically, so they are one card, never two.
 */
class VoiceCardsTest {

    private fun info(name: String, lang: String? = "en", quality: Int = 300, network: Boolean = false) =
        VoiceCardInfo(name = name, language = lang, quality = quality, networkRequired = network)

    @Test
    fun `duplicate names collapse to one card keeping the best instance`() {
        val cards = pickVoiceCards(
            listOf(
                info("en-us-x-1", quality = 200),
                info("en-us-x-1", quality = 500),
                info("en-us-x-1", quality = 300),
                info("en-gb-x-1", quality = 100),
            ),
        )
        assertEquals(2, cards.size)
        assertEquals(500, cards.first { it.name == "en-us-x-1" }.quality)
    }

    @Test
    fun `a phone with one real voice honestly gets one card`() {
        val cards = pickVoiceCards(listOf(info("en-us-x-1")))
        assertEquals(1, cards.size)
        assertEquals("en-us-x-1", cards[0].name)
    }

    @Test
    fun `a phone with two real voices gets two cards`() {
        val cards = pickVoiceCards(listOf(info("a"), info("b")))
        assertEquals(2, cards.size)
    }

    @Test
    fun `empty voice set gives no cards`() {
        assertTrue(pickVoiceCards(emptyList()).isEmpty())
    }

    @Test
    fun `cards are capped at six`() {
        val cards = pickVoiceCards((1..9).map { info("voice-$it", quality = it) })
        assertEquals(6, cards.size)
    }

    @Test
    fun `english pool needs three DISTINCT english voices`() {
        // Three entries but only ONE distinct English voice — the gate
        // must not trigger on duplicates (that was the old behaviour).
        val cards = pickVoiceCards(
            listOf(
                info("en-us-x-1", quality = 100),
                info("en-us-x-1", quality = 200),
                info("en-us-x-1", quality = 300),
                info("ur-pk-x-1", lang = "ur", quality = 400),
            ),
        )
        // Pool = all voices → the Urdu voice is included.
        assertTrue(cards.any { it.name == "ur-pk-x-1" })
        assertEquals(2, cards.size)
    }

    @Test
    fun `three distinct english voices select the english pool`() {
        val cards = pickVoiceCards(
            listOf(
                info("en-1"), info("en-2"), info("en-3"),
                info("ur-1", lang = "ur", quality = 999),
            ),
        )
        assertEquals(3, cards.size)
        assertTrue(cards.all { it.language == "en" })
    }

    @Test
    fun `best voices come first`() {
        val cards = pickVoiceCards(
            listOf(
                info("low", quality = 100),
                info("high", quality = 500),
                info("mid", quality = 300),
            ),
        )
        assertEquals(listOf("high", "mid", "low"), cards.map { it.name })
    }
}
