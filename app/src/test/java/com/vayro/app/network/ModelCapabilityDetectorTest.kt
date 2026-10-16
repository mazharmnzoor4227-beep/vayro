package com.vayro.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityDetectorTest {

    @Test
    fun `heuristic detects vision models`() {
        val cases = listOf(
            "anthropic/claude-3-5-sonnet" to true,
            "anthropic/claude-3-7-sonnet" to true,
            "openai/gpt-4o" to true,
            "openai/gpt-4o-mini" to true,
            "google/gemini-2.0-flash-001" to true,
            "meta-llama/llama-3.2-90b-vision-instruct" to true,
        )
        cases.forEach { (id, expectedVision) ->
            val result = ModelCapabilityDetector.heuristicFor(id)
            assertNotNull("Expected heuristic for $id", result)
            assertEquals("Vision for $id", expectedVision, result!!.supportsVision)
            assertTrue("Context for $id", result.contextLimit > 0)
        }
    }

    @Test
    fun `heuristic detects text-only models`() {
        val cases = listOf(
            "deepseek/deepseek-chat" to 128_000,
            "deepseek/deepseek-reasoner" to 128_000,
            "meta-llama/llama-3.1-70b-instruct" to 128_000,
            "nvidia/llama-3.1-nemotron-70b-instruct" to 128_000,
            "qwen/qwen2.5-coder-32b-instruct" to 128_000,
        )
        cases.forEach { (id, expectedCtx) ->
            val result = ModelCapabilityDetector.heuristicFor(id)
            assertNotNull("Expected heuristic for $id", result)
            assertFalse("No vision for $id", result!!.supportsVision)
            assertEquals("Context for $id", expectedCtx, result.contextLimit)
        }
    }

    @Test
    fun `heuristic returns null for unknown models`() {
        assertNull(ModelCapabilityDetector.heuristicFor("some-obscure-model-xyz-123"))
        assertNull(ModelCapabilityDetector.heuristicFor(""))
    }

    @Test
    fun `heuristic is case insensitive`() {
        val result = ModelCapabilityDetector.heuristicFor("ANTHROPIC/CLAUDE-3-5-SONNET")
        assertNotNull(result)
        assertTrue(result!!.supportsVision)
    }

    @Test
    fun `detected capabilities carry a note`() {
        val caps = com.vayro.app.model.DetectedModelCapabilities(
            supportsVision = true,
            contextLimit = 200_000,
            note = "Detected via /models",
        )
        assertTrue(caps.supportsVision)
        assertEquals(200_000, caps.contextLimit)
        assertEquals("Detected via /models", caps.note)
    }
}
