package com.vayro.app.editor

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}

data class EditorDiagnostic(
    val lineNumber: Int, // 1-indexed
    val column: Int = 1,
    val message: String,
    val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val ruleId: String,
)

/**
 * Real-time syntax and structural code diagnostics engine for the mobile IDE editor.
 * Detects bracket mismatches, unclosed quotes, duplicate imports, and syntax anomalies.
 */
object EditorDiagnosticsEngine {

    fun analyzeDocument(text: String, filePath: String): List<EditorDiagnostic> {
        val diagnostics = mutableListOf<EditorDiagnostic>()
        val lines = text.lines()
        val extension = filePath.substringAfterLast('.', "").lowercase()

        // 1. Bracket & Parenthesis Balance Check
        diagnostics.addAll(checkBracketBalance(lines))

        // 2. Unclosed String Literals Check
        diagnostics.addAll(checkStringLiterals(lines, extension))

        // 3. Duplicate Imports Check
        diagnostics.addAll(checkDuplicateImports(lines))

        // 4. Common Syntax Gotchas (e.g. empty catch blocks, assignment in condition)
        diagnostics.addAll(checkCommonCodeSmells(lines))

        return diagnostics.sortedWith(compareBy({ it.lineNumber }, { it.severity }))
    }

    private fun checkBracketBalance(lines: List<String>): List<EditorDiagnostic> {
        val list = mutableListOf<EditorDiagnostic>()
        val stack = ArrayDeque<Triple<Char, Int, Int>>() // char, lineNum, col

        for (i in lines.indices) {
            val lineNum = i + 1
            val line = lines[i]

            var inSingleString = false
            var inDoubleString = false
            var escape = false

            for (col in line.indices) {
                val ch = line[col]
                if (escape) {
                    escape = false
                    continue
                }
                if (ch == '\\') {
                    escape = true
                    continue
                }
                if (ch == '\'' && !inDoubleString) {
                    inSingleString = !inSingleString
                    continue
                }
                if (ch == '"' && !inSingleString) {
                    inDoubleString = !inDoubleString
                    continue
                }
                if (inSingleString || inDoubleString) continue

                when (ch) {
                    '{', '(', '[' -> stack.addLast(Triple(ch, lineNum, col + 1))
                    '}' -> {
                        if (stack.isEmpty() || stack.last().first != '{') {
                            list.add(
                                EditorDiagnostic(
                                    lineNumber = lineNum,
                                    column = col + 1,
                                    message = "Unmatched closing brace '}'",
                                    severity = DiagnosticSeverity.ERROR,
                                    ruleId = "syntax.unmatched_brace",
                                )
                            )
                        } else {
                            stack.removeLast()
                        }
                    }
                    ')' -> {
                        if (stack.isEmpty() || stack.last().first != '(') {
                            list.add(
                                EditorDiagnostic(
                                    lineNumber = lineNum,
                                    column = col + 1,
                                    message = "Unmatched closing parenthesis ')'",
                                    severity = DiagnosticSeverity.ERROR,
                                    ruleId = "syntax.unmatched_parenthesis",
                                )
                            )
                        } else {
                            stack.removeLast()
                        }
                    }
                    ']' -> {
                        if (stack.isEmpty() || stack.last().first != '[') {
                            list.add(
                                EditorDiagnostic(
                                    lineNumber = lineNum,
                                    column = col + 1,
                                    message = "Unmatched closing bracket ']'",
                                    severity = DiagnosticSeverity.ERROR,
                                    ruleId = "syntax.unmatched_bracket",
                                )
                            )
                        } else {
                            stack.removeLast()
                        }
                    }
                }
            }
        }

        // Report any unclosed openings remaining on the stack
        while (stack.isNotEmpty()) {
            val (openChar, lineNum, col) = stack.removeLast()
            val expectedClose = when (openChar) {
                '{' -> "'}'"
                '(' -> "')'"
                '[' -> "']'"
                else -> "closing token"
            }
            list.add(
                EditorDiagnostic(
                    lineNumber = lineNum,
                    column = col,
                    message = "Unclosed '$openChar', expected matching $expectedClose",
                    severity = DiagnosticSeverity.ERROR,
                    ruleId = "syntax.unclosed_delimiter",
                )
            )
        }

        return list
    }

    private fun checkStringLiterals(lines: List<String>, extension: String): List<EditorDiagnostic> {
        val list = mutableListOf<EditorDiagnostic>()
        var inMultiLineString = false
        var multiLineStartLine = 1

        for (i in lines.indices) {
            val lineNum = i + 1
            val line = lines[i]

            // Triple-quote multi-line strings in Kotlin/Python
            val tripleCount = line.windowed(3, 1).count { it == "\"\"\"" }
            if (tripleCount % 2 != 0) {
                if (inMultiLineString) {
                    inMultiLineString = false
                } else {
                    inMultiLineString = true
                    multiLineStartLine = lineNum
                }
                continue
            }
            if (inMultiLineString) continue

            // Check single line unclosed double quotes
            val quotes = line.count { it == '"' } - line.windowed(2, 1).count { it == "\\\"" }
            if (quotes % 2 != 0 && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("#")) {
                list.add(
                    EditorDiagnostic(
                        lineNumber = lineNum,
                        column = line.lastIndexOf('"') + 1,
                        message = "Unclosed string literal",
                        severity = DiagnosticSeverity.WARNING,
                        ruleId = "syntax.unclosed_string",
                    )
                )
            }
        }

        if (inMultiLineString) {
            list.add(
                EditorDiagnostic(
                    lineNumber = multiLineStartLine,
                    message = "Unclosed multi-line string literal (\"\"\")",
                    severity = DiagnosticSeverity.ERROR,
                    ruleId = "syntax.unclosed_multiline_string",
                )
            )
        }

        return list
    }

    private fun checkDuplicateImports(lines: List<String>): List<EditorDiagnostic> {
        val list = mutableListOf<EditorDiagnostic>()
        val seenImports = mutableSetOf<String>()

        for (i in lines.indices) {
            val lineNum = i + 1
            val line = lines[i].trim()
            if (line.startsWith("import ") || (line.startsWith("from ") && line.contains(" import "))) {
                if (!seenImports.add(line)) {
                    list.add(
                        EditorDiagnostic(
                            lineNumber = lineNum,
                            message = "Duplicate import statement: '$line'",
                            severity = DiagnosticSeverity.WARNING,
                            ruleId = "linter.duplicate_import",
                        )
                    )
                }
            }
        }
        return list
    }

    private fun checkCommonCodeSmells(lines: List<String>): List<EditorDiagnostic> {
        val list = mutableListOf<EditorDiagnostic>()
        for (i in lines.indices) {
            val lineNum = i + 1
            val line = lines[i].trim()
            if (line.contains(Regex("""catch\s*\([^)]*\)\s*\{\s*\}"""))) {
                list.add(
                    EditorDiagnostic(
                        lineNumber = lineNum,
                        message = "Empty catch block suppresses exceptions silently",
                        severity = DiagnosticSeverity.WARNING,
                        ruleId = "linter.empty_catch",
                    )
                )
            }
            if (line.contains("TODO()") || line.contains("TODO(\"") || line.contains("// TODO")) {
                list.add(
                    EditorDiagnostic(
                        lineNumber = lineNum,
                        message = "Unimplemented TODO item",
                        severity = DiagnosticSeverity.INFO,
                        ruleId = "linter.todo",
                    )
                )
            }
        }
        return list
    }
}
