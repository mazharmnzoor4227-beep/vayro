package com.vayro.app.lsp.parser

/**
 * Milestone 3 — a real structural parser for Kotlin.
 *
 * Tokenizes with full string/comment awareness, then recognizes declarations
 * structurally: classes/interfaces/objects/enums (with supertype lists),
 * functions (with parameter lists), properties, constructors and imports.
 * Scope is tracked through brace nesting, so nested declarations get the
 * correct container. Produces genuine diagnostics for unclosed delimiters
 * and duplicate definitions in the same scope.
 *
 * Not compiler-accurate: no type resolution or overload resolution. Real
 * compiler accuracy comes from kotlin-language-server when installed.
 */
class KotlinParser(
    filePath: String,
    source: String,
) : BaseParser(filePath, "kotlin", source, LanguageSyntax.KOTLIN) {

    override fun parse(): ParsedFile {
        while (!isAtEnd()) {
            val t = peek()
            when (t.type) {
                TokenType.COMMENT -> {
                    observeComment(advance())
                }
                TokenType.IDENTIFIER -> when (t.text) {
                    "package" -> parsePackage()
                    "import" -> parseImport()
                    else -> parseDeclarationOrSkip()
                }
                TokenType.SYMBOL -> when (t.text) {
                    "{" -> {
                        advance()
                        // Anonymous block: no scope pushed, so do not pop on close.
                        parseBlockContents(ownsScope = false)
                    }
                    "}" -> {
                        // Stray closing brace at this level: pop one scope if any.
                        advance()
                        exitScope()
                    }
                    else -> advance()
                }
                else -> advance()
            }
        }
        return buildResult()
    }

    /** Parses declarations inside a `{...}` region already opened. */
    private fun parseBlockContents(ownsScope: Boolean = true) {
        var depth = 1
        // Tokens of the current non-declaration statement, for call-site collection.
        val statementTokens = mutableListOf<Token>()
        var parenDepth = 0

        fun flushStatement() {
            if (statementTokens.isNotEmpty()) {
                collectCallSites(statementTokens.toList())
                statementTokens.clear()
            }
            parenDepth = 0
        }

        blockLoop@ while (!isAtEnd() && depth > 0) {
            val t = peek()
            when {
                t.type == TokenType.SYMBOL && t.text == "{" -> {
                    flushStatement()
                    advance()
                    depth++
                }
                t.type == TokenType.SYMBOL && t.text == "}" -> {
                    flushStatement()
                    advance()
                    depth--
                    if (depth == 0) {
                        if (ownsScope) exitScope()
                        return
                    }
                }
                t.type == TokenType.COMMENT -> {
                    observeComment(advance())
                }
                t.type == TokenType.IDENTIFIER && parenDepth == 0 && isDeclarationStart(t.text) -> {
                    flushStatement()
                    parseDeclarationOrSkip()
                }
                else -> {
                    if (t.type == TokenType.SYMBOL) {
                        when (t.text) {
                            "(" -> parenDepth++
                            ")" -> if (parenDepth > 0) parenDepth--
                            ";" -> {
                                advance()
                                flushStatement()
                                continue@blockLoop
                            }
                        }
                    }
                    statementTokens.add(advance())
                }
            }
        }
        flushStatement()
        if (depth > 0) {
            diagnostics.add(
                ParseDiagnostic(
                    line = tSafeLine(),
                    character = 0,
                    message = "Unclosed '{' — expected '}'",
                    severity = "error",
                )
            )
        }
    }

    /** True when an identifier at statement position starts a declaration. */
    private fun isDeclarationStart(word: String): Boolean {
        if (word in setOf("class", "interface", "object", "enum", "annotation", "fun", "val", "var", "const", "constructor")) {
            return true
        }
        // Modifiers/annotations can precede the declaration keyword; peek ahead
        // through them (bounded lookahead).
        if (word in KOTLIN_MODIFIERS || word == "companion") {
            var i = 1
            while (i <= 6) {
                val n = peek(i)
                if (n.type == TokenType.IDENTIFIER &&
                    (n.text in KOTLIN_MODIFIERS || n.text == "companion")
                ) {
                    i++
                    continue
                }
                return n.type == TokenType.IDENTIFIER &&
                    n.text in setOf("class", "interface", "object", "enum", "fun", "val", "var")
            }
        }
        return false
    }

    private fun tSafeLine(): Int = if (isAtEnd()) peek().line else peek().line

    private fun parsePackage() {
        advance() // package
        val parts = mutableListOf<String>()
        while (!isAtEnd()) {
            val t = peek()
            if (t.type != TokenType.IDENTIFIER) break
            parts.add(advance().text)
            // A package name is a dotted identifier sequence; stop when the
            // next token is not a dot (e.g. the `import` keyword).
            if (!(peek().type == TokenType.SYMBOL && peek().text == ".")) break
            advance() // .
        }
        if (parts.isNotEmpty()) packageName = parts.joinToString(".")
        pendingDocComment = null
    }

    private fun parseImport() {
        val importToken = advance() // import
        val parts = mutableListOf<String>()
        var isStar = false
        var alias: String? = null
        while (!isAtEnd()) {
            val t = peek()
            // `import a.B as C`: capture the alias, then done.
            if (t.type == TokenType.IDENTIFIER && t.text == "as") {
                advance()
                if (peek().type == TokenType.IDENTIFIER) {
                    alias = advance().text
                }
                break
            }
            if (t.type != TokenType.IDENTIFIER) break
            parts.add(advance().text)
            // After an identifier: a dot continues the path (possibly to `*`);
            // anything else ends the import.
            val n = peek()
            if (n.type == TokenType.SYMBOL && (n.text == "." || n.text == ".*")) {
                if (n.text == ".*") {
                    isStar = true
                    advance()
                    break
                }
                advance() // .
                if (peek().type == TokenType.SYMBOL && peek().text == "*") {
                    isStar = true
                    advance()
                    break
                }
                // Otherwise the next loop iteration expects an identifier.
            } else {
                break
            }
        }
        val raw = parts.joinToString(".")
        if (raw.isNotEmpty()) {
            val simpleName = alias ?: parts.lastOrNull() ?: raw
            imports.add(
                ParsedImport(
                    rawText = raw,
                    importedNames = if (isStar) listOf("*") else listOf(simpleName),
                    line = importToken.line,
                    isStar = isStar,
                )
            )
        }
        pendingDocComment = null
    }

    /**
     * Attempts to parse a declaration at the current position (modifiers like
     * `public`, `data`, `sealed`, `suspend`, `override` are skipped first).
     * Anything unrecognized is skipped token-by-token — never mis-parsed.
     */
    private fun parseDeclarationOrSkip() {
        // Skip modifiers / annotations.
        var sawCompanion = false
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.IDENTIFIER && t.text in KOTLIN_MODIFIERS) {
                if (t.text == "companion") sawCompanion = true
                advance()
                pendingDocComment = null
                continue
            }
            if (t.type == TokenType.SYMBOL && t.text == "@") {
                advance() // annotation name follows
                if (peek().type == TokenType.IDENTIFIER) advance()
                if (checkSymbol("(")) skipBalanced("(", ")")
                pendingDocComment = null
                continue
            }
            break
        }
        if (isAtEnd()) return
        val t = peek()
        if (t.type != TokenType.IDENTIFIER) {
            if (t.type == TokenType.COMMENT) observeComment(advance()) else advance()
            return
        }
        when (t.text) {
            "class", "interface", "object", "enum", "annotation" -> parseClassLike(isCompanion = sawCompanion)
            "fun" -> parseFunction()
            "val", "var", "const" -> parseProperty()
            "constructor" -> parseConstructor()
            else -> {
                // Not a declaration: could be an expression statement. Scan for
                // call sites in a bounded region (up to ; or } or newline-run).
                pendingDocComment = null
                skipStatementCollectingCalls()
            }
        }
    }

    private fun parseClassLike(isCompanion: Boolean = false) {
        val keywordToken = advance()
        val keyword = keywordToken.text // class | interface | object | enum | annotation
        // `enum class`, `annotation class`, `companion object`
        var kind = when (keyword) {
            "interface" -> ParsedSymbolKind.INTERFACE
            "object" -> ParsedSymbolKind.OBJECT
            "enum", "annotation" -> ParsedSymbolKind.CLASS
            else -> ParsedSymbolKind.CLASS
        }
        if (keyword == "enum" && checkText("class")) {
            advance()
            kind = ParsedSymbolKind.ENUM
        }
        if (keyword == "annotation" && checkText("class")) advance()

        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            // Anonymous object expression — unless it is `companion object`,
            // whose members belong to the synthetic "companion" scope.
            if (isCompanion && keyword == "object" && checkSymbol("{")) {
                val companionSymbol = declare(
                    name = "companion",
                    kind = ParsedSymbolKind.OBJECT,
                    token = keywordToken,
                    signature = "companion object",
                )
                advance() // {
                enterScope(companionSymbol)
                parseBlockContents(ownsScope = true)
                return
            }
            if (checkSymbol("{")) {
                advance()
                parseBlockContents(ownsScope = false)
            }
            return
        }
        advance()
        val name = nameToken.text

        // Type parameters: class Foo<T>
        if (checkSymbol("<")) skipAngleBrackets()

        // Primary constructor parameters: class Foo(val x: Int)
        var parameters: List<String> = emptyList()
        if (checkSymbol("(")) {
            parameters = parseParameterList(name)
        }

        // Supertypes: : Bar(), Baz
        val superTypes = mutableListOf<String>()
        if (matchSymbol(":")) {
            while (!isAtEnd()) {
                val st = peek()
                if (st.type == TokenType.IDENTIFIER) {
                    superTypes.add(advance().text)
                    if (checkSymbol("(")) skipBalanced("(", ")")
                    if (checkSymbol("<")) skipAngleBrackets()
                } else if (st.type == TokenType.SYMBOL && st.text == ",") {
                    advance()
                } else {
                    break
                }
            }
        }

        val symbol = declare(
            name = name,
            kind = kind,
            token = nameToken,
            signature = buildString {
                append("$keyword $name")
                if (parameters.isNotEmpty()) append("(${parameters.joinToString(", ")})")
                if (superTypes.isNotEmpty()) append(" : ${superTypes.joinToString(", ")}")
            },
            superTypes = superTypes,
            parameters = parameters,
        )
        // Body
        if (checkSymbol("{")) {
            advance()
            enterScope(symbol)
            parseBlockContents()
        } else if (checkSymbol("(")) {
            // Defensive: skip stray parens.
            skipBalanced("(", ")")
        }
    }

    private fun parseFunction() {
        advance() // fun
        // Type parameters: fun <T> foo()
        if (checkSymbol("<")) skipAngleBrackets()
        // Receiver: fun String.foo()
        var receiver: String? = null
        if (peek().type == TokenType.IDENTIFIER && peek(1).type == TokenType.SYMBOL && peek(1).text == ".") {
            receiver = advance().text
            advance() // .
        }
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) return
        advance()
        val parameters = if (checkSymbol("(")) parseParameterList(nameToken.text) else emptyList()
        // Return type: : Foo<Bar>
        var returnType: String? = null
        if (matchSymbol(":")) {
            val rt = peek()
            if (rt.type == TokenType.IDENTIFIER) {
                returnType = advance().text
                if (checkSymbol("<")) {
                    returnType += skipAngleBracketsRaw()
                }
                if (checkSymbol("?")) {
                    advance()
                    returnType += "?"
                }
            }
        }
        val symbol = declare(
            name = nameToken.text,
            kind = if (scopeStack.isEmpty()) ParsedSymbolKind.FUNCTION else ParsedSymbolKind.METHOD,
            token = nameToken,
            signature = buildString {
                if (receiver != null) append("$receiver.")
                append("fun ${nameToken.text}(${parameters.joinToString(", ")})")
                if (returnType != null) append(": $returnType")
            },
            parameters = parameters,
        )
        when {
            checkSymbol("{") -> {
                advance()
                enterScope(symbol)
                // Body is parsed directly from the stream: nested declarations
                // become real symbols and call sites are collected.
                parseBlockContents()
            }
            matchSymbol("=") -> {
                // Expression body: fun foo() = bar()
                val expr = collectExpressionTokens()
                collectCallSites(expr)
            }
            else -> {
                // Abstract / interface method without body.
            }
        }
    }

    private fun parseProperty() {
        val keyword = advance().text // val | var | const
        if (keyword == "const" && checkText("val")) advance()
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) return
        advance()
        // Type: val x: Foo<Bar>?
        if (matchSymbol(":")) {
            if (peek().type == TokenType.IDENTIFIER) advance()
            if (checkSymbol("<")) skipAngleBrackets()
            if (checkSymbol("?")) advance()
        }
        // Delegate: by lazy { }
        if (checkText("by")) {
            advance()
        }
        declare(
            name = nameToken.text,
            kind = if (scopeStack.isEmpty()) ParsedSymbolKind.PROPERTY else ParsedSymbolKind.VARIABLE,
            token = nameToken,
            signature = "$keyword ${nameToken.text}",
        )
        // Initializer may contain calls: = foo()
        if (matchSymbol("=")) {
            val expr = collectExpressionTokens()
            collectCallSites(expr)
        }
    }

    private fun parseConstructor() {
        val token = advance() // constructor
        val parameters = if (checkSymbol("(")) parseParameterList() else emptyList()
        val container = scopeStack.lastOrNull()
        declare(
            name = "constructor",
            kind = ParsedSymbolKind.CONSTRUCTOR,
            token = token,
            signature = "constructor(${parameters.joinToString(", ")})",
            parameters = parameters,
        )
        if (checkSymbol("{")) {
            advance()
            // Constructor body shares the container scope: no push, no pop.
            parseBlockContents(ownsScope = false)
        }
        @Suppress("UNUSED_EXPRESSION")
        container
    }

    /** Parses `(p1: T1, p2: T2)` into ["p1: T1", "p2: T2"], recording parameters. */
    private fun parseParameterList(functionName: String? = null): List<String> {
        val inner = skipBalanced("(", ")")
        val params = mutableListOf<String>()
        var i = 0
        while (i < inner.size) {
            val t = inner[i]
            if (t.type == TokenType.IDENTIFIER && t.text !in setOf("val", "var")) {
                // Skip annotations/modifiers inside the parameter list.
                var j = i
                while (j < inner.size && inner[j].type == TokenType.IDENTIFIER &&
                    inner[j].text in setOf("crossinline", "noinline")
                ) {
                    j++
                }
                val nameToken = inner.getOrNull(j)
                if (nameToken != null && nameToken.type == TokenType.IDENTIFIER) {
                    params.add(nameToken.text)
                    val container = scopeStack.lastOrNull()?.name
                    symbols.add(
                        ParsedSymbol(
                            name = nameToken.text,
                            kind = ParsedSymbolKind.PARAMETER,
                            line = nameToken.line,
                            character = nameToken.character,
                            containerName = functionName ?: container,
                            depth = scopeStack.size + 1,
                            signature = nameToken.text,
                        )
                    )
                }
                // Skip to next comma at this nesting level.
                var depth = 0
                i = j
                while (i < inner.size) {
                    val c = inner[i]
                    if (c.type == TokenType.SYMBOL) {
                        when (c.text) {
                            "(", "<", "[" -> depth++
                            ")", ">", "]" -> depth--
                            "," -> if (depth == 0) break
                        }
                    }
                    i++
                }
            } else {
                i++
            }
            // Skip the comma itself.
            if (i < inner.size && inner[i].type == TokenType.SYMBOL && inner[i].text == ",") i++
        }
        return params
    }

    private fun skipAngleBrackets() {
        skipAngleBracketsRaw()
    }

    /** Skips `<...>` with nesting; returns the raw text. */
    private fun skipAngleBracketsRaw(): String {
        if (!matchSymbol("<")) return ""
        val sb = StringBuilder("<")
        var depth = 1
        while (!isAtEnd() && depth > 0) {
            val t = advance()
            sb.append(t.text)
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "<" -> depth++
                    ">" -> depth--
                }
            }
        }
        return sb.toString()
    }

    /**
     * Collects tokens of an `=` expression up to `;`, newline-adjacent `}`,
     * or a token that clearly starts a new declaration. Bounded so a missing
     * terminator cannot swallow the file.
     */
    private fun collectExpressionTokens(): List<Token> {
        val collected = mutableListOf<Token>()
        var parenDepth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "(" -> parenDepth++
                    ")" -> if (parenDepth > 0) parenDepth-- else return collected
                    ";" -> {
                        advance()
                        return collected
                    }
                    "{" -> {
                        // Lambda / block as expression tail: collect balanced.
                        advance()
                        collected.add(t)
                        var braceDepth = 1
                        while (!isAtEnd() && braceDepth > 0) {
                            val b = advance()
                            collected.add(b)
                            if (b.type == TokenType.SYMBOL) {
                                when (b.text) {
                                    "{" -> braceDepth++
                                    "}" -> braceDepth--
                                }
                            }
                        }
                        return collected
                    }
                    "}" -> return collected
                }
            }
            // A declaration keyword at depth 0 ends the expression.
            if (parenDepth == 0 && t.type == TokenType.IDENTIFIER &&
                t.text in setOf("fun", "val", "var", "class", "interface", "object", "return")
            ) {
                return collected
            }
            collected.add(advance())
        }
        return collected
    }

    /** Skips one statement, collecting call sites from its tokens. */
    private fun skipStatementCollectingCalls() {
        val collected = mutableListOf<Token>()
        var parenDepth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "(" -> parenDepth++
                    ")" -> if (parenDepth > 0) parenDepth-- else break
                    ";" -> {
                        advance()
                        break
                    }
                    "{" -> break
                    "}" -> break
                }
            }
            if (parenDepth == 0 && t.type == TokenType.IDENTIFIER && t.text in setOf("fun", "val", "var")) break
            collected.add(advance())
        }
        collectCallSites(collected)
    }

    companion object {
        private val KOTLIN_MODIFIERS: Set<String> = setOf(
            "public", "private", "protected", "internal",
            "open", "final", "abstract", "sealed", "data",
            "override", "lateinit", "suspend", "inline", "tailrec",
            "operator", "infix", "const", "external", "expect", "actual",
            "companion",
        )
    }
}
