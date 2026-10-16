package com.vayro.app.lsp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 — validates the language-aware tokenizer: comments and
 * strings never leak into the token stream, positions are accurate, and
 * Python indentation tracking works.
 */
class TokenizerTest {

    @Test
    fun `line and block comments are skipped`() {
        val src = "fun a() {} // trailing\n/* block\ncomment */ fun b() {}"
        val tokens = Tokenizer(src, LanguageSyntax.KOTLIN).tokenize()
        val idents = tokens.filter { it.type == TokenType.IDENTIFIER }.map { it.text }
        assertEquals(listOf("fun", "a", "fun", "b"), idents)
    }

    @Test
    fun `string contents never become tokens`() {
        val src = "val s = \"fun fake() { }\" // not code"
        val tokens = Tokenizer(src, LanguageSyntax.KOTLIN).tokenize()
        val idents = tokens.filter { it.type == TokenType.IDENTIFIER }.map { it.text }
        assertEquals(listOf("val", "s"), idents)
    }

    @Test
    fun `token positions are accurate`() {
        val src = "ab\n  cd"
        val tokens = Tokenizer(src, LanguageSyntax.KOTLIN).tokenize()
        val cd = tokens.first { it.text == "cd" }
        assertEquals(2, cd.line)
        assertEquals(2, cd.character)
    }

    @Test
    fun `kotlin triple-quoted strings span lines`() {
        val src = "val s = \"\"\"line1\nline2\"\"\"\nval t = 1"
        val tokens = Tokenizer(src, LanguageSyntax.KOTLIN).tokenize()
        val t = tokens.first { it.text == "t" }
        assertEquals(3, t.line)
    }

    @Test
    fun `python indentation emits indent and dedent`() {
        val src = "def f():\n    x = 1\n    if x:\n        y = 2\n    z = 3\n"
        val tokens = Tokenizer(src, LanguageSyntax.PYTHON, trackIndentation = true).tokenize()
        val kinds = tokens.map { it.type }
        assertEquals(2, kinds.count { it == TokenType.INDENT })
        assertEquals(2, kinds.count { it == TokenType.DEDENT })
    }

    @Test
    fun `python comments do not affect indentation`() {
        val src = "def f():\n    # comment\n    x = 1\n"
        val tokens = Tokenizer(src, LanguageSyntax.PYTHON, trackIndentation = true).tokenize()
        val kinds = tokens.map { it.type }
        assertEquals(1, kinds.count { it == TokenType.INDENT })
    }

    @Test
    fun `unterminated block comment does not hang`() {
        val src = "fun a() {} /* never closed"
        val tokens = Tokenizer(src, LanguageSyntax.KOTLIN).tokenize()
        assertTrue(tokens.any { it.type == TokenType.EOF })
    }

    @Test
    fun `kept comments are emitted as comment tokens`() {
        val src = "/** docs */\nfun a() {}"
        val tokens = Tokenizer(src, LanguageSyntax.KOTLIN, keepComments = true).tokenize()
        val comment = tokens.first { it.type == TokenType.COMMENT }
        assertTrue(comment.text.startsWith("/**"))
    }
}
