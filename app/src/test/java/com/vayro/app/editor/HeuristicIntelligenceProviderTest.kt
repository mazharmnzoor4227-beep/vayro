package com.vayro.app.editor

import com.vayro.app.lsp.LanguageIntelligenceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicIntelligenceProviderTest {

    private val provider: LanguageIntelligenceProvider = HeuristicIntelligenceProvider()

    @Test
    fun fallbackIsExplicitlyLabelled() {
        assertTrue(provider.isFallback)
        assertTrue(provider.providerName.contains("fallback", ignoreCase = true))
        assertTrue(provider.providerName.contains("not compiler-accurate", ignoreCase = true))
    }

    @Test
    fun supportsExpectedLanguages() {
        assertTrue(provider.supportsLanguage("kotlin"))
        assertTrue(provider.supportsLanguage("Kotlin"))
        assertTrue(provider.supportsLanguage("python"))
        assertTrue(provider.supportsLanguage("typescript"))
    }

    @Test
    fun definitionsFindsDeclaredFunction() {
        val content = """
            fun greet(name: String): String {
                return "hi " + name
            }

            fun main() {
                val message = greet("sam")
            }
        """.trimIndent()
        val locations = provider.definitions("Main.kt", content, line = 6, character = 20)
        assertTrue(locations.any { it.symbolName == "greet" && it.line == 1 })
    }

    @Test
    fun definitionsReturnsEmptyWhenNoSymbolAtPosition() {
        val content = "fun main() {\n    // comment\n}\n"
        val locations = provider.definitions("Main.kt", content, line = 2, character = 4)
        assertTrue(locations.isEmpty())
    }

    @Test
    fun diagnosticsAreHonestlyEmpty() {
        val content = "fun broken( {"
        assertTrue(provider.diagnostics("Main.kt", content).isEmpty())
    }

    @Test
    fun completionsSuggestFileSymbols() {
        val content = """
            fun greet(name: String) {}
            fun goodbye() {}

            fun main() {
                gr
            }
        """.trimIndent()
        val items = provider.completionItems("Main.kt", content, line = 5, character = 4, prefix = "gr")
        assertTrue(items.contains("greet"))
    }

    @Test
    fun wordAtExtractsIdentifier() {
        val fallback = provider as HeuristicIntelligenceProvider
        val content = "val greeting = computeGreeting(name)"
        assertEquals("computeGreeting", fallback.wordAt(content, 1, 20))
        // Position on '=' (not an identifier) -> null.
        assertEquals(null, fallback.wordAt(content, 1, 13))
        // Position right after "val" resolves to "val" (cursor at end of word).
        assertEquals("val", fallback.wordAt(content, 1, 3))
    }
}
