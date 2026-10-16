package com.vayro.app.lsp.parser

/**
 * Milestone 3 — models for the real parser-based language intelligence.
 *
 * Unlike the regex-based [com.vayro.app.runtime.SemanticCodeEngine], parsers
 * in this package perform genuine tokenization (strings/comments cannot
 * confuse them) and structural scope tracking. They are real implementations,
 * but they are NOT compiler-accurate: there is no type resolution, no
 * overload resolution, and no cross-module compilation. Full
 * compiler-accurate intelligence comes from real LSP servers when installed
 * (see [com.vayro.app.lsp.LspProtocolClient]).
 */

/** Kinds of symbols a real parser can structurally identify. */
enum class ParsedSymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    OBJECT,
    FUNCTION,
    METHOD,
    CONSTRUCTOR,
    PROPERTY,
    VARIABLE,
    PARAMETER,
    TYPE_ALIAS,
}

/** A single structurally-parsed symbol declaration. Lines are 1-based, characters 0-based. */
data class ParsedSymbol(
    val name: String,
    val kind: ParsedSymbolKind,
    val line: Int,
    val character: Int,
    /** Name of the immediately enclosing class/function, null at file scope. */
    val containerName: String? = null,
    /** Nesting depth: 0 = file scope, 1 = inside one class/function, ... */
    val depth: Int = 0,
    val signature: String? = null,
    val superTypes: List<String> = emptyList(),
    val docComment: String? = null,
    val parameters: List<String> = emptyList(),
    /** True when declared with an explicit visibility or export modifier. */
    val isPublic: Boolean = true,
)

/** A structurally-parsed import statement. */
data class ParsedImport(
    /** Raw import text, e.g. "com.example.Foo" or "./utils". */
    val rawText: String,
    /** Simple names brought into scope, e.g. ["Foo"]; ["*"] for star imports. */
    val importedNames: List<String>,
    val line: Int,
    val isStar: Boolean = false,
)

/** A genuine diagnostic produced by a real parser (never guessed). */
data class ParseDiagnostic(
    val line: Int,
    val character: Int,
    val message: String,
    /** "error" or "warning". */
    val severity: String,
)

/** The complete structural parse result for one file. */
data class ParsedFile(
    val filePath: String,
    val languageId: String,
    val symbols: List<ParsedSymbol>,
    val imports: List<ParsedImport>,
    val diagnostics: List<ParseDiagnostic>,
    val packageName: String? = null,
    /** Symbol names called/invoked in this file (for reference search). */
    val callSites: List<CallSite> = emptyList(),
)

/** A call/invocation site: `name(` occurring at a position. */
data class CallSite(
    val name: String,
    val line: Int,
    val character: Int,
)
