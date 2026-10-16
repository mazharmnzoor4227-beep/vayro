package com.vayro.app.lsp

/**
 * Milestone 3 — intelligence provider backed by a REAL running language
 * server (via [LspProtocolClient]).
 *
 * Used by [ProviderRegistry] only when [LspManager] reports a genuine
 * server process running for the language. Results are protocol-accurate;
 * when the server cannot answer, the provider returns honest empties and
 * the registry falls through to the parser providers.
 */
class LspServerBackedProvider(
    private val language: String,
    private val lspManager: LspManager,
) : LanguageIntelligenceProvider {

    override val providerName: String = "Language server ($language)"
    override val isFallback: Boolean = false

    override fun supportedLanguages(): Set<String> = setOf(language.lowercase())

    private fun client(): LspProtocolClient? = lspManager.getProtocolClient(language)

    override fun definitions(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
    ): List<SymbolLocation> {
        val client = client() ?: return emptyList()
        return runCatching {
            client.definition(filePath, line, character).map { loc ->
                SymbolLocation(
                    filePath = LspProtocolClient.uriToPath(loc.uri),
                    line = loc.range.start.line + 1,
                    character = loc.range.start.character,
                    symbolName = "",
                    kind = "definition",
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun references(
        filePath: String,
        content: String,
        symbolName: String,
    ): List<SymbolLocation> {
        // LSP references need a position; without one we cannot query.
        // Return empty honestly rather than guessing.
        return emptyList()
    }

    fun referencesAt(
        filePath: String,
        line: Int,
        character: Int,
    ): List<SymbolLocation> {
        val client = client() ?: return emptyList()
        return runCatching {
            client.references(filePath, line, character).map { loc ->
                SymbolLocation(
                    filePath = LspProtocolClient.uriToPath(loc.uri),
                    line = loc.range.start.line + 1,
                    character = loc.range.start.character,
                    symbolName = "",
                    kind = "reference",
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun diagnostics(
        filePath: String,
        content: String,
    ): List<IntelligenceDiagnostic> {
        val cached = lspManager.getCachedDiagnostics(language, filePath)
        return cached.map { d ->
            IntelligenceDiagnostic(
                filePath = filePath,
                line = d.range.start.line + 1,
                message = d.message,
                severity = when (d.severity) {
                    LspDiagnosticSeverity.ERROR -> "error"
                    LspDiagnosticSeverity.WARNING -> "warning"
                    LspDiagnosticSeverity.INFORMATION -> "info"
                    else -> "hint"
                },
                source = providerName,
            )
        }
    }

    override fun completionItems(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
        prefix: String,
    ): List<String> {
        val client = client() ?: return emptyList()
        return runCatching {
            client.completion(filePath, line, character)
                .map { it.label }
                .filter { it.startsWith(prefix) && it != prefix }
                .distinct()
                .take(50)
        }.getOrDefault(emptyList())
    }
}
