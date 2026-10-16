package com.vayro.app.lsp.parser

/**
 * Milestone 3 — dispatches files to the real structural parser for their
 * language. Returns null for languages without a real parser (the caller
 * falls back to the labelled heuristic provider).
 */
object SourceParser {

    /** Language ids with a real structural parser. */
    val SUPPORTED_LANGUAGES: Set<String> = setOf(
        "kotlin", "java", "typescript", "javascript", "python",
    )

    fun supportsLanguage(languageId: String): Boolean =
        languageId.lowercase() in SUPPORTED_LANGUAGES

    fun languageIdForPath(filePath: String): String =
        when (filePath.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "ts", "tsx", "mts", "cts" -> "typescript"
            "js", "jsx", "mjs", "cjs" -> "javascript"
            "py", "pyw" -> "python"
            else -> "unknown"
        }

    /**
     * Structurally parses [content]. Never throws: on catastrophic failure
     * returns a ParsedFile with an error diagnostic (honest, not silent).
     */
    fun parse(filePath: String, content: String, languageId: String? = null): ParsedFile? {
        val lang = (languageId ?: languageIdForPath(filePath)).lowercase()
        if (lang !in SUPPORTED_LANGUAGES) return null
        return runCatching {
            when (lang) {
                "kotlin" -> KotlinParser(filePath, content).parse()
                "java" -> JavaParser(filePath, content).parse()
                "typescript", "javascript" ->
                    TypeScriptParser(filePath, content, languageId = lang).parse()
                "python" -> PythonParser(filePath, content).parse()
                else -> null
            }
        }.getOrElse { e ->
            ParsedFile(
                filePath = filePath,
                languageId = lang,
                symbols = emptyList(),
                imports = emptyList(),
                diagnostics = listOf(
                    ParseDiagnostic(
                        line = 1,
                        character = 0,
                        message = "Parser failed: ${e.message}",
                        severity = "error",
                    )
                ),
            )
        }
    }
}
