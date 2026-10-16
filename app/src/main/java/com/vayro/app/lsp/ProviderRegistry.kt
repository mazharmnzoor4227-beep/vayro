package com.vayro.app.lsp

import com.vayro.app.editor.HeuristicIntelligenceProvider

/**
 * Milestone 3 — selects the best intelligence provider for a language.
 *
 * Priority (honest, capability-based):
 * 1. A REAL running language server for the language
 *    ([LspManager] reports `isRunning && isRealServer`) — protocol-accurate.
 * 2. The real structural parser provider for Kotlin/Java, TypeScript/
 *    JavaScript or Python — scope-aware, not compiler-accurate.
 * 3. The explicitly-labelled heuristic regex fallback — never presented as
 *    accurate.
 *
 * Every provider carries its [LanguageIntelligenceProvider.providerName];
 * the UI must surface it so users always know what produced a result.
 */
class ProviderRegistry(
    private val lspManager: LspManager? = null,
    private val projectIndex: ParserProjectIndex = ParserProjectIndex(),
    private val heuristicFallback: LanguageIntelligenceProvider = HeuristicIntelligenceProvider(),
) {
    private val parserProviders: Map<String, LanguageIntelligenceProvider> by lazy {
        val kotlin = ParserBackedIntelligenceProvider.kotlinProvider(projectIndex)
        val ts = ParserBackedIntelligenceProvider.typeScriptProvider(projectIndex)
        val python = ParserBackedIntelligenceProvider.pythonProvider(projectIndex)
        mapOf(
            "kotlin" to kotlin,
            "java" to kotlin,
            "typescript" to ts,
            "javascript" to ts,
            "python" to python,
        )
    }

    /**
     * Best provider for [languageId] (e.g. "kotlin", "python").
     * Never null: the heuristic fallback always qualifies.
     */
    fun providerFor(languageId: String): LanguageIntelligenceProvider {
        val lang = languageId.lowercase()
        // 1. Real server running?
        val manager = lspManager
        if (manager != null) {
            val status = manager.getServerStatus(displayNameFor(lang))
            if (status.isRunning && status.isRealServer && manager.getProtocolClient(displayNameFor(lang)) != null) {
                return LspServerBackedProvider(displayNameFor(lang), manager)
            }
        }
        // 2. Real parser provider?
        parserProviders[lang]?.let { return it }
        // 3. Labelled fallback.
        return heuristicFallback
    }

    /** Short human-readable description of which tier serves a language. */
    fun describeTier(languageId: String): String {
        val provider = providerFor(languageId)
        return when {
            provider is LspServerBackedProvider -> "real language server"
            !provider.isFallback -> "built-in parser (not compiler-accurate)"
            else -> "heuristic fallback (not compiler-accurate)"
        }
    }

    fun projectIndex(): ParserProjectIndex = projectIndex

    private fun displayNameFor(languageId: String): String = when (languageId) {
        "kotlin" -> "Kotlin"
        "java" -> "Java"
        "typescript" -> "TypeScript"
        "javascript" -> "JavaScript"
        "python" -> "Python"
        else -> languageId.replaceFirstChar { it.uppercase() }
    }
}
