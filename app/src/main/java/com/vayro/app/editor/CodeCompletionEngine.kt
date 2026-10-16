package com.vayro.app.editor

import com.vayro.app.runtime.CodeSymbol
import com.vayro.app.runtime.SymbolKind

enum class CompletionItemKind {
    KEYWORD,
    FUNCTION,
    CLASS,
    VARIABLE,
    PROPERTY,
    IMPORT,
    SNIPPET,
}

data class CompletionItem(
    val label: String,
    val insertText: String = label,
    val kind: CompletionItemKind,
    val detail: String? = null,
    val autoImport: String? = null,
)

/**
 * Real-time code completion engine designed for mobile keyboard coding.
 * Provides fuzzy keyword, symbol, snippet, and member suggestions.
 */
object CodeCompletionEngine {

    private val KOTLIN_KEYWORDS = listOf(
        "fun", "val", "var", "class", "interface", "object", "data class", "sealed class",
        "override", "private", "public", "protected", "internal", "if", "else", "when",
        "return", "for", "while", "null", "true", "false", "this", "super", "try", "catch",
        "finally", "throw", "import", "package", "companion object", "suspend", "lateinit",
    )

    private val PYTHON_KEYWORDS = listOf(
        "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while",
        "try", "except", "finally", "raise", "with", "as", "async", "await", "yield",
        "None", "True", "False", "lambda", "pass", "break", "continue", "self",
    )

    private val JS_TS_KEYWORDS = listOf(
        "function", "const", "let", "var", "class", "interface", "type", "export", "import",
        "default", "return", "if", "else", "switch", "case", "for", "while", "try", "catch",
        "finally", "throw", "async", "await", "null", "undefined", "true", "false", "this",
    )

    private val COMMON_SNIPPETS = mapOf(
        "fun" to "fun name() {\n    \n}",
        "class" to "class Name {\n    \n}",
        "if" to "if (condition) {\n    \n}",
        "try" to "try {\n    \n} catch (e: Exception) {\n    \n}",
        "when" to "when (value) {\n    else -> {}\n}",
        "def" to "def name():\n    pass",
    )

    /**
     * Computes completion suggestions given editor text, cursor offset, and project symbols.
     */
    fun getCompletions(
        content: String,
        cursorOffset: Int,
        language: String = "Kotlin",
        projectSymbols: List<CodeSymbol> = emptyList(),
    ): List<CompletionItem> {
        if (content.isEmpty() || cursorOffset <= 0) return defaultKeywords(language).take(8)

        val safeOffset = cursorOffset.coerceIn(0, content.length)
        val textBefore = content.substring(0, safeOffset)

        // Find the current token / word prefix
        val prefix = textBefore.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        val isDotAccess = textBefore.endsWith('.') || (textBefore.dropLast(prefix.length).endsWith('.'))

        val results = mutableListOf<CompletionItem>()

        if (isDotAccess) {
            // Suggest methods, properties, and functions matching prefix
            projectSymbols.filter {
                it.kind == SymbolKind.METHOD || it.kind == SymbolKind.FUNCTION || it.kind == SymbolKind.PROPERTY
            }.filter {
                prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true)
            }.forEach {
                results.add(
                    CompletionItem(
                        label = it.name,
                        insertText = if (it.kind == SymbolKind.METHOD || it.kind == SymbolKind.FUNCTION) "${it.name}()" else it.name,
                        kind = if (it.kind == SymbolKind.PROPERTY) CompletionItemKind.PROPERTY else CompletionItemKind.FUNCTION,
                        detail = it.signature ?: it.kind.name.lowercase(),
                    )
                )
            }
            return results.distinctBy { it.label }.take(10)
        }

        if (prefix.isBlank()) {
            return defaultKeywords(language).take(8)
        }

        // 1. Keyword matches
        val keywords = when (language.lowercase()) {
            "python" -> PYTHON_KEYWORDS
            "javascript", "typescript" -> JS_TS_KEYWORDS
            else -> KOTLIN_KEYWORDS
        }

        keywords.filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }
            .forEach { kw ->
                results.add(
                    CompletionItem(
                        label = kw,
                        insertText = kw,
                        kind = CompletionItemKind.KEYWORD,
                        detail = "keyword",
                    )
                )
            }

        // 2. Snippet matches
        COMMON_SNIPPETS.entries.firstOrNull { it.key.equals(prefix, ignoreCase = true) }?.let { (_, template) ->
            results.add(
                0,
                CompletionItem(
                    label = "$prefix (snippet)",
                    insertText = template,
                    kind = CompletionItemKind.SNIPPET,
                    detail = "Code snippet",
                )
            )
        }

        // 3. Project symbols matches (classes, functions, properties)
        projectSymbols.filter {
            it.name.startsWith(prefix, ignoreCase = true) && !it.name.equals(prefix, ignoreCase = true)
        }.forEach { sym ->
            val kind = when (sym.kind) {
                SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.STRUCT -> CompletionItemKind.CLASS
                SymbolKind.FUNCTION, SymbolKind.METHOD -> CompletionItemKind.FUNCTION
                SymbolKind.PROPERTY, SymbolKind.VARIABLE -> CompletionItemKind.VARIABLE
                else -> CompletionItemKind.VARIABLE
            }
            results.add(
                CompletionItem(
                    label = sym.name,
                    insertText = if (sym.kind == SymbolKind.FUNCTION || sym.kind == SymbolKind.METHOD) "${sym.name}()" else sym.name,
                    kind = kind,
                    detail = sym.signature ?: sym.kind.name.lowercase(),
                )
            )
        }

        return results.distinctBy { it.label }.take(12)
    }

    private fun defaultKeywords(language: String): List<CompletionItem> {
        val list = when (language.lowercase()) {
            "python" -> listOf("def", "class", "import", "return", "if", "for")
            "javascript", "typescript" -> listOf("function", "const", "let", "return", "export", "import")
            else -> listOf("fun", "val", "var", "class", "override", "return")
        }
        return list.map { CompletionItem(it, it, CompletionItemKind.KEYWORD, "keyword") }
    }
}
