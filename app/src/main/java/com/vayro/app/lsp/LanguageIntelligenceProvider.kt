package com.vayro.app.lsp

/**
 * Milestone 2 (interface) / Milestone 3 (real providers) — language
 * intelligence provider contract.
 *
 * Editor commands (go to definition, find references, rename, diagnostics,
 * completion) must be backed by a [LanguageIntelligenceProvider] rather than
 * duplicating heuristic text search. Milestone 2 ships the interface plus the
 * explicitly-labelled heuristic fallback
 * ([com.vayro.app.editor.HeuristicIntelligenceProvider]); Milestone 3 adds
 * real parser/LSP-backed providers per language.
 *
 * Contract: a language is "supported" by a provider only when its
 * definitions/references/rename/diagnostics behaviour is validated by tests
 * (Milestone 3 gate). Until then the fallback must stay labelled as such.
 */

/** A symbol location returned by a provider. Lines are 1-based. */
data class SymbolLocation(
    val filePath: String,
    val line: Int,
    val character: Int = 0,
    val symbolName: String,
    /** "class", "function", "variable", "parameter", ... */
    val kind: String = "unknown",
)

/** A diagnostic produced by a provider. Lines are 1-based. */
data class IntelligenceDiagnostic(
    val filePath: String,
    val line: Int,
    val message: String,
    /** "error", "warning", "info", "hint" */
    val severity: String = "info",
    val source: String = "",
)

interface LanguageIntelligenceProvider {

    /** Human-readable name, shown in the UI wherever results are labelled. */
    val providerName: String

    /**
     * True for heuristic/fallback providers. The UI must label fallback
     * results and must never present them as compiler-accurate.
     */
    val isFallback: Boolean

    /** Language ids this provider handles, e.g. "kotlin", "java", "typescript", "python". */
    fun supportedLanguages(): Set<String>

    fun supportsLanguage(languageId: String): Boolean =
        languageId.lowercase() in supportedLanguages()

    /**
     * Definitions of the symbol at ([line], [character]) in the given file
     * content. Returns empty when the provider cannot determine a definition
     * (honest empty, never a guess).
     */
    fun definitions(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
    ): List<SymbolLocation>

    /** All known references to [symbolName] in the given file content. */
    fun references(
        filePath: String,
        content: String,
        symbolName: String,
    ): List<SymbolLocation>

    /** Diagnostics for the given file content (honest empty when unsupported). */
    fun diagnostics(
        filePath: String,
        content: String,
    ): List<IntelligenceDiagnostic>

    /** Completion candidates for [prefix] at ([line], [character]). */
    fun completionItems(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
        prefix: String,
    ): List<String>

    /**
     * Definition + reference locations for renaming the symbol at
     * ([line], [character]) to [newName].
     *
     * Returns null when the provider does not support rename (default);
     * returns empty when the symbol cannot be resolved (honest, not a
     * guess). Providers with real rename return every location that must
     * change for a correct rename.
     */
    fun renameLocations(
        filePath: String,
        content: String,
        line: Int,
        character: Int,
        newName: String,
    ): List<SymbolLocation>? = null
}
