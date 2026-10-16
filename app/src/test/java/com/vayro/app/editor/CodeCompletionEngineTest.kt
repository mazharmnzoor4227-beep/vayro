package com.vayro.app.editor

import com.vayro.app.runtime.CodeSymbol
import com.vayro.app.runtime.SymbolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeCompletionEngineTest {

    @Test
    fun `keyword completion suggests override when typing ov in Kotlin`() {
        val code = "ov"
        val completions = CodeCompletionEngine.getCompletions(code, cursorOffset = 2, language = "Kotlin")

        val overrideItem = completions.firstOrNull { it.label == "override" }
        assertTrue("Expected 'override' keyword completion", overrideItem != null)
        assertEquals(CompletionItemKind.KEYWORD, overrideItem!!.kind)
    }

    @Test
    fun `keyword completion suggests def in Python`() {
        val code = "de"
        val completions = CodeCompletionEngine.getCompletions(code, cursorOffset = 2, language = "Python")

        val defItem = completions.firstOrNull { it.label == "def" }
        assertTrue("Expected 'def' keyword completion", defItem != null)
        assertEquals(CompletionItemKind.KEYWORD, defItem!!.kind)
    }

    @Test
    fun `snippet completion for fun provides template insertText`() {
        val code = "fun"
        val completions = CodeCompletionEngine.getCompletions(code, cursorOffset = 3, language = "Kotlin")

        val snippet = completions.firstOrNull { it.kind == CompletionItemKind.SNIPPET }
        assertTrue("Expected snippet suggestion for fun", snippet != null)
        assertTrue(snippet!!.insertText.contains("fun name()"))
    }

    @Test
    fun `dot access suggests methods and properties from project symbols`() {
        val code = "val result = calculator."
        val symbols = listOf(
            CodeSymbol(name = "add", kind = SymbolKind.METHOD, line = 10, signature = "(a: Int, b: Int) -> Int"),
            CodeSymbol(name = "subtract", kind = SymbolKind.METHOD, line = 15, signature = "(a: Int, b: Int) -> Int"),
            CodeSymbol(name = "precision", kind = SymbolKind.PROPERTY, line = 5),
            CodeSymbol(name = "OtherClass", kind = SymbolKind.CLASS, line = 30) // should not be suggested for dot access
        )

        val completions = CodeCompletionEngine.getCompletions(
            content = code,
            cursorOffset = code.length,
            language = "Kotlin",
            projectSymbols = symbols
        )

        assertTrue(completions.any { it.label == "add" && it.insertText == "add()" })
        assertTrue(completions.any { it.label == "subtract" && it.insertText == "subtract()" })
        assertTrue(completions.any { it.label == "precision" && it.insertText == "precision" })
        assertTrue("Class should not be suggested as member", completions.none { it.label == "OtherClass" })
    }

    @Test
    fun `project symbol matches prefix and generates correct kind`() {
        val code = "val x = My"
        val symbols = listOf(
            CodeSymbol(name = "MyAwesomeService", kind = SymbolKind.CLASS, line = 1),
            CodeSymbol(name = "myHelper", kind = SymbolKind.FUNCTION, line = 20),
            CodeSymbol(name = "Unrelated", kind = SymbolKind.CLASS, line = 40)
        )

        val completions = CodeCompletionEngine.getCompletions(
            content = code,
            cursorOffset = code.length,
            language = "Kotlin",
            projectSymbols = symbols
        )

        assertTrue(completions.any { it.label == "MyAwesomeService" && it.kind == CompletionItemKind.CLASS })
        assertTrue(completions.any { it.label == "myHelper" && it.kind == CompletionItemKind.FUNCTION })
        assertTrue(completions.none { it.label == "Unrelated" })
    }

    @Test
    fun `empty content returns default keywords for language`() {
        val completions = CodeCompletionEngine.getCompletions("", cursorOffset = 0, language = "Python")
        assertTrue(completions.isNotEmpty())
        assertTrue(completions.any { it.label == "def" })
        assertTrue(completions.any { it.label == "class" })
    }
}
