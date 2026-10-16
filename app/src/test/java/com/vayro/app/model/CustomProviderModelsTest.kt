package com.vayro.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderModelsTest {

    @Test
    fun `effective context limit prefers user override`() {
        val config = CustomProviderConfig(
            contextLimitOverride = 100_000,
            detectedContextLimit = 200_000,
        )
        assertEquals(100_000, config.effectiveContextLimit)
    }

    @Test
    fun `effective context limit falls back to detected`() {
        val config = CustomProviderConfig(detectedContextLimit = 200_000)
        assertEquals(200_000, config.effectiveContextLimit)
    }

    @Test
    fun `effective context limit is zero when unknown`() {
        assertEquals(0, CustomProviderConfig().effectiveContextLimit)
    }

    @Test
    fun `capability summary formats nicely`() {
        val vision = CustomProviderConfig(
            detectedSupportsVision = true,
            detectedContextLimit = 200_000,
            detectedAtMillis = 1L,
        )
        assertEquals("Text + Vision · 200K context", vision.capabilitySummary())

        val textOnly = CustomProviderConfig(detectedContextLimit = 128_000, detectedAtMillis = 1L)
        assertEquals("Text-only · 128K context", textOnly.capabilitySummary())

        val unknown = CustomProviderConfig()
        assertEquals("Text-only · context unknown", unknown.capabilitySummary())
    }

    @Test
    fun `token formatting handles millions`() {
        assertEquals("1M", CustomProviderConfig.formatTokens(1_000_000))
        assertEquals("10M", CustomProviderConfig.formatTokens(10_000_000))
        assertEquals("128K", CustomProviderConfig.formatTokens(128_000))
        assertEquals("500", CustomProviderConfig.formatTokens(500))
    }

    @Test
    fun `json round trip preserves all fields`() {
        val original = CustomProviderConfig(
            id = "test-id",
            displayName = "My OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            modelId = "anthropic/claude-3.5-sonnet",
            modelDisplayName = "Claude 3.5 Sonnet",
            apiType = CustomApiType.OPENAI_COMPATIBLE,
            contextLimitOverride = 150_000,
            detectedSupportsVision = true,
            detectedContextLimit = 200_000,
            detectedAtMillis = 12345L,
            detectionNote = "Detected via /models",
            isActive = true,
        )
        val restored = customProviderFromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `json parsing tolerates missing fields`() {
        val restored = customProviderFromJson(JSONObject())
        assertEquals(CustomApiType.OPENAI_COMPATIBLE, restored.apiType)
        assertFalse(restored.isActive)
        assertFalse(restored.hasDetection)
    }

    @Test
    fun `invalid api type defaults to openai compatible`() {
        val obj = JSONObject().put("apiType", "SOMETHING_ELSE")
        assertEquals(CustomApiType.OPENAI_COMPATIBLE, customProviderFromJson(obj).apiType)
    }

    @Test
    fun `hasDetection reflects detection timestamp`() {
        assertFalse(CustomProviderConfig().hasDetection)
        assertTrue(CustomProviderConfig(detectedAtMillis = 1L).hasDetection)
    }
}
