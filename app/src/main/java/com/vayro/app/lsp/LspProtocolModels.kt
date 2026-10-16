package com.vayro.app.lsp

data class LspPosition(
    val line: Int,       // 0-indexed
    val character: Int,  // 0-indexed
)

data class LspRange(
    val start: LspPosition,
    val end: LspPosition,
)

data class LspLocation(
    val uri: String,
    val range: LspRange,
)

enum class LspDiagnosticSeverity(val value: Int) {
    ERROR(1),
    WARNING(2),
    INFORMATION(3),
    HINT(4),
}

data class LspDiagnostic(
    val range: LspRange,
    val message: String,
    val severity: LspDiagnosticSeverity = LspDiagnosticSeverity.ERROR,
    val code: String? = null,
    val source: String? = "MobileHarness",
)

data class LspHover(
    val contents: String,
    val range: LspRange? = null,
)

enum class LspCompletionItemKind(val value: Int) {
    TEXT(1),
    METHOD(2),
    FUNCTION(3),
    CONSTRUCTOR(4),
    FIELD(5),
    VARIABLE(6),
    CLASS(7),
    INTERFACE(8),
    MODULE(9),
    PROPERTY(10),
    KEYWORD(14),
    SNIPPET(15),
}

data class LspCompletionItem(
    val label: String,
    val kind: LspCompletionItemKind = LspCompletionItemKind.TEXT,
    val detail: String? = null,
    val documentation: String? = null,
    val insertText: String? = null,
)

data class LspCodeAction(
    val title: String,
    val kind: String = "quickfix",
    val isPreferred: Boolean = false,
    val newText: String? = null,
    val targetFile: String? = null,
)

data class LspServerStatus(
    val language: String,
    /**
     * True only when a REAL language-server process is running. Never true when
     * only the built-in fallback is active (Milestone 0).
     */
    val isRunning: Boolean,
    /** True only when [isRunning] refers to a genuine external server process. */
    val isRealServer: Boolean = false,
    val isBuiltInFallback: Boolean = false,
    val pid: Long? = null,
    val lastError: String? = null,
)
