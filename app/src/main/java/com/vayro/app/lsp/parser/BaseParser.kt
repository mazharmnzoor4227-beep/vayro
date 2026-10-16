package com.vayro.app.lsp.parser

/**
 * Milestone 3 — shared infrastructure for the real structural parsers.
 *
 * Provides token-stream navigation, balanced-delimiter skipping with genuine
 * unclosed-delimiter diagnostics, scope-stack tracking and doc-comment
 * capture. Concrete per-language parsers only declare how declarations look.
 */
abstract class BaseParser(
    protected val filePath: String,
    protected val languageId: String,
    source: String,
    syntax: LanguageSyntax,
    trackIndentation: Boolean = false,
) {
    protected val tokens: List<Token> =
        Tokenizer(source, syntax, keepComments = true, trackIndentation = trackIndentation).tokenize()
    protected var pos = 0

    protected val symbols = mutableListOf<ParsedSymbol>()
    protected val imports = mutableListOf<ParsedImport>()
    protected val diagnostics = mutableListOf<ParseDiagnostic>()
    protected val callSites = mutableListOf<CallSite>()
    protected var packageName: String? = null

    /** Enclosing declarations, innermost last. */
    protected val scopeStack = ArrayDeque<ParsedSymbol>()

    /** Last doc comment seen (immediately before the current token). */
    protected var pendingDocComment: String? = null

    protected fun peek(offset: Int = 0): Token {
        val i = (pos + offset).coerceAtMost(tokens.size - 1)
        return tokens[i]
    }

    protected fun advance(): Token {
        val t = peek()
        if (pos < tokens.size - 1) pos++
        return t
    }

    protected fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    protected fun matchText(vararg texts: String): Boolean {
        val t = peek()
        if (t.type == TokenType.IDENTIFIER && t.text in texts) {
            advance()
            return true
        }
        return false
    }

    protected fun matchSymbol(vararg syms: String): Boolean {
        val t = peek()
        if (t.type == TokenType.SYMBOL && t.text in syms) {
            advance()
            return true
        }
        return false
    }

    protected fun checkSymbol(vararg syms: String): Boolean {
        val t = peek()
        return t.type == TokenType.SYMBOL && t.text in syms
    }

    protected fun checkText(vararg texts: String): Boolean {
        val t = peek()
        return t.type == TokenType.IDENTIFIER && t.text in texts
    }

    /** Consumes a pending doc comment if the previous token was one. */
    protected fun takeDocComment(): String? {
        val doc = pendingDocComment
        pendingDocComment = null
        return doc
    }

    /**
     * Records a COMMENT token as a pending doc comment when it looks like
     * documentation (`/** */`, `///`, `//!`). Call for every COMMENT token
     * encountered during the main scan loop.
     */
    protected fun observeComment(token: Token) {
        val text = token.text.trim()
        pendingDocComment = when {
            text.startsWith("/**") -> text
                .removePrefix("/**")
                .removeSuffix("*/")
                .split('\n')
                .joinToString("\n") { it.trim().removePrefix("*").trim() }
                .trim()
                .takeIf { it.isNotEmpty() }
            text.startsWith("///") -> text.removePrefix("///").trim().takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /**
     * Skips a balanced `(...)` / `[...]` region starting at the current
     * opening token. Returns the raw inner token texts. Emits a genuine
     * "unclosed delimiter" error diagnostic when the close never arrives.
     */
    protected fun skipBalanced(open: String, close: String): List<Token> {
        val opener = peek()
        if (!matchSymbol(open)) return emptyList()
        val inner = mutableListOf<Token>()
        var depth = 1
        while (!isAtEnd() && depth > 0) {
            val t = advance()
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) break
                    }
                }
            }
            if (depth > 0) inner.add(t)
        }
        if (depth > 0) {
            diagnostics.add(
                ParseDiagnostic(
                    line = opener.line,
                    character = opener.character,
                    message = "Unclosed '$open' — expected '$close'",
                    severity = "error",
                )
            )
        }
        return inner
    }

    /**
     * Skips a `{...}` block starting at the current token, emitting a genuine
     * diagnostic when it never closes. Returns true when the block closed.
     */
    protected fun skipBlock(): Boolean {
        val opener = peek()
        if (!matchSymbol("{")) return true
        var depth = 1
        while (!isAtEnd() && depth > 0) {
            val t = advance()
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "{" -> depth++
                    "}" -> depth--
                }
            }
        }
        if (depth > 0) {
            diagnostics.add(
                ParseDiagnostic(
                    line = opener.line,
                    character = opener.character,
                    message = "Unclosed '{' — expected '}'",
                    severity = "error",
                )
            )
            return false
        }
        return true
    }

    /**
     * Records a declaration symbol with the current scope stack as its
     * container context.
     */
    protected fun declare(
        name: String,
        kind: ParsedSymbolKind,
        token: Token,
        signature: String? = null,
        superTypes: List<String> = emptyList(),
        parameters: List<String> = emptyList(),
        isPublic: Boolean = true,
    ): ParsedSymbol {
        val container = scopeStack.lastOrNull()
        val symbol = ParsedSymbol(
            name = name,
            kind = kind,
            line = token.line,
            character = token.character,
            containerName = container?.name,
            depth = scopeStack.size,
            signature = signature,
            superTypes = superTypes,
            docComment = takeDocComment(),
            parameters = parameters,
            isPublic = isPublic,
        )
        // Genuine duplicate-definition diagnostic: same name + kind in the same scope.
        if (symbols.any { it.name == name && it.kind == kind && it.containerName == symbol.containerName }) {
            diagnostics.add(
                ParseDiagnostic(
                    line = token.line,
                    character = token.character,
                    message = "'$name' is already defined in this scope",
                    severity = "warning",
                )
            )
        }
        symbols.add(symbol)
        return symbol
    }

    /** Pushes a newly parsed container (class/function) onto the scope stack. */
    protected fun enterScope(symbol: ParsedSymbol) {
        scopeStack.addLast(symbol)
    }

    protected fun exitScope() {
        if (scopeStack.isNotEmpty()) scopeStack.removeLast()
    }

    /**
     * Records `name(` call sites while scanning an expression region. Only
     * identifiers immediately followed by `(` count — a structural property of
     * the token stream, not a regex.
     */
    protected fun collectCallSites(region: List<Token>) {
        var i = 0
        while (i < region.size - 1) {
            val t = region[i]
            val n = region[i + 1]
            if (t.type == TokenType.IDENTIFIER &&
                n.type == TokenType.SYMBOL && n.text == "(" &&
                t.text !in LANGUAGE_KEYWORDS
            ) {
                callSites.add(CallSite(t.text, t.line, t.character))
            }
            i++
        }
    }

    abstract fun parse(): ParsedFile

    protected fun buildResult(): ParsedFile = ParsedFile(
        filePath = filePath,
        languageId = languageId,
        symbols = symbols.toList(),
        imports = imports.toList(),
        diagnostics = diagnostics.toList(),
        packageName = packageName,
        callSites = callSites.toList(),
    )

    companion object {
        /** Identifiers that can never be call sites. */
        val LANGUAGE_KEYWORDS: Set<String> = setOf(
            "if", "else", "for", "while", "do", "switch", "when", "try", "catch",
            "finally", "return", "throw", "new", "typeof", "instanceof", "in",
            "of", "import", "export", "from", "as", "class", "interface",
            "fun", "function", "def", "val", "var", "const", "let",
        )
    }
}
