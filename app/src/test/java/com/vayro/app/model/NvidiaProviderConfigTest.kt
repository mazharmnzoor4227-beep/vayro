package com.vayro.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaProviderConfigTest {

    @Test
    fun `nvidia provider uses official integrate api endpoint`() {
        assertEquals(
            "https://integrate.api.nvidia.com/v1",
            ProviderKind.NVIDIA_NIM.defaultBaseUrl,
        )
    }

    @Test
    fun `nvidia provider uses openai chat protocol`() {
        assertEquals(ProviderProtocol.OPENAI_CHAT, ProviderKind.NVIDIA_NIM.protocol)
    }

    @Test
    fun `nvidia default model is a nemotron variant`() {
        val model = ProviderKind.NVIDIA_NIM.defaultModel.lowercase()
        assertTrue("Default model should be Nemotron, was: $model", "nemotron" in model)
    }

    @Test
    fun `nvidia base url and protocol are fixed`() {
        assertTrue(ProviderKind.NVIDIA_NIM.fixedBaseUrl)
        assertTrue(ProviderKind.NVIDIA_NIM.fixedProtocol)
    }

    @Test
    fun `nvidia is available for deepseek harness agent`() {
        assertTrue(ProviderKind.NVIDIA_NIM in DEEPSEEK_HARNESS_PROVIDERS)
        assertEquals("openai-completions", defaultDshApiForProvider(ProviderKind.NVIDIA_NIM))
    }

    @Test
    fun `nvidia model ids follow org slash model convention`() {
        val models = listOf(
            "nvidia/llama-3.1-nemotron-70b-instruct",
            "nvidia/llama-3.1-nemotron-ultra-253b-v1",
            "nvidia/llama-3.1-nemotron-nano-8b-v1",
            "nvidia/nemotron-4-340b-instruct",
            "meta/llama-3.3-70b-instruct",
            "mistralai/mistral-large-2-instruct",
            "qwen/qwen2.5-coder-32b-instruct",
            "deepseek-ai/deepseek-r1",
        )
        models.forEach { id ->
            assertTrue("Model id should be org/model: $id", "/" in id && id.indexOf('/') > 0)
        }
    }

    @Test
    fun `deepseek provider keeps anthropic gateway protocol`() {
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, ProviderKind.DEEPSEEK.protocol)
        assertEquals("https://api.deepseek.com/anthropic", ProviderKind.DEEPSEEK.defaultBaseUrl)
    }

}
