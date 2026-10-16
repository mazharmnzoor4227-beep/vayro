package com.vayro.app.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 — validates provider selection priority: real server first,
 * then structural parser, then the labelled heuristic fallback.
 */
class ProviderRegistryTest {

    @Test
    fun `kotlin resolves to the parser provider`() {
        val registry = ProviderRegistry(lspManager = null)
        val provider = registry.providerFor("kotlin")
        assertFalse(provider.isFallback)
        assertTrue(provider.providerName.contains("Kotlin"))
        assertEquals("built-in parser (not compiler-accurate)", registry.describeTier("kotlin"))
    }

    @Test
    fun `java shares the kotlin parser provider`() {
        val registry = ProviderRegistry(lspManager = null)
        val provider = registry.providerFor("java")
        assertFalse(provider.isFallback)
    }

    @Test
    fun `typescript and javascript resolve to the parser provider`() {
        val registry = ProviderRegistry(lspManager = null)
        assertFalse(registry.providerFor("typescript").isFallback)
        assertFalse(registry.providerFor("javascript").isFallback)
    }

    @Test
    fun `python resolves to the parser provider`() {
        val registry = ProviderRegistry(lspManager = null)
        val provider = registry.providerFor("python")
        assertFalse(provider.isFallback)
        assertTrue(provider.providerName.contains("Python"))
    }

    @Test
    fun `unsupported language falls back to the labelled heuristic`() {
        val registry = ProviderRegistry(lspManager = null)
        val provider = registry.providerFor("go")
        assertTrue(provider.isFallback)
        assertTrue(provider.providerName.lowercase().contains("fallback"))
        assertEquals(
            "heuristic fallback (not compiler-accurate)",
            registry.describeTier("go")
        )
    }

    @Test
    fun `parser providers answer through the registry`() {
        val index = ParserProjectIndex()
        index.indexFile("A.kt", "fun greet(): String = \"hi\"\n")
        val registry = ProviderRegistry(lspManager = null, projectIndex = index)
        val provider = registry.providerFor("kotlin")
        val content = "fun greet(): String = \"hi\"\nfun use() {\n    greet()\n}\n"
        index.indexFile("A.kt", content)
        val defs = provider.definitions("A.kt", content, 3, 5)
        assertEquals(1, defs.size)
        assertEquals("greet", defs[0].symbolName)
    }

    @Test
    fun `no real server means no server-backed provider`() {
        // LspManager with no binaries installed: startServer honestly fails,
        // so the registry must not select a server-backed provider.
        val manager = LspManager(serverBinaryLocator = { null })
        assertFalse(manager.startServer("Kotlin", "/workspace"))
        val registry = ProviderRegistry(lspManager = manager)
        val provider = registry.providerFor("kotlin")
        assertFalse(provider is LspServerBackedProvider)
        assertFalse(provider.isFallback) // parser provider wins
    }
}
