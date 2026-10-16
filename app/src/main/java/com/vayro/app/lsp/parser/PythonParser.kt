package com.vayro.app.lsp.parser

/**
 * Milestone 3 — a real structural parser for Python.
 *
 * Uses indentation-aware tokenization (NEWLINE/INDENT/DEDENT) to track
 * blocks structurally: classes (with base lists), functions/methods (with
 * parameter lists), module/class-level assignments, imports (incl. from/
 * as forms) and decorators. Docstrings (the first string literal in a
 * body) are captured as documentation. Produces genuine diagnostics for
 * unbalanced delimiters and duplicate definitions in the same scope.
 *
 * Not compiler-accurate: no type inference. Real type-aware accuracy comes
 * from pyright/pylsp when installed.
 */
class PythonParser(
    filePath: String,
    source: String,
) : BaseParser(filePath, "python", source, LanguageSyntax.PYTHON, trackIndentation = true) {

    override fun parse(): ParsedFile {
        parseStatements()
        return buildResult()
    }

    /** Parses statements until EOF or a DEDENT that closes our block. */
    private fun parseStatements() {
        while (!isAtEnd()) {
            val t = peek()
            when (t.type) {
                TokenType.EOF -> return
                TokenType.NEWLINE -> advance()
                TokenType.INDENT -> {
                    // Unexpected indent without a header: consume it so the
                    // parser cannot stall, then parse its statements.
                    advance()
                }
                TokenType.DEDENT -> return // closes the caller's block
                TokenType.COMMENT -> observeComment(advance())
                TokenType.SYMBOL -> when (t.text) {
                    "@" -> skipPyDecorator()
                    ";" -> advance()
                    else -> {
                        parseSimpleStatement()
                    }
                }
                TokenType.IDENTIFIER -> when (t.text) {
                    "import" -> parseImport()
                    "from" -> parseFromImport()
                    "class" -> parseClass()
                    "def" -> parseFunction(isAsync = false)
                    "async" -> {
                        advance()
                        if (checkText("def")) parseFunction(isAsync = true)
                        else parseSimpleStatement()
                    }
                    else -> parseSimpleStatement()
                }
                else -> advance()
            }
        }
    }

    private fun skipPyDecorator() {
        advance() // @
        // Decorator expression up to NEWLINE (may include calls).
        val collected = mutableListOf<Token>()
        while (!isAtEnd() && peek().type != TokenType.NEWLINE && peek().type != TokenType.EOF) {
            collected.add(advance())
        }
        collectCallSites(collected)
        pendingDocComment = null
    }

    private fun parseImport() {
        val importToken = advance() // import
        val names = mutableListOf<String>()
        while (!isAtEnd() && peek().type != TokenType.NEWLINE && peek().type != TokenType.EOF) {
            val t = peek()
            when {
                t.type == TokenType.IDENTIFIER && t.text == "as" -> {
                    advance()
                    if (peek().type == TokenType.IDENTIFIER) {
                        names[names.size - 1] = advance().text
                    }
                }
                t.type == TokenType.IDENTIFIER -> {
                    // Dotted: os.path — record the full dotted path as raw,
                    // bind the top-level name (or the alias).
                    val dotted = StringBuilder(advance().text)
                    while (peek().type == TokenType.SYMBOL && peek().text == ".") {
                        advance()
                        if (peek().type == TokenType.IDENTIFIER) {
                            dotted.append('.').append(advance().text)
                        }
                    }
                    names.add(dotted.toString())
                }
                t.type == TokenType.SYMBOL && t.text == "," -> advance()
                else -> advance()
            }
        }
        if (names.isNotEmpty()) {
            // Bind the simple (possibly aliased) name for scope resolution.
            val bound = names.map { it.substringAfterLast('.') }
            imports.add(
                ParsedImport(
                    rawText = names.joinToString(", "),
                    importedNames = bound,
                    line = importToken.line,
                )
            )
        }
        pendingDocComment = null
    }

    private fun parseFromImport() {
        val fromToken = advance() // from
        val moduleParts = mutableListOf<String>()
        var dots = 0
        while (!isAtEnd()) {
            val t = peek()
            when {
                t.type == TokenType.SYMBOL && t.text == "." -> {
                    dots++
                    advance()
                }
                t.type == TokenType.IDENTIFIER && t.text == "import" -> break
                t.type == TokenType.IDENTIFIER -> {
                    moduleParts.add(advance().text)
                    if (peek().type == TokenType.SYMBOL && peek().text == ".") {
                        advance()
                    } else {
                        break
                    }
                }
                else -> break
            }
        }
        if (!checkText("import")) {
            skipToNewline()
            return
        }
        advance() // import
        val names = mutableListOf<String>()
        var isStar = false
        // Parenthesized import list: from x import (a, b)
        val paren = checkSymbol("(")
        if (paren) advance()
        while (!isAtEnd() && peek().type != TokenType.NEWLINE && peek().type != TokenType.EOF) {
            if (paren && checkSymbol(")")) {
                advance()
                break
            }
            val t = peek()
            when {
                t.type == TokenType.SYMBOL && t.text == "*" -> {
                    isStar = true
                    advance()
                }
                t.type == TokenType.IDENTIFIER && t.text == "as" -> {
                    advance()
                    if (peek().type == TokenType.IDENTIFIER && names.isNotEmpty()) {
                        names[names.size - 1] = advance().text
                    }
                }
                t.type == TokenType.IDENTIFIER -> names.add(advance().text)
                t.type == TokenType.SYMBOL && t.text == "," -> advance()
                else -> advance()
            }
        }
        val module = ".".repeat(dots) + moduleParts.joinToString(".")
        imports.add(
            ParsedImport(
                rawText = module,
                importedNames = if (isStar) listOf("*") else names,
                line = fromToken.line,
                isStar = isStar,
            )
        )
        pendingDocComment = null
    }

    private fun parseClass() {
        val classToken = advance() // class
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            skipToNewline()
            return
        }
        advance()
        // Base classes: class Foo(Bar, metaclass=X):
        val superTypes = mutableListOf<String>()
        if (checkSymbol("(")) {
            val inner = skipBalanced("(", ")")
            var k = 0
            while (k < inner.size) {
                val t = inner[k]
                if (t.type == TokenType.IDENTIFIER && t.text != "metaclass") {
                    // Skip keyword args: metaclass=X already filtered; X=...
                    val next = inner.getOrNull(k + 1)
                    if (next?.text != "=") superTypes.add(t.text)
                }
                k++
            }
        }
        // Skip to ':' ending the header.
        skipToColonOrNewline()
        val symbol = declare(
            name = nameToken.text,
            kind = ParsedSymbolKind.CLASS,
            token = nameToken,
            signature = buildString {
                append("class ${nameToken.text}")
                if (superTypes.isNotEmpty()) append("(${superTypes.joinToString(", ")})")
            },
            superTypes = superTypes,
        )
        enterScope(symbol)
        parseBody()
        exitScope()
        @Suppress("UNUSED_EXPRESSION")
        classToken
    }

    private fun parseFunction(isAsync: Boolean) {
        advance() // def
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            skipToNewline()
            return
        }
        advance()
        val parameters = if (checkSymbol("(")) parsePyParameterList(nameToken.text) else emptyList()
        // Return annotation: -> Type:
        if (checkSymbol("-") && peek(1).type == TokenType.SYMBOL && peek(1).text == ">") {
            advance()
            advance()
            skipAnnotation()
        } else if (matchSymbol("->")) {
            skipAnnotation()
        }
        skipToColonOrNewline()
        val symbol = declare(
            name = nameToken.text,
            kind = if (scopeStack.any { it.kind == ParsedSymbolKind.CLASS }) {
                ParsedSymbolKind.METHOD
            } else if (scopeStack.isEmpty()) {
                ParsedSymbolKind.FUNCTION
            } else {
                ParsedSymbolKind.FUNCTION
            },
            token = nameToken,
            signature = "${if (isAsync) "async " else ""}def ${nameToken.text}(${parameters.joinToString(", ")})",
            parameters = parameters,
        )
        enterScope(symbol)
        parseBody()
        exitScope()
    }

    /**
     * Parses the indented body after a `def`/`class` header. Captures a
     * leading docstring as the symbol's documentation.
     */
    private fun parseBody() {
        // Expect NEWLINE then INDENT; tolerate same-line `:` bodies.
        while (!isAtEnd() && peek().type == TokenType.NEWLINE) advance()
        val hasIndent = peek().type == TokenType.INDENT
        if (hasIndent) advance()
        // Docstring: first STRING token in the body.
        if (peek().type == TokenType.STRING) {
            val docToken = advance()
            val container = scopeStack.lastOrNull()
            if (container != null) {
                val idx = symbols.indexOfLast { it === container }
                if (idx >= 0) {
                    val cleaned = docToken.text.trim().take(500)
                    symbols[idx] = container.copy(docComment = cleaned.ifEmpty { null })
                }
            }
            // Docstring may be followed by more statements on later lines.
            while (!isAtEnd() && peek().type == TokenType.NEWLINE) advance()
        }
        if (hasIndent) {
            parseStatements() // returns on DEDENT
            if (peek().type == TokenType.DEDENT) advance()
        } else {
            // Single-line body: `def f(): return 1`
            parseSimpleStatement()
        }
    }

    private fun parsePyParameterList(functionName: String? = null): List<String> {
        val inner = skipBalanced("(", ")")
        val params = mutableListOf<String>()
        var i = 0
        while (i < inner.size) {
            val t = inner[i]
            when {
                t.type == TokenType.SYMBOL && t.text in setOf("*", "/") -> i++
                t.type == TokenType.IDENTIFIER -> {
                    // Skip `self`-style annotation/default: name [: ann] [= default]
                    val nameToken = t
                    if (nameToken.text != "self" && nameToken.text != "cls") {
                        params.add(nameToken.text)
                        symbols.add(
                            ParsedSymbol(
                                name = nameToken.text,
                                kind = ParsedSymbolKind.PARAMETER,
                                line = nameToken.line,
                                character = nameToken.character,
                                containerName = functionName ?: scopeStack.lastOrNull()?.name,
                                depth = scopeStack.size + 1,
                                signature = nameToken.text,
                            )
                        )
                    }
                    // Skip to comma at depth 0.
                    i++
                    var depth = 0
                    while (i < inner.size) {
                        val u = inner[i]
                        if (u.type == TokenType.SYMBOL) {
                            when (u.text) {
                                "(", "[", "{" -> depth++
                                ")", "]", "}" -> depth--
                                "," -> if (depth == 0) break
                            }
                        }
                        i++
                    }
                }
                else -> i++
            }
            if (i < inner.size && inner[i].type == TokenType.SYMBOL && inner[i].text == ",") i++
        }
        return params
    }

    /** Skips a `-> annotation` structurally. */
    private fun skipAnnotation() {
        var depth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.NEWLINE && depth == 0) return
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> if (depth > 0) depth-- else return
                    ":" -> if (depth == 0) return
                }
            }
            advance()
        }
    }

    /** Advances to the `:` ending a def/class header (or newline). */
    private fun skipToColonOrNewline() {
        var depth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.NEWLINE || t.type == TokenType.EOF) return
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> if (depth > 0) depth--
                    ":" -> if (depth == 0) {
                        advance()
                        return
                    }
                }
            }
            advance()
        }
    }

    private fun skipToNewline() {
        while (!isAtEnd() && peek().type != TokenType.NEWLINE) advance()
    }

    /**
     * Parses a simple statement: assignments (`x = ...`), augmented assigns,
     * annotated assigns (`x: int = ...`), expression statements and `return`/
     * `raise` etc. Module/class-level assignments become VARIABLE symbols;
     * call sites are collected from the right-hand side.
     */
    private fun parseSimpleStatement() {
        // Handle `global` / `nonlocal` — no symbols.
        if (checkText("global", "nonlocal")) {
            skipToNewline()
            if (peek().type == TokenType.NEWLINE) advance()
            return
        }
        val collected = mutableListOf<Token>()
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.NEWLINE || t.type == TokenType.EOF ||
                t.type == TokenType.DEDENT || t.type == TokenType.INDENT
            ) {
                break
            }
            if (t.type == TokenType.SYMBOL && t.text == ";") {
                advance()
                break
            }
            collected.add(advance())
        }
        if (peek().type == TokenType.NEWLINE) advance()

        // Assignment detection: IDENTIFIER (= | : | += ...) at depth 0.
        val assignIdx = findAssignmentOperator(collected)
        if (assignIdx >= 0) {
            val lhs = collected.subList(0, assignIdx)
            val target = lhs.lastOrNull { it.type == TokenType.IDENTIFIER }
            val opToken = collected[assignIdx]
            if (target != null && isPlainName(lhs)) {
                // `x: int = ...` — annotation between name and =.
                declare(
                    name = target.text,
                    kind = ParsedSymbolKind.VARIABLE,
                    token = target,
                    signature = target.text,
                )
            }
            // RHS call sites.
            val rhs = collected.subList((assignIdx + 1).coerceAtMost(collected.size), collected.size)
            collectCallSites(rhs)
            @Suppress("UNUSED_EXPRESSION")
            opToken
        } else {
            collectCallSites(collected)
        }
        pendingDocComment = null
    }

    /** Finds `=`, `:=`, `+=` etc. at paren depth 0; -1 when absent. */
    private fun findAssignmentOperator(tokens: List<Token>): Int {
        var depth = 0
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> if (depth > 0) depth--
                    "=", ":=", "+=", "-=", "*=", "/=", "%=", "//=", "**=" -> if (depth == 0) return i
                }
            }
            // `x: int = 1` — the annotation colon; the = is what we want.
            // A bare `:` at depth 0 that is not part of slice: keep scanning.
        }
        return -1
    }

    /** True when the LHS is a plain dotted/single name (not a call or index). */
    private fun isPlainName(lhs: List<Token>): Boolean {
        val filtered = lhs.filter { !(it.type == TokenType.SYMBOL && it.text == ":") }
        // Drop annotation tokens after ':'.
        val namePart = lhs.takeWhile { !(it.type == TokenType.SYMBOL && it.text == ":") }
        return namePart.all {
            it.type == TokenType.IDENTIFIER ||
                (it.type == TokenType.SYMBOL && it.text == ".")
        } && namePart.any { it.type == TokenType.IDENTIFIER } && filtered.isNotEmpty()
    }
}
