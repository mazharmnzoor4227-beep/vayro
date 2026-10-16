package com.vayro.app.editor

import com.vayro.app.lsp.IntelligenceDiagnostic
import com.vayro.app.lsp.LanguageIntelligenceProvider
import com.vayro.app.lsp.SymbolLocation
import com.vayro.app.runtime.SemanticCodeEngine

/**
 * Milestone 2 — the explicitly-labelled heuristic fallback provider.
 *
 * Backs editor intelligence commands with the existing regex-based
 * [SemanticCodeEngine] until Milestone 3 lands real parser/LSP providers.
 * Every result is traceable to [providerName]; the UI must surface that label
 * and must never present these results as compiler-accurate.
 *
 * Honesty rules for the fallback:
 * - definitions/references: best-effort symbol-name matching only;
 * - diagnostics: always empty (the heuristic engine cannot produce trustworthy
 *   diagnostics, so it reports none rather than noise);
 * - completions: symbol names from the current file matching the prefix.
 */
class HeuristicIntelligenceProvider : LanguageIntelligenceProvider {

    override val providerName: String = "Heuristic regex fallback (not compiler-accurate)"
    override val isFallback: Boolean = true

    override fun supportedLanguages(): Set<String> = setOf(
        "kotlin", "java", "typescript", "javascript", "python",
        "go", "rust", "cpp", "c", "shell", "php", "dart",
    )

    override fun definitions(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
    ): List<SymbolLocation> {
        val symbolName = wordAt(content, line, character) ?: return emptyList()
        if (symbolName.isBlank()) return emptyList()
        val summary = runCatching { SemanticCodeEngine.parseFile(filePath, content) }.getOrNull()
            ?: return emptyList()
        return SemanticCodeEngine.findDefinitions(symbolName, listOf(summary), exactMatch = true)
            .map { (path, symbol) ->
                SymbolLocation(
                    filePath = path,
                    line = symbol.line,
                    character = symbol.characterOffset,
                    symbolName = symbol.name,
                    kind = symbol.kind.name.lowercase(),
                )
            }
    }

    override fun references(
        filePath: String,
        content: String,
        symbolName: String,
    ): List<SymbolLocation> {
        if (symbolName.isBlank()) return emptyList()
        val summary = runCatching { SemanticCodeEngine.parseFile(filePath, content) }.getOrNull()
            ?: return emptyList()
        return SemanticCodeEngine.findReferences(symbolName, listOf(summary))
            .map { ref ->
                SymbolLocation(
                    filePath = ref.sourceFilePath,
                    line = ref.line,
                    symbolName = ref.symbolName,
                    kind = ref.referenceType.name.lowercase(),
                )
            }
    }

    override fun diagnostics(
        filePath: String,
        content: String,
    ): List<IntelligenceDiagnostic> = emptyList()

    override fun completionItems(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
        prefix: String,
    ): List<String> {
        if (prefix.isBlank()) return emptyList()
        val summary = runCatching { SemanticCodeEngine.parseFile(filePath, content) }.getOrNull()
            ?: return emptyList()
        return summary.symbols
            .map { it.name }
            .filter { it.startsWith(prefix) && it != prefix }
            .distinct()
            .take(50)
    }

    /**
     * Extracts the identifier under ([line], [character]) (1-based line,
     * 0-based character). Returns null when the position is not on an
     * identifier.
     */
    internal fun wordAt(content: String, line: Int, character: Int): String? {
        if (line < 1) return null
        val lines = content.split('\n')
        val text = lines.getOrNull(line - 1) ?: return null
        if (text.isEmpty()) return null
        val pos = character.coerceIn(0, text.length)
        if (pos > 0 && !isIdentifierChar(text[pos - 1]) && (pos >= text.length || !isIdentifierChar(text[pos]))) {
            return null
        }
        var start = pos
        while (start > 0 && isIdentifierChar(text[start - 1])) start--
        var end = pos
        while (end < text.length && isIdentifierChar(text[end])) end++
        return text.substring(start, end).takeIf { it.isNotEmpty() }
    }

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
