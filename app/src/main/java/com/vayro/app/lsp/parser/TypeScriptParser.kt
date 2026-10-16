package com.vayro.app.lsp.parser

/**
 * Milestone 3 — a real structural parser for TypeScript and JavaScript.
 *
 * Recognizes imports (named/default/namespace), classes with extends/
 * implements, interfaces, type aliases, enums, functions, arrow functions
 * assigned to variables, variable declarations and class members —
 * structurally from the token stream. Export modifiers and decorators are
 * handled. Produces genuine unclosed-delimiter and duplicate-definition
 * diagnostics.
 *
 * Not compiler-accurate: no type resolution. Real compiler accuracy comes
 * from typescript-language-server when installed.
 */
class TypeScriptParser(
    filePath: String,
    source: String,
    languageId: String = "typescript",
) : BaseParser(filePath, languageId, source, LanguageSyntax.TYPESCRIPT) {

    override fun parse(): ParsedFile {
        while (!isAtEnd()) {
            val t = peek()
            when (t.type) {
                TokenType.COMMENT -> observeComment(advance())
                TokenType.IDENTIFIER -> when (t.text) {
                    "import" -> parseImport()
                    "export" -> parseExport()
                    else -> parseDeclarationOrSkip()
                }
                TokenType.SYMBOL -> when (t.text) {
                    "{" -> {
                        advance()
                        parseBlockContents(ownsScope = false)
                    }
                    "}" -> {
                        advance()
                        exitScope()
                    }
                    ";" -> advance()
                    "@" -> skipDecorator()
                    else -> advance()
                }
                else -> advance()
            }
        }
        return buildResult()
    }

    private fun skipDecorator() {
        advance() // @
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.IDENTIFIER) advance()
            else if (t.type == TokenType.SYMBOL && t.text == ".") advance()
            else break
        }
        if (checkSymbol("(")) skipBalanced("(", ")")
        pendingDocComment = null
    }

    private fun parseImport() {
        val importToken = advance() // import
        val names = mutableListOf<String>()
        // import type { A } from 'x' / import 'x' (side-effect)
        if (checkText("type")) advance()
        when {
            checkSymbol("*") -> {
                advance() // *
                if (checkText("as")) {
                    advance()
                    if (peek().type == TokenType.IDENTIFIER) names.add(advance().text)
                }
            }
            checkSymbol("{") -> {
                val inner = skipBalanced("{", "}")
                var k = 0
                while (k < inner.size) {
                    val it = inner[k]
                    if (it.type == TokenType.IDENTIFIER && it.text != "as" && it.text != "type") {
                        // `a as b`: record the local name b.
                        val next = inner.getOrNull(k + 1)
                        val next2 = inner.getOrNull(k + 2)
                        if (next?.text == "as" && next2?.type == TokenType.IDENTIFIER) {
                            names.add(next2.text)
                            k += 3
                            continue
                        }
                        names.add(it.text)
                    }
                    k++
                }
            }
            peek().type == TokenType.STRING -> {
                // Side-effect import: import './x'
                advance()
            }
            peek().type == TokenType.IDENTIFIER -> {
                names.add(advance().text) // default import
                if (checkSymbol(",")) {
                    advance()
                    if (checkSymbol("{")) {
                        val inner = skipBalanced("{", "}")
                        inner.filter { it.type == TokenType.IDENTIFIER && it.text != "as" }
                            .forEach { names.add(it.text) }
                    } else if (checkSymbol("*")) {
                        advance()
                        if (checkText("as")) {
                            advance()
                            if (peek().type == TokenType.IDENTIFIER) names.add(advance().text)
                        }
                    }
                }
            }
        }
        // from '...'
        var module = ""
        if (checkText("from")) {
            advance()
            if (peek().type == TokenType.STRING) module = advance().text
        } else if (peek().type == TokenType.STRING) {
            module = advance().text
        }
        matchSymbol(";")
        if (names.isNotEmpty() || module.isNotEmpty()) {
            imports.add(
                ParsedImport(
                    rawText = module,
                    importedNames = names.ifEmpty { listOf("*") },
                    line = importToken.line,
                    isStar = names.isEmpty(),
                )
            )
        }
        pendingDocComment = null
    }

    private fun parseExport() {
        advance() // export
        if (checkText("default")) advance()
        // export { a, b } — nothing to declare.
        if (checkSymbol("{")) {
            skipBalanced("{", "}")
            if (checkText("from")) {
                advance()
                if (peek().type == TokenType.STRING) advance()
            }
            matchSymbol(";")
            pendingDocComment = null
            return
        }
        // export * from 'x'
        if (checkSymbol("*")) {
            advance()
            if (checkText("from")) {
                advance()
                if (peek().type == TokenType.STRING) {
                    val mod = peek()
                    imports.add(
                        ParsedImport(
                            rawText = advance().text,
                            importedNames = listOf("*"),
                            line = mod.line,
                            isStar = true,
                        )
                    )
                }
            }
            matchSymbol(";")
            pendingDocComment = null
            return
        }
        // export <declaration>
        parseDeclarationOrSkip()
    }

    private fun parseDeclarationOrSkip() {
        // Skip modifiers.
        while (!isAtEnd() && peek().type == TokenType.IDENTIFIER &&
            peek().text in setOf("declare", "abstract", "readonly", "static", "public", "private", "protected")
        ) {
            advance()
        }
        if (isAtEnd()) return
        val t = peek()
        if (t.type == TokenType.SYMBOL && t.text == "@") {
            skipDecorator()
            parseDeclarationOrSkip()
            return
        }
        if (t.type != TokenType.IDENTIFIER) {
            if (t.type == TokenType.COMMENT) observeComment(advance()) else advance()
            return
        }
        when (t.text) {
            "class", "interface" -> parseClassOrInterface()
            "function" -> parseFunction()
            "const", "let", "var" -> parseVariable()
            "type" -> parseTypeAlias()
            "enum" -> parseEnum()
            "namespace" -> parseNamespace()
            else -> skipTsStatement()
        }
    }

    private fun parseClassOrInterface() {
        val keyword = advance().text
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            if (checkSymbol("{")) {
                advance()
                parseBlockContents(ownsScope = false)
            }
            return
        }
        advance()
        if (checkSymbol("<")) skipGenericParams()

        val superTypes = mutableListOf<String>()
        if (checkText("extends")) {
            advance()
            val st = peek()
            if (st.type == TokenType.IDENTIFIER) {
                superTypes.add(advance().text)
                if (checkSymbol("<")) skipGenericParams()
            }
        }
        if (checkText("implements")) {
            advance()
            while (!isAtEnd()) {
                val st = peek()
                if (st.type == TokenType.IDENTIFIER) {
                    superTypes.add(advance().text)
                    if (checkSymbol("<")) skipGenericParams()
                } else if (st.type == TokenType.SYMBOL && st.text == ",") {
                    advance()
                } else {
                    break
                }
            }
        }

        val symbol = declare(
            name = nameToken.text,
            kind = if (keyword == "interface") ParsedSymbolKind.INTERFACE else ParsedSymbolKind.CLASS,
            token = nameToken,
            signature = buildString {
                append("$keyword ${nameToken.text}")
                if (superTypes.isNotEmpty()) append(" : ${superTypes.joinToString(", ")}")
            },
            superTypes = superTypes,
        )
        if (checkSymbol("{")) {
            advance()
            enterScope(symbol)
            parseBlockContents(ownsScope = true)
        }
    }

    private fun parseFunction() {
        advance() // function
        if (checkSymbol("*")) advance() // generator
        val nameToken = peek()
        val isAnonymous = nameToken.type != TokenType.IDENTIFIER
        if (!isAnonymous) advance()
        if (checkSymbol("<")) skipGenericParams()
        val parameters = if (checkSymbol("(")) {
            parseTsParameterList(if (!isAnonymous) nameToken.text else null)
        } else emptyList()
        // Return type annotation.
        if (matchSymbol(":")) skipTypeAnnotation()
        val symbol = if (!isAnonymous) {
            declare(
                name = nameToken.text,
                kind = if (scopeStack.isEmpty()) ParsedSymbolKind.FUNCTION else ParsedSymbolKind.METHOD,
                token = nameToken,
                signature = "function ${nameToken.text}(${parameters.joinToString(", ")})",
                parameters = parameters,
            )
        } else {
            null
        }
        if (checkSymbol("{")) {
            advance()
            if (symbol != null) enterScope(symbol)
            parseBlockContents(ownsScope = symbol != null)
        } else {
            matchSymbol(";")
        }
    }

    private fun parseVariable() {
        val keyword = advance().text // const | let | var
        // Destructuring: const { a, b } = ... — skip structurally.
        if (checkSymbol("{", "[")) {
            val open = advance().text
            skipBalanced(open, if (open == "{") "}" else "]")
            if (matchSymbol("=")) {
                val expr = collectTsExpression()
                collectCallSites(expr)
            }
            matchSymbol(";")
            return
        }
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            skipTsStatement()
            return
        }
        advance()
        if (matchSymbol(":")) skipTypeAnnotation()
        var isFunction = false
        var parameters: List<String> = emptyList()
        if (matchSymbol("=")) {
            // Arrow function: const f = (a) => ... or const f = async (a) => ...
            val exprStart = peek()
            var idx = pos
            // Look ahead for => at paren depth 0 within a bounded window.
            var depth = 0
            var foundArrow = false
            var scan = idx
            while (scan < tokens.size && scan < idx + 60) {
                val st = tokens[scan]
                if (st.type == TokenType.SYMBOL) {
                    when (st.text) {
                        "(" -> depth++
                        ")" -> depth--
                        "=>" -> if (depth == 0) {
                            foundArrow = true
                            break
                        }
                        ";", "{", "}" -> break
                    }
                }
                scan++
            }
            if (foundArrow) {
                isFunction = true
                // Parse the parameter list structurally.
                val savedPos = pos
                if (checkText("async")) advance()
                parameters = if (checkSymbol("(")) parseTsParameterList(nameToken.text) else {
                    // Single param without parens: x => ...
                    if (peek().type == TokenType.IDENTIFIER) listOf(advance().text) else emptyList()
                }
                matchSymbol("=>")
                @Suppress("UNUSED_EXPRESSION")
                savedPos
                @Suppress("UNUSED_EXPRESSION")
                exprStart
            }
            val expr = collectTsExpression()
            collectCallSites(expr)
        }
        declare(
            name = nameToken.text,
            kind = if (isFunction) {
                if (scopeStack.isEmpty()) ParsedSymbolKind.FUNCTION else ParsedSymbolKind.METHOD
            } else {
                ParsedSymbolKind.VARIABLE
            },
            token = nameToken,
            signature = "$keyword ${nameToken.text}" +
                if (parameters.isNotEmpty()) "(${parameters.joinToString(", ")})" else "",
            parameters = parameters,
        )
        matchSymbol(";")
    }

    private fun parseTypeAlias() {
        val keywordToken = advance() // type
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            skipTsStatement()
            return
        }
        advance()
        if (checkSymbol("<")) skipGenericParams()
        if (matchSymbol("=")) {
            // Skip the aliased type structurally.
            var depth = 0
            while (!isAtEnd()) {
                val t = peek()
                if (t.type == TokenType.SYMBOL) {
                    when (t.text) {
                        "{", "(", "[" -> {
                            depth++
                            advance()
                        }
                        "}", ")", "]" -> {
                            if (depth == 0) break
                            depth--
                            advance()
                        }
                        ";" -> if (depth == 0) {
                            advance()
                            break
                        } else {
                            advance()
                        }
                        else -> advance()
                    }
                } else {
                    advance()
                }
                // A new declaration keyword at depth 0 ends the alias.
                if (depth == 0 && peek().type == TokenType.IDENTIFIER &&
                    peek().text in setOf("class", "interface", "function", "const", "let", "var", "type", "enum")
                ) {
                    break
                }
            }
        }
        declare(
            name = nameToken.text,
            kind = ParsedSymbolKind.TYPE_ALIAS,
            token = nameToken,
            signature = "type ${nameToken.text}",
        )
        @Suppress("UNUSED_EXPRESSION")
        keywordToken
    }

    private fun parseEnum() {
        advance() // enum
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            skipTsStatement()
            return
        }
        advance()
        val symbol = declare(
            name = nameToken.text,
            kind = ParsedSymbolKind.ENUM,
            token = nameToken,
            signature = "enum ${nameToken.text}",
        )
        if (checkSymbol("{")) {
            advance()
            enterScope(symbol)
            parseBlockContents(ownsScope = true)
        }
    }

    private fun parseNamespace() {
        advance() // namespace
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            skipTsStatement()
            return
        }
        advance()
        val symbol = declare(
            name = nameToken.text,
            kind = ParsedSymbolKind.CLASS,
            token = nameToken,
            signature = "namespace ${nameToken.text}",
        )
        if (checkSymbol("{")) {
            advance()
            enterScope(symbol)
            parseBlockContents(ownsScope = true)
        }
    }

    private fun skipGenericParams() {
        if (!matchSymbol("<")) return
        var depth = 1
        while (!isAtEnd() && depth > 0) {
            val t = advance()
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "<" -> depth++
                    ">" -> depth--
                }
            }
        }
    }

    /** Skips a `: Type` annotation structurally. */
    private fun skipTypeAnnotation() {
        var depth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "<", "(", "[", "{" -> {
                        depth++
                        advance()
                    }
                    ">", ")", "]" -> {
                        if (depth == 0) return
                        depth--
                        advance()
                    }
                    "}" -> return
                    "=" -> if (depth == 0) return else advance()
                    ";" -> if (depth == 0) return else advance()
                    "," -> if (depth == 0) return else advance()
                    else -> advance()
                }
            } else if (t.type == TokenType.IDENTIFIER && depth == 0 &&
                t.text in setOf("function", "class", "interface")
            ) {
                return
            } else {
                advance()
            }
        }
    }

    private fun parseTsParameterList(functionName: String? = null): List<String> {
        val inner = skipBalanced("(", ")")
        val params = mutableListOf<String>()
        var i = 0
        while (i < inner.size) {
            // Skip modifiers/decorators.
            while (i < inner.size && inner[i].type == TokenType.IDENTIFIER &&
                inner[i].text in setOf("public", "private", "protected", "readonly")
            ) {
                i++
            }
            // Find the parameter name: first identifier not followed by . or ::
            // at depth 0. Pattern: [mods] name [: type] [= default]
            var j = i
            var nameToken: Token? = null
            var depth = 0
            while (j < inner.size) {
                val t = inner[j]
                if (t.type == TokenType.SYMBOL) {
                    when (t.text) {
                        "(", "<", "[", "{" -> depth++
                        ")", ">", "]", "}" -> depth--
                        "," -> if (depth == 0) break
                        "=" -> if (depth == 0) {
                            // Default value: skip to comma at depth 0.
                            j++
                            var d2 = 0
                            while (j < inner.size) {
                                val u = inner[j]
                                if (u.type == TokenType.SYMBOL) {
                                    when (u.text) {
                                        "(", "[", "{" -> d2++
                                        ")", "]", "}" -> d2--
                                        "," -> if (d2 == 0) break
                                    }
                                }
                                j++
                            }
                            break
                        }
                        ":" -> if (depth == 0) {
                            // Type annotation: skip to , or = at depth 0.
                            j++
                            var d2 = 0
                            while (j < inner.size) {
                                val u = inner[j]
                                if (u.type == TokenType.SYMBOL) {
                                    when (u.text) {
                                        "<", "(", "[", "{" -> d2++
                                        ">", ")", "]", "}" -> d2--
                                        "," -> if (d2 == 0) break
                                        "=" -> if (d2 == 0) break
                                    }
                                }
                                j++
                            }
                            break
                        }
                    }
                } else if (t.type == TokenType.IDENTIFIER && depth == 0 && nameToken == null) {
                    // Rest element ...args
                    nameToken = t
                }
                j++
            }
            if (nameToken != null && nameToken.text != "this") {
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
            i = j
            if (i < inner.size && inner[i].type == TokenType.SYMBOL && inner[i].text == ",") i++
            if (j == i && i < inner.size) i++
        }
        return params
    }

    /** Collects an `=` initializer expression, bounded. */
    private fun collectTsExpression(): List<Token> {
        val collected = mutableListOf<Token>()
        var parenDepth = 0
        var braceDepth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "(" -> parenDepth++
                    ")" -> if (parenDepth > 0) parenDepth-- else {
                        collected.add(advance())
                        continue
                    }
                    "{" -> braceDepth++
                    "}" -> if (braceDepth > 0) {
                        braceDepth--
                    } else {
                        return collected
                    }
                    ";" -> if (parenDepth == 0 && braceDepth == 0) return collected
                }
            }
            if (parenDepth == 0 && braceDepth == 0 && t.type == TokenType.IDENTIFIER &&
                t.text in setOf("function", "class", "interface", "const", "let", "var")
            ) {
                return collected
            }
            collected.add(advance())
        }
        return collected
    }

    /** Skips one TS statement, collecting call sites. */
    private fun skipTsStatement() {
        val collected = mutableListOf<Token>()
        var parenDepth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.SYMBOL) {
                when (t.text) {
                    "(" -> parenDepth++
                    ")" -> if (parenDepth > 0) parenDepth-- else break
                    ";" -> if (parenDepth == 0) {
                        advance()
                        break
                    }
                    "{" -> if (parenDepth == 0) break
                    "}" -> break
                }
            }
            collected.add(advance())
        }
        collectCallSites(collected)
        pendingDocComment = null
    }

    /** Parses members inside a `{...}` region already opened. */
    private fun parseBlockContents(ownsScope: Boolean) {
        var depth = 1
        val statementTokens = mutableListOf<Token>()
        var parenDepth = 0

        fun flushStatement() {
            if (statementTokens.isNotEmpty()) {
                // A class member shorthand like `name() {}` or `name = () =>`
                // at statement level: try member parsing.
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
                t.type == TokenType.COMMENT -> observeComment(advance())
                t.type == TokenType.SYMBOL && t.text == "@" -> {
                    flushStatement()
                    skipDecorator()
                    // A decorated member follows.
                    parseDeclarationOrSkip()
                    continue@blockLoop
                }
                t.type == TokenType.SYMBOL && t.text == ";" && parenDepth == 0 -> {
                    advance()
                    // Member shorthand check before flushing: `name;`? no.
                    flushStatement()
                    continue@blockLoop
                }
                else -> {
                    // Try member-declaration parsing at class-body level when the
                    // statement buffer is empty.
                    if (depth == 1 && parenDepth == 0 && statementTokens.isEmpty() &&
                        t.type == TokenType.IDENTIFIER
                    ) {
                        // Variable/function declarations (in function bodies).
                        if (t.text in setOf("const", "let", "var", "function", "class", "interface", "type", "enum")) {
                            flushStatement()
                            parseDeclarationOrSkip()
                            continue@blockLoop
                        }
                        if (tryParseClassMember()) continue@blockLoop
                    }
                    if (t.type == TokenType.SYMBOL) {
                        when (t.text) {
                            "(" -> parenDepth++
                            ")" -> if (parenDepth > 0) parenDepth--
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
                    line = peek().line,
                    character = 0,
                    message = "Unclosed '{' — expected '}'",
                    severity = "error",
                )
            )
        }
    }

    /**
     * Attempts `name(...)`, `name: type`, `get name()`, `async name()` member
     * parsing at class-body level. Returns false when it is not a member.
     */
    private fun tryParseClassMember(): Boolean {
        val startPos = pos
        // Skip member modifiers.
        while (!isAtEnd() && peek().type == TokenType.IDENTIFIER &&
            peek().text in setOf("static", "public", "private", "protected", "readonly", "abstract", "async", "get", "set", "declare", "override")
        ) {
            advance()
        }
        // Computed member ["x"] or [Symbol.x]: not handled, rewind.
        if (checkSymbol("[")) {
            pos = startPos
            return false
        }
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            pos = startPos
            return false
        }
        val next = peek(1)
        val isMember = next.type == TokenType.SYMBOL &&
            (next.text == "(" || next.text == ":" || next.text == "=" || next.text == ";" ||
                next.text == "<")
        if (!isMember) {
            pos = startPos
            return false
        }
        advance() // name
        when (next.text) {
            "(" -> {
                val parameters = parseTsParameterList(nameToken.text)
                if (matchSymbol(":")) skipTypeAnnotation()
                val symbol = declare(
                    name = nameToken.text,
                    kind = if (nameToken.text == "constructor") ParsedSymbolKind.CONSTRUCTOR else ParsedSymbolKind.METHOD,
                    token = nameToken,
                    signature = "${nameToken.text}(${parameters.joinToString(", ")})",
                    parameters = parameters,
                )
                if (checkSymbol("{")) {
                    advance()
                    enterScope(symbol)
                    parseBlockContents(ownsScope = true)
                } else {
                    matchSymbol(";")
                }
            }
            "<" -> {
                // Generic method: name<T>(...)
                skipGenericParams()
                if (checkSymbol("(")) {
                    val parameters = parseTsParameterList(nameToken.text)
                    if (matchSymbol(":")) skipTypeAnnotation()
                    val symbol = declare(
                        name = nameToken.text,
                        kind = ParsedSymbolKind.METHOD,
                        token = nameToken,
                        signature = "${nameToken.text}(${parameters.joinToString(", ")})",
                        parameters = parameters,
                    )
                    if (checkSymbol("{")) {
                        advance()
                        enterScope(symbol)
                        parseBlockContents(ownsScope = true)
                    } else {
                        matchSymbol(";")
                    }
                } else {
                    pos = startPos
                    return false
                }
            }
            else -> {
                // Property: name: Type = ...; name = ...
                if (next.text == ":") {
                    advance()
                    skipTypeAnnotation()
                }
                if (matchSymbol("=")) {
                    val expr = collectTsExpression()
                    collectCallSites(expr)
                }
                matchSymbol(";")
                declare(
                    name = nameToken.text,
                    kind = ParsedSymbolKind.PROPERTY,
                    token = nameToken,
                    signature = nameToken.text,
                )
            }
        }
        return true
    }
}
