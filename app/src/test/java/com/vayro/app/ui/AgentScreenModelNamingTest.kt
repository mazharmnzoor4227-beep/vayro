package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA H8: Antigravity model ids must not be mislabelled — `claude-sonnet-4-6`
 * was shown as "Claude 3.7 Sonnet", colliding with the genuine 3.7 model.
 */
class AgentScreenModelNamingTest {

    @Test
    fun `claude 4-6 ids label as 4-6, not 3-7`() {
        assertEquals("Claude Sonnet 4.6", formatAntigravityModelName("claude-sonnet-4-6"))
        assertEquals("Claude Opus 4.6 (Thinking)", formatAntigravityModelName("claude-opus-4-6-thinking"))
    }

    @Test
    fun `known models keep their labels`() {
        assertEquals("Gemini 3.8 Flash (High)", formatAntigravityModelName("gemini-3.8-flash-high"))
        assertEquals("GPT-OSS 120B", formatAntigravityModelName("gpt-oss-120b-medium"))
    }

    @Test
    fun `unknown ids fall back to title-cased words`() {
        assertEquals("Some New Model", formatAntigravityModelName("some-new-model"))
    }
}
