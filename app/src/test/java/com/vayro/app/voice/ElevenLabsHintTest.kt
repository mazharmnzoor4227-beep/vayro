package com.vayro.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v3.2.39 (Scope 3): the plain-language hint for the ElevenLabs
 * 402 / library-voice failure class — the exact failure the user hit
 * (his pasted ID was a public library voice; free-plan keys cannot
 * use those through the API).
 */
class ElevenLabsHintTest {

    @Test
    fun `402 library failure maps to the plain hint`() {
        val hint = elevenLabsPlainHint(
            "HTTP 402 — Free users cannot use library voices via the API. " +
                "Please use a voice from your library or the standard voices.",
        )
        assertEquals(
            "This is a library voice. ElevenLabs' free plan can't use library voices " +
                "through the API — choose one of your own voices above, or a standard voice.",
            hint,
        )
    }

    @Test
    fun `402 without the library cause gets no hint`() {
        // A different 402 (e.g. quota/payment) must not be mislabelled.
        assertNull(elevenLabsPlainHint("HTTP 402 — Payment required"))
    }

    @Test
    fun `other failures get no hint`() {
        assertNull(elevenLabsPlainHint("HTTP 401 — Invalid API key"))
        assertNull(elevenLabsPlainHint("HTTP 404 — voice not found"))
        assertNull(elevenLabsPlainHint("network unreachable"))
        assertNull(elevenLabsPlainHint(null))
        assertNull(elevenLabsPlainHint(""))
    }
}
