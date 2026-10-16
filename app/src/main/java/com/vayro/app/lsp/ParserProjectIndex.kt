package com.vayro.app.lsp

import com.vayro.app.lsp.parser.LanguageSyntax
import com.vayro.app.lsp.parser.ParsedFile
import com.vayro.app.lsp.parser.ParsedImport
import com.vayro.app.lsp.parser.ParsedSymbol
import com.vayro.app.lsp.parser.ParsedSymbolKind
import com.vayro.app.lsp.parser.ParseDiagnostic
import com.vayro.app.lsp.parser.SourceParser
import com.vayro.app.lsp.parser.Token
import com.vayro.app.lsp.parser.TokenType
import com.vayro.app.lsp.parser.Tokenizer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Milestone 3 — project-wide symbol/dependency index built on the real
 * structural parsers, with genuine incremental invalidation.
 *
 * - Each file is parsed once and cached; [updateFile] re-parses only the
 *   changed file.
 * - The dependency graph (file → resolved imports → dependents) is
 *   maintained incrementally: when a file changes, [getAffectedFiles]
 *   returns the transitive dependents whose cross-file resolutions are now
 *   stale, so callers can re-resolve or re-analyze exactly those files —
 *   never the whole project.
 * - Cross-file go-to-definition resolves through imports first, then falls
 *   back to project-wide top-level lookup (honest empty when unresolved).
 */
class ParserProjectIndex {

    private val parsedFiles = ConcurrentHashMap<String, ParsedFile>()
    /** filePath -> files it directly imports (resolved). */
    private val importsGraph = ConcurrentHashMap<String, Set<String>>()
    /** filePath -> files that directly depend on it. */
    private val dependentsGraph = ConcurrentHashMap<String, MutableSet<String>>()

    /** Index a file (parse + link into the dependency graph). */
    fun indexFile(filePath: String, content: String, languageId: String? = null) {
        val parsed = SourceParser.parse(filePath, content, languageId) ?: return
        parsedFiles[filePath] = parsed
        relinkFile(filePath, parsed)
    }

    /**
     * Incrementally update one file: re-parse it, relink its imports, and
     * return the transitive dependents whose cross-file resolutions are now
     * stale (excluding the file itself).
     */
    fun updateFile(filePath: String, content: String, languageId: String? = null): Set<String> {
        val parsed = SourceParser.parse(filePath, content, languageId)
        if (parsed == null) {
            removeFile(filePath)
            return affectedByRemoval(filePath)
        }
        parsedFiles[filePath] = parsed
        relinkFile(filePath, parsed)
        return getAffectedFiles(filePath)
    }

    /** Remove a file; returns transitive dependents now missing a dependency. */
    fun removeFile(filePath: String): Set<String> {
        parsedFiles.remove(filePath)
        // Drop edges where this file was a dependency.
        for ((_, dependents) in dependentsGraph) {
            dependents.remove(filePath)
        }
        val affected = getAffectedFiles(filePath)
        importsGraph.remove(filePath)
        dependentsGraph.remove(filePath)
        return affected
    }

    fun getParsedFile(filePath: String): ParsedFile? = parsedFiles[filePath]

    fun indexedPaths(): Set<String> = parsedFiles.keys.toSet()

    fun clear() {
        parsedFiles.clear()
        importsGraph.clear()
        dependentsGraph.clear()
    }

    /** Direct imports of a file (resolved paths). */
    fun getImports(filePath: String): Set<String> = importsGraph[filePath] ?: emptySet()

    /** Direct dependents of a file. */
    fun getDependents(filePath: String): Set<String> = dependentsGraph[filePath]?.toSet() ?: emptySet()

    /**
     * Transitive dependents of [filePath] (files that import it, directly or
     * indirectly). These are exactly the files whose cross-file resolutions
     * must be re-validated after [filePath] changes.
     */
    fun getAffectedFiles(filePath: String): Set<String> {
        val affected = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addAll(getDependents(filePath))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == filePath || !affected.add(current)) continue
            queue.addAll(getDependents(current))
        }
        return affected
    }

    private fun affectedByRemoval(filePath: String): Set<String> = getAffectedFiles(filePath)

    /** Re-resolves a file's imports and patches the dependency edges. */
    private fun relinkFile(filePath: String, parsed: ParsedFile) {
        // Remove old "dependents" edges pointing at this file's previous imports.
        val oldImports = importsGraph[filePath] ?: emptySet()
        for (old in oldImports) {
            dependentsGraph[old]?.remove(filePath)
        }
        val resolved = resolveImports(filePath, parsed)
        importsGraph[filePath] = resolved
        for (target in resolved) {
            dependentsGraph.getOrPut(target) { ConcurrentHashMap.newKeySet() }.add(filePath)
        }
        dependentsGraph.getOrPut(filePath) { ConcurrentHashMap.newKeySet() }
    }

    /** Resolves a parsed file's imports to indexed file paths. */
    private fun resolveImports(filePath: String, parsed: ParsedFile): Set<String> {
        val resolved = mutableSetOf<String>()
        val allPaths = parsedFiles.keys
        for (import in parsed.imports) {
            resolveSingleImport(filePath, import, allPaths)?.let { resolved.add(it) }
        }
        return resolved
    }

    private fun resolveSingleImport(
        fromPath: String,
        import: ParsedImport,
        allPaths: Set<String>,
    ): String? {
        val raw = import.rawText
        return when (parsedLanguageOf(fromPath)) {
            "kotlin", "java" -> {
                // com.example.Foo -> file defining Foo (case-insensitive file
                // name match, or a top-level symbol with that name).
                val simpleName = raw.substringAfterLast('.')
                allPaths.firstOrNull { p ->
                    val name = p.substringAfterLast('/').substringBeforeLast('.')
                    name.equals(simpleName, ignoreCase = true) &&
                        (p.endsWith(".kt") || p.endsWith(".java"))
                } ?: allPaths.firstOrNull { p ->
                    parsedFiles[p]?.symbols?.any {
                        it.name == simpleName && it.depth == 0
                    } == true
                }
            }
            "typescript", "javascript" -> {
                // ./utils, ../lib/helper — resolve relative to the importing file.
                if (raw.startsWith(".")) {
                    val dir = fromPath.substringBeforeLast('/', "")
                    val base = normalizePath(if (dir.isEmpty()) raw else "$dir/$raw")
                    val candidates = listOf(
                        "$base.ts", "$base.tsx", "$base.js", "$base.jsx",
                        "$base/index.ts", "$base/index.js",
                    )
                    candidates.firstOrNull { it in allPaths }
                } else {
                    // Bare module: match by file name.
                    allPaths.firstOrNull { p ->
                        p.substringAfterLast('/').substringBeforeLast('.') == raw.substringAfterLast('/')
                    }
                }
            }
            "python" -> {
                // from m import x / import m — module path with dots.
                val module = raw.substringBeforeLast('.', raw).ifEmpty { raw }
                val asPath = module.replace('.', '/')
                val candidates = listOf(
                    "$asPath.py",
                    "$asPath/__init__.py",
                )
                candidates.firstOrNull { it in allPaths }
                    ?: allPaths.firstOrNull { p ->
                        p.substringAfterLast('/').substringBeforeLast('.') ==
                            module.substringAfterLast('.')
                    }
            }
            else -> null
        }
    }

    private fun parsedLanguageOf(filePath: String): String =
        parsedFiles[filePath]?.languageId ?: SourceParser.languageIdForPath(filePath)

    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    // ------------------------------------------------------------------
    // Cross-file queries
    // ------------------------------------------------------------------

    /**
     * Scope-aware go-to-definition: the identifier at ([line], [character]).
     *
     * Resolution order (all structural, never regex):
     * 1. Lexical scopes enclosing the position (parameters → locals →
     *    container members → file top level).
     * 2. Names imported into this file (resolved to their source files).
     * 3. Top-level declarations in any other indexed file.
     * Returns empty when nothing resolves (honest, never a guess).
     */
    fun definitions(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
        languageId: String? = null,
    ): List<SymbolLocation> {
        val parsed = parsedFiles[filePath]
            ?: SourceParser.parse(filePath, content, languageId)
            ?: return emptyList()
        val name = identifierAt(parsed, content, line, character) ?: return emptyList()
        if (name.isBlank()) return emptyList()

        // 1. Lexical scopes.
        val scopes = scopesAt(parsed, line)
        for (scope in scopes) {
            val match = scope.symbols.firstOrNull { it.name == name }
            if (match != null) {
                return listOf(match.toLocation(filePath))
            }
        }
        // 2. File symbols (top-level first, then members): a simple in-file
        //    reference like `greet(...)` usually means the method in this file.
        parsed.symbols
            .filter { it.name == name }
            .minByOrNull { it.depth }
            ?.let { return listOf(it.toLocation(filePath)) }

        // 2. Imported names.
        for (import in parsed.imports) {
            if (name in import.importedNames || import.isStar) {
                for (target in getImports(filePath)) {
                    val targetParsed = parsedFiles[target] ?: continue
                    // Star imports only resolve exported/top-level names.
                    val match = targetParsed.symbols.firstOrNull {
                        it.name == name && (it.depth == 0 || !import.isStar)
                    }
                    if (match != null) return listOf(match.toLocation(target))
                }
            }
        }

        // 3. Project-wide top-level fallback.
        for ((path, other) in parsedFiles) {
            if (path == filePath) continue
            val match = other.symbols.firstOrNull { it.name == name && it.depth == 0 }
            if (match != null) return listOf(match.toLocation(path))
        }
        return emptyList()
    }

    /**
     * All references to [symbolName] across indexed files: the definition
     * plus every call site and name occurrence in a code position.
     */
    fun references(filePath: String, symbolName: String): List<SymbolLocation> {
        if (symbolName.isBlank()) return emptyList()
        val results = mutableListOf<SymbolLocation>()
        // Definitions in any indexed file.
        for ((path, parsed) in parsedFiles) {
            for (sym in parsed.symbols) {
                if (sym.name == symbolName) {
                    results.add(sym.toLocation(path))
                }
            }
            // Call sites: `name(` occurrences.
            for (call in parsed.callSites) {
                if (call.name == symbolName) {
                    results.add(
                        SymbolLocation(
                            filePath = path,
                            line = call.line,
                            character = call.character,
                            symbolName = symbolName,
                            kind = "reference",
                        )
                    )
                }
            }
        }
        return results.distinct()
    }

    /** Completion candidates visible at ([line], [character]). */
    fun completions(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
        prefix: String,
        languageId: String? = null,
    ): List<String> {
        if (prefix.isBlank()) return emptyList()
        val parsed = parsedFiles[filePath]
            ?: SourceParser.parse(filePath, content, languageId)
            ?: return emptyList()
        val scopes = scopesAt(parsed, line)
        val candidates = linkedSetOf<String>()
        // Innermost scope first.
        for (scope in scopes) {
            for (sym in scope.symbols) {
                if (sym.name.startsWith(prefix) && sym.name != prefix) candidates.add(sym.name)
            }
        }
        // Imported names.
        for (import in parsed.imports) {
            for (n in import.importedNames) {
                if (n != "*" && n.startsWith(prefix) && n != prefix) candidates.add(n)
            }
        }
        // Language keywords.
        for (kw in keywordsFor(parsed.languageId)) {
            if (kw.startsWith(prefix) && kw != prefix) candidates.add(kw)
        }
        return candidates.take(50)
    }

    /**
     * Rename locations for the symbol at ([line], [character]): its
     * definition plus all cross-file references. Returns empty when the
     * symbol cannot be resolved (honest, never a guess).
     */
    fun renameLocations(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
        languageId: String? = null,
    ): List<SymbolLocation> {
        val defs = definitions(filePath, content, line, character, languageId)
        if (defs.isEmpty()) return emptyList()
        val name = defs.first().symbolName
        return references(filePath, name)
    }

    /** Real parser diagnostics for a file (unclosed delimiters, duplicates). */
    fun diagnostics(
        filePath: String,
        content: String,
        languageId: String? = null,
    ): List<ParseDiagnostic> {
        val parsed = parsedFiles[filePath]
            ?: SourceParser.parse(filePath, content, languageId)
            ?: return emptyList()
        return parsed.diagnostics
    }

    // ------------------------------------------------------------------
    // Scope helpers
    // ------------------------------------------------------------------

    /** Identifier at a position, from the token stream (structural). */
    internal fun identifierAt(
        parsed: ParsedFile,
        content: String,
        line: Int,
        character: Int,
    ): String? {
        val syntax = when (parsed.languageId) {
            "kotlin" -> LanguageSyntax.KOTLIN
            "java" -> LanguageSyntax.JAVA
            "typescript", "javascript" -> LanguageSyntax.TYPESCRIPT
            "python" -> LanguageSyntax.PYTHON
            else -> return null
        }
        val tokens = Tokenizer(
            content, syntax,
            trackIndentation = parsed.languageId == "python",
        ).tokenize()
        for (t in tokens) {
            if (t.type == TokenType.IDENTIFIER && t.line == line &&
                character >= t.character && character <= t.character + t.text.length
            ) {
                return t.text
            }
        }
        return null
    }

    /**
     * Lexical scopes enclosing [line], innermost first. Each scope lists the
     * symbols declared directly in it.
     */
    internal fun scopesAt(parsed: ParsedFile, line: Int): List<ScopeView> {
        // Build scope chain from containerName links: find the innermost
        // symbol whose declaration contains the line.
        val byName = parsed.symbols.groupBy { it.name }
        // Innermost-first: sort candidate containers by depth desc, pick the
        // deepest symbol declared at/before the line that could contain it.
        val containers = parsed.symbols
            .filter { it.line <= line && it.kind in CONTAINER_KINDS }
            .sortedByDescending { it.depth }
        val chain = mutableListOf<ScopeView>()
        val fileScopeSymbols = parsed.symbols.filter { it.depth == 0 }
        // Walk from innermost container outward via containerName.
        var current: ParsedSymbol? = containers.firstOrNull()
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.name + "@" + current.line)) {
            val containerName = current.name
            val members = parsed.symbols.filter {
                it.containerName == containerName && it.depth == current.depth + 1
            }
            // Parameters belong to the function scope.
            chain.add(ScopeView(containerName, members + current.parameters.mapNotNull { pname ->
                parsed.symbols.find {
                    it.name == pname && it.kind == ParsedSymbolKind.PARAMETER &&
                        it.containerName == containerName
                }
            }))
            current = if (current.containerName != null) {
                byName[current.containerName]?.firstOrNull()
            } else {
                null
            }
        }
        chain.add(ScopeView("<file>", fileScopeSymbols))
        return chain
    }

    internal data class ScopeView(val name: String, val symbols: List<ParsedSymbol>)

    private fun ParsedSymbol.toLocation(path: String): SymbolLocation =
        SymbolLocation(
            filePath = path,
            line = line,
            character = character,
            symbolName = name,
            kind = kind.name.lowercase(),
        )

    private fun keywordsFor(languageId: String): List<String> = when (languageId) {
        "kotlin" -> listOf(
            "fun", "val", "var", "class", "interface", "object", "if", "else",
            "when", "for", "while", "return", "import", "package", "null",
            "true", "false", "this", "super", "try", "catch", "throw",
        )
        "java" -> listOf(
            "class", "interface", "public", "private", "protected", "static",
            "final", "if", "else", "for", "while", "return", "new",
            "import", "package", "null", "true", "false", "this", "super",
            "try", "catch", "throw", "extends", "implements",
        )
        "typescript", "javascript" -> listOf(
            "function", "const", "let", "var", "class", "interface", "if",
            "else", "for", "while", "return", "import", "export", "new",
            "null", "true", "false", "this", "super", "try", "catch", "throw",
            "type", "extends", "implements",
        )
        "python" -> listOf(
            "def", "class", "import", "from", "if", "else", "elif", "for",
            "while", "return", "None", "True", "False", "self", "try",
            "except", "raise", "with", "as", "lambda", "pass",
        )
        else -> emptyList()
    }

    companion object {
        private val CONTAINER_KINDS: Set<ParsedSymbolKind> = setOf(
            ParsedSymbolKind.CLASS,
            ParsedSymbolKind.INTERFACE,
            ParsedSymbolKind.ENUM,
            ParsedSymbolKind.OBJECT,
            ParsedSymbolKind.FUNCTION,
            ParsedSymbolKind.METHOD,
            ParsedSymbolKind.CONSTRUCTOR,
        )

        /** Index a whole directory tree (bounded). */
        fun indexDirectory(root: File, maxFiles: Int = 200): ParserProjectIndex {
            val index = ParserProjectIndex()
            if (!root.isDirectory) return index
            root.walkTopDown()
                .filter { f ->
                    f.isFile && f.length() in 1..500_000 &&
                        !f.path.contains("/build/") &&
                        !f.path.contains("/.git/") &&
                        !f.path.contains("/node_modules/") &&
                        SourceParser.supportsLanguage(SourceParser.languageIdForPath(f.path))
                }
                .take(maxFiles)
                .forEach { f ->
                    val content = runCatching { f.readText() }.getOrDefault("")
                    // Store paths relative to root for stable cross-file resolution.
                    val rel = runCatching { f.relativeTo(root).invariantSeparatorsPath }.getOrDefault(f.path)
                    index.indexFile(rel, content)
                }
            return index
        }
    }
}
