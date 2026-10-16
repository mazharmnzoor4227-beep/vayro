package com.vayro.app.lsp.parser

/**
 * Milestone 3 — a real language-aware tokenizer.
 *
 * Properly skips comments and string literals (with escapes, triple-quoted
 * strings and template literals), so declarations inside strings/comments can
 * never be mistaken for code — the fundamental flaw of regex-based parsing.
 * Every token carries its 1-based line and 0-based character offset.
 */

enum class TokenType {
    IDENTIFIER,
    NUMBER,
    STRING,
    SYMBOL,
    COMMENT,
    NEWLINE,
    INDENT,
    DEDENT,
    EOF,
}

data class Token(
    val type: TokenType,
    val text: String,
    /** 1-based line number. */
    val line: Int,
    /** 0-based character offset within the line. */
    val character: Int,
)

/** Per-language lexical rules. */
data class LanguageSyntax(
    val lineComments: List<String> = listOf("//"),
    val blockCommentStart: String? = "/*",
    val blockCommentEnd: String? = "*/",
    /** String delimiters the language supports, e.g. "\"", "'", "`", "\"\"\"". */
    val stringDelimiters: List<String> = listOf("\"", "'"),
    /** Multi-character symbols to match greedily, longest first. */
    val multiCharSymbols: List<String> = listOf(
        "==", "!=", "<=", ">=", "&&", "||", "->", "=>", "::", "?.", "?:", "...",
        "+=", "-=", "*=", "/=", "%=", "++", "--", "<<", ">>", ".*",
    ),
) {
    companion object {
        val KOTLIN = LanguageSyntax(
            stringDelimiters = listOf("\"\"\"", "\"", "'"),
        )
        val JAVA = LanguageSyntax(
            stringDelimiters = listOf("\"", "'"),
        )
        val TYPESCRIPT = LanguageSyntax(
            stringDelimiters = listOf("\"", "'", "`"),
        )
        val PYTHON = LanguageSyntax(
            lineComments = listOf("#"),
            blockCommentStart = null,
            blockCommentEnd = null,
            stringDelimiters = listOf("\"\"\"", "'''", "\"", "'"),
        )
    }
}

class Tokenizer(
    private val source: String,
    private val syntax: LanguageSyntax,
    /** When true, comments are emitted as COMMENT tokens (for doc-comment capture). */
    private val keepComments: Boolean = false,
    /** When true, NEWLINE/INDENT/DEDENT tokens are emitted (Python-style). */
    private val trackIndentation: Boolean = false,
) {
    private var pos = 0
    private var line = 1
    private var lineStart = 0
    /** Indent stack for [trackIndentation]; stores indent widths. */
    private val indentStack = ArrayDeque<Int>()

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        if (trackIndentation) indentStack.addLast(0)
        // Skip leading blank lines' indentation handling uniformly.
        var atLineStart = true
        while (pos < source.length) {
            val c = source[pos]
            when {
                c == '\n' -> {
                    if (trackIndentation) {
                        tokens.add(Token(TokenType.NEWLINE, "\n", line, pos - lineStart))
                    }
                    line++
                    pos++
                    lineStart = pos
                    atLineStart = true
                    if (trackIndentation) {
                        emitIndentTokens(tokens)
                    }
                }
                atLineStart && (c == ' ' || c == '\t' || c == '\r') -> {
                    // Leading whitespace is consumed by emitIndentTokens when
                    // tracking; otherwise skipped here.
                    if (!trackIndentation) pos++ else {
                        // Should not happen: emitIndentTokens consumes it.
                        pos++
                    }
                }
                c.isWhitespace() -> pos++
                matchesLineComment() -> {
                    if (keepComments) tokens.add(readLineComment()) else skipLineComment()
                    // A comment-only line must not change indentation: the
                    // NEWLINE handler already ran emitIndentTokens before the
                    // comment; a trailing comment does not start a new line.
                    atLineStart = false
                }
                matchesBlockCommentStart() -> {
                    if (keepComments) tokens.add(readBlockComment()) else skipBlockComment()
                    atLineStart = false
                }
                matchesStringStart() != null -> {
                    tokens.add(readString())
                    atLineStart = false
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    tokens.add(readIdentifier())
                    atLineStart = false
                }
                c.isDigit() -> {
                    tokens.add(readNumber())
                    atLineStart = false
                }
                else -> {
                    tokens.add(readSymbol())
                    atLineStart = false
                }
            }
        }
        if (trackIndentation) {
            // Close all open indents at EOF.
            while (indentStack.size > 1) {
                indentStack.removeLast()
                tokens.add(Token(TokenType.DEDENT, "", line, 0))
            }
        }
        tokens.add(Token(TokenType.EOF, "", line, pos - lineStart))
        return tokens
    }

    /**
     * Measures the next line's indentation and emits INDENT/DEDENT tokens.
     * Blank lines and comment-only lines never change indentation.
     */
    private fun emitIndentTokens(tokens: MutableList<Token>) {
        var i = pos
        var width = 0
        while (i < source.length && (source[i] == ' ' || source[i] == '\t' || source[i] == '\r')) {
            width += if (source[i] == '\t') 8 else 1
            i++
        }
        // Blank line or comment-only line: no indentation change.
        if (i >= source.length || source[i] == '\n') return
        val isCommentOnly = syntax.lineComments.any { source.startsWith(it, i) }
        if (isCommentOnly) return
        pos = i
        val current = indentStack.lastOrNull() ?: 0
        when {
            width > current -> {
                indentStack.addLast(width)
                tokens.add(Token(TokenType.INDENT, "", line, 0))
            }
            width < current -> {
                while (indentStack.size > 1 && (indentStack.lastOrNull() ?: 0) > width) {
                    indentStack.removeLast()
                    tokens.add(Token(TokenType.DEDENT, "", line, 0))
                }
            }
        }
    }

    private fun currentChar(): Int = pos - lineStart

    private fun matchesLineComment(): Boolean =
        syntax.lineComments.any { source.startsWith(it, pos) }

    private fun readLineComment(): Token {
        val startLine = line
        val startChar = currentChar()
        val start = pos
        val nl = source.indexOf('\n', pos)
        pos = if (nl == -1) source.length else nl
        return Token(TokenType.COMMENT, source.substring(start, pos), startLine, startChar)
    }

    private fun readBlockComment(): Token {
        val startLine = line
        val startChar = currentChar()
        val start = pos
        val begin = syntax.blockCommentStart ?: ""
        val end = syntax.blockCommentEnd ?: ""
        val close = source.indexOf(end, pos + begin.length)
        if (close == -1) {
            for (i in pos until source.length) {
                if (source[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            pos = source.length
        } else {
            for (i in pos until close + end.length) {
                if (source[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            pos = close + end.length
        }
        return Token(TokenType.COMMENT, source.substring(start, pos), startLine, startChar)
    }

    private fun skipLineComment() {
        val nl = source.indexOf('\n', pos)
        if (nl == -1) {
            pos = source.length
        } else {
            pos = nl // the '\n' itself is handled by the main loop
        }
    }

    private fun matchesBlockCommentStart(): Boolean {
        val start = syntax.blockCommentStart ?: return false
        return source.startsWith(start, pos)
    }

    private fun skipBlockComment() {
        val start = syntax.blockCommentStart ?: return
        val end = syntax.blockCommentEnd ?: return
        val close = source.indexOf(end, pos + start.length)
        if (close == -1) {
            // Unterminated block comment: consume to end, tracking newlines.
            for (i in pos until source.length) {
                if (source[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            pos = source.length
        } else {
            for (i in pos until close + end.length) {
                if (source[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            pos = close + end.length
        }
    }

    private fun matchesStringStart(): String? =
        syntax.stringDelimiters
            .sortedByDescending { it.length }
            .firstOrNull { source.startsWith(it, pos) }

    private fun readString(): Token {
        val delim = matchesStringStart() ?: return readSymbol()
        val startLine = line
        val startChar = currentChar()
        val sb = StringBuilder()
        var i = pos + delim.length
        while (i < source.length) {
            if (delim.length == 1 && source[i] == '\\' && i + 1 < source.length) {
                sb.append(source[i])
                sb.append(source[i + 1])
                i += 2
                continue
            }
            if (source.startsWith(delim, i)) {
                i += delim.length
                break
            }
            if (source[i] == '\n') {
                // Single-line strings terminate the token at newline (unterminated);
                // triple-quoted strings may span lines.
                if (delim.length == 1) break
                line++
                lineStart = i + 1
            }
            sb.append(source[i])
            i++
        }
        pos = i
        // Unterminated strings still produce a token so parsing can continue.
        return Token(TokenType.STRING, sb.toString(), startLine, startChar)
    }

    private fun readIdentifier(): Token {
        val startLine = line
        val startChar = currentChar()
        val start = pos
        while (pos < source.length &&
            (source[pos].isLetterOrDigit() || source[pos] == '_' || source[pos] == '$')
        ) {
            pos++
        }
        return Token(TokenType.IDENTIFIER, source.substring(start, pos), startLine, startChar)
    }

    private fun readNumber(): Token {
        val startLine = line
        val startChar = currentChar()
        val start = pos
        var seenDot = false
        while (pos < source.length) {
            val c = source[pos]
            if (c.isDigit()) {
                pos++
            } else if (c == '.' && !seenDot && pos + 1 < source.length && source[pos + 1].isDigit()) {
                seenDot = true
                pos++
            } else if (c == '_' && pos + 1 < source.length && source[pos + 1].isDigit()) {
                pos++
            } else {
                break
            }
        }
        return Token(TokenType.NUMBER, source.substring(start, pos), startLine, startChar)
    }

    private fun readSymbol(): Token {
        val startLine = line
        val startChar = currentChar()
        val multi = syntax.multiCharSymbols
            .sortedByDescending { it.length }
            .firstOrNull { source.startsWith(it, pos) }
        return if (multi != null) {
            pos += multi.length
            Token(TokenType.SYMBOL, multi, startLine, startChar)
        } else {
            pos++
            Token(TokenType.SYMBOL, source.substring(pos - 1, pos), startLine, startChar)
        }
    }
}
