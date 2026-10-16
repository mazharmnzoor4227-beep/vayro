package com.vayro.app.lsp

import com.vayro.app.editor.CodeCompletionEngine
import com.vayro.app.editor.CompletionItemKind
import com.vayro.app.editor.EditorDiagnosticsEngine
import com.vayro.app.runtime.CodeSymbol
import com.vayro.app.runtime.SemanticCodeEngine
import com.vayro.app.runtime.SymbolKind
import com.vayro.app.runtime.WorkspaceSemanticIndex
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Language Server Protocol (LSP) Manager.
 * Orchestrates language server process lifecycles and provides unified
 * Hover, Definition, References, Completions, and Code Actions.
 * Features an intelligent in-process fallback provider using SemanticCodeEngine.
 */
class LspManager(
    private val workspaceDir: File? = null,
    private val semanticIndex: WorkspaceSemanticIndex = WorkspaceSemanticIndex(workspaceDir),
    /**
     * Locates a real language-server binary for a language, or null when none is
     * installed. Defaults to a PATH lookup for well-known server binaries.
     * Injectable for tests.
     */
    private val serverBinaryLocator: (language: String) -> File? = ::defaultServerBinaryLocator,
) {

    private val serverStatuses = ConcurrentHashMap<String, LspServerStatus>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    /**
     * Milestone 3 — live JSON-RPC clients for languages with a real server
     * process. A language has an entry here only after the `initialize`
     * handshake succeeded.
     */
    private val protocolClients = ConcurrentHashMap<String, LspProtocolClient>()
    /** language -> filePath -> latest publishDiagnostics. */
    private val serverDiagnostics =
        ConcurrentHashMap<String, ConcurrentHashMap<String, List<LspDiagnostic>>>()

    init {
        // Initialize supported language statuses. Milestone 0 honesty: no real
        // language server is running at init — only the built-in fallback
        // intelligence (SemanticCodeEngine) is available. Never report
        // isRunning=true when only the fallback is active.
        listOf("Kotlin", "Java", "TypeScript", "JavaScript", "Python", "C++", "Go", "Rust", "PHP", "Dart").forEach { lang ->
            serverStatuses[lang] = LspServerStatus(
                language = lang,
                isRunning = false,
                isRealServer = false,
                isBuiltInFallback = true,
            )
        }
    }

    fun getServerStatus(language: String): LspServerStatus {
        return serverStatuses[language] ?: LspServerStatus(
            language = language,
            isRunning = false,
            isRealServer = false,
            isBuiltInFallback = true,
        )
    }

    /**
     * Starts a REAL language-server process for [language] and performs the
     * LSP `initialize` handshake over stdio.
     *
     * Returns true only when a server binary was found, its process launched,
     * AND the initialize handshake succeeded. When no binary is installed —
     * or the handshake fails — returns false and the status honestly reports
     * `isRunning=false` with the built-in fallback still available.
     */
    fun startServer(language: String, rootPath: String): Boolean {
        stopServer(language)
        val binary = runCatching { serverBinaryLocator(language) }.getOrNull()
        if (binary == null || !binary.canExecute()) {
            serverStatuses[language] = LspServerStatus(
                language = language,
                isRunning = false,
                isRealServer = false,
                isBuiltInFallback = true,
                lastError = "No language-server binary found for $language; using built-in fallback intelligence.",
            )
            return false
        }
        return try {
            val process = ProcessBuilder(binary.absolutePath, "--stdio")
                .directory(File(rootPath).takeIf { it.isDirectory } ?: workspaceDir ?: File("."))
                .redirectErrorStream(true)
                .start()
            // Milestone 3 — real LSP lifecycle: speak JSON-RPC to the server.
            val client = LspProtocolClient(process.inputStream, process.outputStream)
            val handshakeOk = runCatching { client.initialize(rootPath) }.getOrDefault(false)
            if (!handshakeOk) {
                runCatching { client.shutdown() }
                process.destroy()
                serverStatuses[language] = LspServerStatus(
                    language = language,
                    isRunning = false,
                    isRealServer = false,
                    isBuiltInFallback = true,
                    lastError = "Language server for $language launched but the LSP initialize handshake failed; using built-in fallback intelligence.",
                )
                return false
            }
            client.onDiagnostics { uri, diags ->
                val path = LspProtocolClient.uriToPath(uri)
                serverDiagnostics
                    .getOrPut(language) { ConcurrentHashMap() }[path] = diags
            }
            activeProcesses[language] = process
            protocolClients[language] = client
            serverStatuses[language] = LspServerStatus(
                language = language,
                isRunning = true,
                isRealServer = true,
                isBuiltInFallback = false,
                // Process.pid() is not in the compile SDK's android.jar; resolve reflectively.
                pid = runCatching {
                    Process::class.java.getMethod("pid").invoke(process) as? Long
                }.getOrNull(),
            )
            true
        } catch (e: Exception) {
            serverStatuses[language] = LspServerStatus(
                language = language,
                isRunning = false,
                isRealServer = false,
                isBuiltInFallback = true,
                lastError = "Failed to launch language server for $language: ${e.message}",
            )
            false
        }
    }

    fun stopServer(language: String): Boolean {
        runCatching { protocolClients.remove(language)?.shutdown() }
        activeProcesses.remove(language)?.destroy()
        serverDiagnostics.remove(language)
        // The built-in fallback intelligence remains available after stopping a
        // real server; only isRunning/isRealServer go false.
        serverStatuses[language] = LspServerStatus(
            language = language,
            isRunning = false,
            isRealServer = false,
            isBuiltInFallback = true,
        )
        return true
    }

    /** Live protocol client for [language], or null when no real server runs. */
    fun getProtocolClient(language: String): LspProtocolClient? = protocolClients[language]

    /** Latest server-published diagnostics for a file (empty when none). */
    fun getCachedDiagnostics(language: String, filePath: String): List<LspDiagnostic> =
        serverDiagnostics[language]?.get(filePath)
            ?: serverDiagnostics[language]?.get(LspProtocolClient.pathToUri(filePath))
            ?: emptyList()

    /** Maps LspManager display names to LSP `languageId` values. */
    private fun lspLanguageId(language: String): String = when (language) {
        "Kotlin" -> "kotlin"
        "Java" -> "java"
        "TypeScript" -> "typescript"
        "JavaScript" -> "javascript"
        "Python" -> "python"
        "C++" -> "cpp"
        "Go" -> "go"
        "Rust" -> "rust"
        "PHP" -> "php"
        "Dart" -> "dart"
        else -> language.lowercase()
    }

    fun restartServer(language: String, rootPath: String): Boolean {
        stopServer(language)
        return startServer(language, rootPath)
    }

    // --- LSP Query Surface with Built-In Fallback Provider ---

    /**
     * Computes Hover documentation and signature for symbol under cursor.
     *
     * Milestone 3: routes through a real server when one is running.
     */
    fun requestHover(
        language: String,
        filePath: String,
        line: Int, // 1-indexed
        character: Int,
        content: String,
    ): LspHover? {
        val client = protocolClients[language]
        if (client != null && client.isInitialized) {
            runCatching {
                client.didOpen(filePath, lspLanguageId(language), content)
                return client.hover(filePath, line, character)
            }
        }
        val word = getWordAt(content, line, character)
        if (word.isBlank()) return null

        val summary = SemanticCodeEngine.parseFile(filePath, content)
        val symbol = summary.symbols.firstOrNull { it.name.equals(word, ignoreCase = true) }
            ?: semanticIndex.getSymbolGraph().findNodesByName(word).firstOrNull()?.let {
                CodeSymbol(it.name, it.kind, it.line, signature = it.signature, docComment = it.docComment, superTypes = it.superTypes)
            }

        if (symbol != null) {
            val docs = buildString {
                appendLine("```$language")
                appendLine(symbol.signature ?: "${symbol.kind.name.lowercase()} ${symbol.name}")
                appendLine("```")
                if (!symbol.docComment.isNullOrBlank()) {
                    appendLine("---")
                    appendLine(symbol.docComment)
                }
                if (symbol.superTypes.isNotEmpty()) {
                    appendLine("Inherits/Implements: ${symbol.superTypes.joinToString(", ")}")
                }
            }.trimEnd()
            return LspHover(contents = docs)
        }

        return LspHover(contents = "`$word` ($language identifier)")
    }

    /**
     * Resolves Go to Definition targets.
     *
     * Milestone 3: when a real language server is running for [language], the
     * query goes through the LSP protocol; otherwise the built-in fallback
     * intelligence answers (honestly labelled by callers via ProviderRegistry).
     */
    fun requestDefinition(
        language: String,
        filePath: String,
        line: Int,
        character: Int,
        content: String,
    ): List<LspLocation> {
        // Real server path.
        val client = protocolClients[language]
        if (client != null && client.isInitialized) {
            runCatching {
                client.didOpen(filePath, lspLanguageId(language), content)
                return client.definition(filePath, line, character)
            }
        }
        // Built-in fallback path.
        val word = getWordAt(content, line, character)
        if (word.isBlank()) return emptyList()

        val summaries = semanticIndex.getSummaries().values
        val defs = SemanticCodeEngine.findDefinitions(word, summaries, exactMatch = true)

        return defs.map { (path, sym) ->
            LspLocation(
                uri = path,
                range = LspRange(
                    start = LspPosition(sym.line - 1, sym.characterOffset),
                    end = LspPosition(sym.line - 1, sym.characterOffset + sym.name.length),
                ),
            )
        }
    }

    /**
     * Resolves Find References usages.
     *
     * Milestone 3: routes through a real server when one is running.
     */
    fun requestReferences(
        language: String,
        filePath: String,
        line: Int,
        character: Int,
        content: String,
    ): List<LspLocation> {
        val client = protocolClients[language]
        if (client != null && client.isInitialized) {
            runCatching {
                client.didOpen(filePath, lspLanguageId(language), content)
                return client.references(filePath, line, character)
            }
        }
        val word = getWordAt(content, line, character)
        if (word.isBlank()) return emptyList()

        val summaries = semanticIndex.getSummaries().values
        val refs = SemanticCodeEngine.findReferences(word, summaries)

        return refs.map { ref ->
            LspLocation(
                uri = ref.sourceFilePath,
                range = LspRange(
                    start = LspPosition(ref.line - 1, 0),
                    end = LspPosition(ref.line - 1, word.length),
                ),
            )
        }
    }

    /**
     * Computes completions at cursor position.
     *
     * Milestone 3: routes through a real server when one is running.
     */
    fun requestCompletions(
        language: String,
        filePath: String,
        line: Int,
        character: Int,
        content: String,
    ): List<LspCompletionItem> {
        val client = protocolClients[language]
        if (client != null && client.isInitialized) {
            runCatching {
                client.didOpen(filePath, lspLanguageId(language), content)
                return client.completion(filePath, line, character)
            }
        }
        val cursorOffset = getOffsetForPosition(content, line, character)
        val projectSymbols = semanticIndex.getSymbolGraph().nodes.values.map {
            CodeSymbol(it.name, it.kind, it.line, signature = it.signature)
        }

        val items = CodeCompletionEngine.getCompletions(content, cursorOffset, language, projectSymbols)
        return items.map { item ->
            val kind = when (item.kind) {
                CompletionItemKind.KEYWORD -> LspCompletionItemKind.KEYWORD
                CompletionItemKind.FUNCTION -> LspCompletionItemKind.FUNCTION
                CompletionItemKind.CLASS -> LspCompletionItemKind.CLASS
                CompletionItemKind.VARIABLE -> LspCompletionItemKind.VARIABLE
                CompletionItemKind.PROPERTY -> LspCompletionItemKind.PROPERTY
                CompletionItemKind.SNIPPET -> LspCompletionItemKind.SNIPPET
                else -> LspCompletionItemKind.TEXT
            }
            LspCompletionItem(
                label = item.label,
                kind = kind,
                detail = item.detail,
                insertText = item.insertText,
            )
        }
    }

    /**
     * Computes contextual Code Actions (quick fixes).
     *
     * QA H2: every returned action carries [LspCodeAction.newText] — the full
     * corrected document — so tapping it actually applies the fix. Actions
     * that cannot be applied with one tap are NOT offered: an unclosed
     * delimiter has no single correct insertion point (appending the closer
     * at the document end silently corrupts nested code), and Extract
     * variable/function need interactive selection + naming.
     */
    fun requestCodeActions(
        language: String,
        filePath: String,
        line: Int,
        character: Int,
        content: String,
    ): List<LspCodeAction> {
        val actions = mutableListOf<LspCodeAction>()
        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(content, filePath)
        val lineDiags = diagnostics.filter { it.lineNumber == line }.ifEmpty { diagnostics }
        val lines = content.lines()

        for (diag in lineDiags) {
            when (diag.ruleId) {
                "linter.duplicate_import" -> {
                    // The diagnosed line IS the duplicate (second) import: drop it.
                    val idx = diag.lineNumber - 1
                    if (idx in lines.indices) {
                        val fixed = (lines.subList(0, idx) + lines.subList(idx + 1, lines.size))
                            .joinToString("\n")
                        actions.add(
                            LspCodeAction(
                                title = "Remove duplicate import statement",
                                kind = "quickfix",
                                isPreferred = true,
                                newText = fixed,
                                targetFile = filePath,
                            )
                        )
                    }
                }
                "linter.empty_catch" -> {
                    buildEmptyCatchFix(lines, diag.lineNumber, filePath)?.let { actions.add(it) }
                }
            }
        }

        return actions
    }

    /**
     * Builds the rethrow fix for a one-line `catch (...) {}`: replaces the
     * empty block with `throw <var>`, so the exception fails loudly instead
     * of being swallowed. Returns null when the caught variable cannot be
     * extracted reliably (e.g. `_`) or when code follows the `{}` on the
     * line — in both cases no fix is offered rather than a guessed one.
     */
    private fun buildEmptyCatchFix(
        lines: List<String>,
        lineNumber: Int,
        filePath: String,
    ): LspCodeAction? {
        val idx = lineNumber - 1
        if (idx !in lines.indices) return null
        val line = lines[idx]
        val match = Regex("""catch\s*\(([^)]*)\)\s*\{\s*\}""").find(line) ?: return null
        val after = line.substring(match.range.last + 1).trim()
        if (after.isNotEmpty() && !after.startsWith("//")) return null
        val inside = match.groupValues[1]
        val varName = inside.substringBefore(":").trim().split(Regex("\\s+")).lastOrNull().orEmpty()
        if (varName == "_" || !varName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val fixedLine = line.substring(0, match.range.first) +
            "catch ($inside) {\n$indent    throw $varName\n$indent}" +
            line.substring(match.range.last + 1)
        val fixed = lines.toMutableList().also { it[idx] = fixedLine }.joinToString("\n")
        return LspCodeAction(
            title = "Rethrow in empty catch block",
            kind = "quickfix",
            newText = fixed,
            targetFile = filePath,
        )
    }

    private fun getWordAt(content: String, lineNum: Int, charNum: Int): String {
        val lines = content.lines()
        if (lineNum !in 1..lines.size) return ""
        val line = lines[lineNum - 1]
        val col = charNum.coerceIn(0, line.length)
        if (line.isEmpty()) return ""

        var start = (col - 1).coerceAtLeast(0)
        while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
        var end = col
        while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++

        return if (start < end) line.substring(start, end).trim() else ""
    }

    private fun getOffsetForPosition(content: String, lineNum: Int, charNum: Int): Int {        val lines = content.lines()
        var offset = 0
        for (i in 0 until (lineNum - 1).coerceAtMost(lines.size)) {
            offset += lines[i].length + 1
        }
        return (offset + charNum).coerceIn(0, content.length)
    }
}

/**
 * Well-known language-server binaries looked up on PATH.
 */
private val LANGUAGE_SERVER_BINARIES: Map<String, List<String>> = mapOf(
    "Kotlin" to listOf("kotlin-language-server"),
    "Java" to listOf("jdtls", "java-language-server"),
    "TypeScript" to listOf("typescript-language-server"),
    "JavaScript" to listOf("typescript-language-server"),
    "Python" to listOf("pyright-langserver", "pylsp", "pyright"),
    "C++" to listOf("clangd"),
    "Go" to listOf("gopls"),
    "Rust" to listOf("rust-analyzer"),
    "PHP" to listOf("intelephense"),
    "Dart" to listOf("dart"),
)

/**
 * Default [LspManager] binary locator: searches PATH for a known server binary.
 * Returns null when nothing is installed, which makes [LspManager.startServer]
 * honestly report that only the built-in fallback is available.
 */
private fun defaultServerBinaryLocator(language: String): File? {
    val candidates = LANGUAGE_SERVER_BINARIES[language] ?: return null
    val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
    for (dir in pathDirs) {
        for (name in candidates) {
            val file = File(dir, name)
            if (file.isFile && file.canExecute()) return file
        }
    }
    return null
}
