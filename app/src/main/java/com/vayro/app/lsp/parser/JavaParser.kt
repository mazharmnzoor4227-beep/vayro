package com.vayro.app.lsp.parser

/**
 * Milestone 3 — a real structural parser for Java.
 *
 * Recognizes packages, imports (incl. static), classes/interfaces/enums/
 * records/annotation types with extends/implements lists, constructors,
 * methods (return type + parameter list) and fields — structurally, from the
 * token stream. Method vs field is decided by the `(` after the name, not by
 * regex. Produces genuine unclosed-delimiter and duplicate-definition
 * diagnostics.
 *
 * Not compiler-accurate: no type resolution. Real compiler accuracy comes
 * from jdtls / java-language-server when installed.
 */
class JavaParser(
    filePath: String,
    source: String,
) : BaseParser(filePath, "java", source, LanguageSyntax.JAVA) {

    override fun parse(): ParsedFile {
        while (!isAtEnd()) {
            val t = peek()
            when (t.type) {
                TokenType.COMMENT -> observeComment(advance())
                TokenType.IDENTIFIER -> when (t.text) {
                    "package" -> parsePackage()
                    "import" -> parseImport()
                    else -> parseMemberOrSkip()
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
                    else -> advance()
                }
                else -> advance()
            }
        }
        return buildResult()
    }

    private fun parsePackage() {
        advance() // package
        val parts = mutableListOf<String>()
        while (!isAtEnd() && !checkSymbol(";")) {
            val t = peek()
            if (t.type == TokenType.IDENTIFIER) parts.add(advance().text)
            else advance()
        }
        matchSymbol(";")
        if (parts.isNotEmpty()) packageName = parts.joinToString(".")
        pendingDocComment = null
    }

    private fun parseImport() {
        val importToken = advance() // import
        var isStatic = false
        if (checkText("static")) {
            advance()
            isStatic = true
        }
        val parts = mutableListOf<String>()
        var isStar = false
        while (!isAtEnd() && !checkSymbol(";")) {
            val t = peek()
            when {
                t.type == TokenType.IDENTIFIER -> parts.add(advance().text)
                t.type == TokenType.SYMBOL && t.text == "*" -> {
                    isStar = true
                    advance()
                }
                else -> advance()
            }
        }
        matchSymbol(";")
        val raw = parts.joinToString(".")
        if (raw.isNotEmpty()) {
            imports.add(
                ParsedImport(
                    rawText = (if (isStatic) "static " else "") + raw,
                    importedNames = if (isStar) listOf("*") else listOf(parts.last()),
                    line = importToken.line,
                    isStar = isStar,
                )
            )
        }
        pendingDocComment = null
    }

    /**
     * Parses a type/member declaration or skips one statement. Handles
     * modifiers and annotations first, then dispatches on the declaration
     * keyword or the `Type name(` / `Type name;` / `Type name =` patterns.
     */
    private fun parseMemberOrSkip() {
        // Skip modifiers and annotations.
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.IDENTIFIER && t.text in JAVA_MODIFIERS) {
                advance()
                continue
            }
            if (t.type == TokenType.SYMBOL && t.text == "@") {
                advance()
                // Annotation name (possibly qualified).
                while (!isAtEnd()) {
                    val n = peek()
                    if (n.type == TokenType.IDENTIFIER) advance()
                    else if (n.type == TokenType.SYMBOL && n.text == ".") advance()
                    else break
                }
                if (checkSymbol("(")) skipBalanced("(", ")")
                continue
            }
            break
        }
        if (isAtEnd()) return
        val t = peek()
        when {
            t.type == TokenType.COMMENT -> observeComment(advance())
            t.type == TokenType.IDENTIFIER && t.text in setOf("class", "interface", "enum", "record") ->
                parseTypeDeclaration()
            t.type == TokenType.SYMBOL && t.text == "@" && peek(1).type == TokenType.IDENTIFIER &&
                peek(1).text == "interface" -> {
                advance() // @
                parseTypeDeclaration()
            }
            else -> parseMethodOrField()
        }
    }

    private fun parseTypeDeclaration() {
        val keywordToken = advance()
        var kind = when (keywordToken.text) {
            "interface" -> ParsedSymbolKind.INTERFACE
            "enum" -> ParsedSymbolKind.ENUM
            "record" -> ParsedSymbolKind.CLASS
            else -> ParsedSymbolKind.CLASS
        }
        // @interface was consumed as @ + interface check above; handle plain.
        val nameToken = peek()
        if (nameToken.type != TokenType.IDENTIFIER) {
            if (checkSymbol("{")) {
                advance()
                parseBlockContents(ownsScope = false)
            }
            return
        }
        advance()
        val name = nameToken.text
        if (checkSymbol("<")) skipAngleBrackets()

        // Record components: record Point(int x, int y)
        var parameters: List<String> = emptyList()
        if (keywordToken.text == "record" && checkSymbol("(")) {
            parameters = parseJavaParameterList(name)
        }

        // extends / implements
        val superTypes = mutableListOf<String>()
        while (!isAtEnd() && checkText("extends", "implements")) {
            advance() // extends | implements
            while (!isAtEnd()) {
                val st = peek()
                if (st.type == TokenType.IDENTIFIER && st.text !in setOf("extends", "implements")) {
                    superTypes.add(advance().text)
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
                append("${keywordToken.text} $name")
                if (superTypes.isNotEmpty()) append(" : ${superTypes.joinToString(", ")}")
            },
            superTypes = superTypes,
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

    /**
     * Parses `Type name(...)` (method/constructor) or `Type name` (field) at
     * the current position. The decision is structural: a `(` immediately
     * after the name means callable.
     */
    private fun parseMethodOrField() {
        // Collect the type + name tokens: [annotations...] Type name
        val startPos = pos
        // Skip a leading type: identifiers with dots and generics and [].
        var nameToken: Token? = null
        var nameIndex = -1
        var i = pos
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.IDENTIFIER) {
                // Look ahead: is the NEXT token ( or ; or = or , ?
                val n = tokens.getOrNull(i + 1)
                if (n != null && n.type == TokenType.SYMBOL && n.text in setOf("(", ";", "=", ",")) {
                    nameToken = t
                    nameIndex = i
                    break
                }
                i++
            } else if (t.type == TokenType.SYMBOL && t.text in setOf(".", "[", "]", "?", "...")) {
                i++
            } else if (t.type == TokenType.SYMBOL && t.text == "<") {
                // Skip generic arguments.
                var depth = 1
                i++
                while (i < tokens.size && depth > 0) {
                    val g = tokens[i]
                    if (g.type == TokenType.SYMBOL) {
                        if (g.text == "<") depth++
                        else if (g.text == ">") depth--
                    }
                    i++
                }
            } else {
                break
            }
        }
        // A name preceded by `.` is a member access (e.g. `items.add(...)`),
        // not a declaration — skip as a statement.
        if (nameToken != null && nameIndex > 0) {
            val prev = tokens.getOrNull(nameIndex - 1)
            if (prev != null && prev.type == TokenType.SYMBOL && prev.text == ".") {
                nameToken = null
            }
        }
        if (nameToken == null) {
            // Not a recognizable member: skip one statement, collecting calls.
            skipJavaStatement()
            return
        }
        pos = tokens.indexOf(nameToken)
        val nameTok = advance() // the name
        val after = peek()
        if (after.type == TokenType.SYMBOL && after.text == "(") {
            // Method or constructor.
            val parameters = parseJavaParameterList(nameTok.text)
            // throws clause
            if (checkText("throws")) {
                advance()
                while (!isAtEnd() && !checkSymbol("{", ";")) advance()
            }
            val container = scopeStack.lastOrNull()
            val isCtor = container != null && nameTok.text == container.name
            val symbol = declare(
                name = nameTok.text,
                kind = if (isCtor) ParsedSymbolKind.CONSTRUCTOR else ParsedSymbolKind.METHOD,
                token = nameTok,
                signature = "${nameTok.text}(${parameters.joinToString(", ")})",
                parameters = parameters,
            )
            when {
                checkSymbol("{") -> {
                    advance()
                    enterScope(symbol)
                    parseBlockContents(ownsScope = true)
                }
                checkSymbol(";") -> advance()
                else -> skipJavaStatement()
            }
        } else {
            // Field: Type name [= ...] [, name2 ...] ;
            val fieldNames = mutableListOf(nameTok)
            declare(
                name = nameTok.text,
                kind = ParsedSymbolKind.VARIABLE,
                token = nameTok,
                signature = "field ${nameTok.text}",
            )
            skipFieldRest(fieldNames)
        }
        @Suppress("UNUSED_EXPRESSION")
        startPos
    }

    /** Skips the rest of a field declaration, declaring `, name2` siblings. */
    private fun skipFieldRest(firstNames: MutableList<Token>) {
        val collected = mutableListOf<Token>()
        while (!isAtEnd()) {
            val t = peek()
            if (t.type == TokenType.SYMBOL && t.text == ";") {
                advance()
                break
            }
            if (t.type == TokenType.SYMBOL && t.text == ",") {
                advance()
                val n = peek()
                if (n.type == TokenType.IDENTIFIER) {
                    advance()
                    firstNames.add(n)
                    declare(
                        name = n.text,
                        kind = ParsedSymbolKind.VARIABLE,
                        token = n,
                        signature = "field ${n.text}",
                    )
                }
                continue
            }
            if (t.type == TokenType.SYMBOL && t.text == "{") break
            collected.add(advance())
        }
        collectCallSites(collected)
    }

    /** Skips one Java statement, collecting call sites. */
    private fun skipJavaStatement() {
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

    private fun parseJavaParameterList(functionName: String? = null): List<String> {
        val inner = skipBalanced("(", ")")
        val params = mutableListOf<String>()
        var i = 0
        while (i < inner.size) {
            // Skip annotations and modifiers.
            while (i < inner.size && inner[i].type == TokenType.SYMBOL && inner[i].text == "@") {
                i++
                while (i < inner.size && (inner[i].type == TokenType.IDENTIFIER ||
                        (inner[i].type == TokenType.SYMBOL && inner[i].text == "."))
                ) {
                    i++
                }
            }
            while (i < inner.size && inner[i].type == TokenType.IDENTIFIER &&
                inner[i].text in setOf("final")
            ) {
                i++
            }
            // Type ... name
            var nameToken: Token? = null
            var j = i
            var lastIdent: Token? = null
            var depth = 0
            while (j < inner.size) {
                val t = inner[j]
                if (t.type == TokenType.SYMBOL) {
                    when (t.text) {
                        "<", "(", "[" -> depth++
                        ">", ")", "]" -> depth--
                        "," -> if (depth == 0) break
                        "..." -> { /* varargs marker */ }
                    }
                } else if (t.type == TokenType.IDENTIFIER && depth == 0) {
                    lastIdent = t
                }
                j++
            }
            nameToken = lastIdent
            if (nameToken != null) {
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
            // Guarantee progress on unparseable input.
            if (j == i && i < inner.size) i++
        }
        return params
    }

    private fun skipAngleBrackets() {
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

    /** Parses member declarations inside a `{...}` region already opened. */
    private fun parseBlockContents(ownsScope: Boolean) {
        var depth = 1
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
                t.type == TokenType.COMMENT -> observeComment(advance())
                t.type == TokenType.SYMBOL && t.text == ";" && parenDepth == 0 -> {
                    advance()
                    flushStatement()
                    continue@blockLoop
                }
                // Static/instance initializer blocks are handled by the { case.
                else -> {
                    // A member declaration starts at statement level. Heuristic:
                    // try member parsing when we see an identifier at depth 1
                    // and parenDepth 0 and the statement buffer is empty.
                    if (depth == 1 && parenDepth == 0 && statementTokens.isEmpty() &&
                        (t.type == TokenType.IDENTIFIER || (t.type == TokenType.SYMBOL && t.text == "@"))
                    ) {
                        parseMemberOrSkip()
                        continue@blockLoop
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

    companion object {
        private val JAVA_MODIFIERS: Set<String> = setOf(
            "public", "private", "protected", "static", "final",
            "abstract", "synchronized", "volatile", "transient",
            "native", "strictfp", "default", "sealed", "non-sealed",
        )
    }
}
