package com.vayro.app.editor

enum class FoldType {
    BRACES,
    INDENTATION,
    IMPORT_BLOCK,
    COMMENT_BLOCK,
}

data class FoldableRange(
    val startLine: Int, // 1-indexed
    val endLine: Int,   // 1-indexed
    val type: FoldType,
    val previewText: String = "...",
)

data class FoldedLineDisplay(
    val originalLineNumber: Int,
    val text: String,
    val isFoldedHeader: Boolean = false,
    val isFoldableStart: Boolean = false,
    val matchingRange: FoldableRange? = null,
)

/**
 * Engine responsible for detecting collapsible code regions and computing
 * folded text transformations for the mobile code editor.
 */
object CodeFoldingEngine {

    /**
     * Finds all collapsible code ranges in the provided document content.
     */
    fun findFoldableRanges(text: String, language: String = "Kotlin"): List<FoldableRange> {
        val lines = text.lines()
        if (lines.size <= 2) return emptyList()

        val ranges = mutableListOf<FoldableRange>()

        // 1. Multi-line comment folding (/* ... */ or """ ... """)
        ranges.addAll(findCommentBlockFolds(lines))

        // 2. Import block folding (3 or more consecutive import lines)
        ranges.addAll(findImportBlockFolds(lines))

        // 3. Language-specific folding
        if (language.equals("Python", ignoreCase = true) || language.equals("YAML", ignoreCase = true)) {
            ranges.addAll(findIndentationFolds(lines))
        } else {
            ranges.addAll(findBraceFolds(lines))
        }

        return ranges.distinctBy { "${it.startLine}:${it.endLine}" }.sortedBy { it.startLine }
    }

    private fun findBraceFolds(lines: List<String>): List<FoldableRange> {
        val folds = mutableListOf<FoldableRange>()
        val stack = ArrayDeque<Int>() // stores 1-indexed line numbers where '{' opened

        for (i in lines.indices) {
            val lineNum = i + 1
            val line = lines[i]

            // Simple scanner ignoring characters inside single-line strings
            var inString = false
            var escape = false

            for (ch in line) {
                if (escape) {
                    escape = false
                    continue
                }
                if (ch == '\\') {
                    escape = true
                    continue
                }
                if (ch == '"' || ch == '\'') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (ch == '{') {
                        stack.addLast(lineNum)
                    } else if (ch == '}') {
                        if (stack.isNotEmpty()) {
                            val startLine = stack.removeLast()
                            if (lineNum > startLine) {
                                folds.add(
                                    FoldableRange(
                                        startLine = startLine,
                                        endLine = lineNum,
                                        type = FoldType.BRACES,
                                        previewText = "{ ... }",
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return folds
    }

    private fun findIndentationFolds(lines: List<String>): List<FoldableRange> {
        val folds = mutableListOf<FoldableRange>()
        val indentStack = ArrayDeque<Pair<Int, Int>>() // (lineNum, indentLevel)

        for (i in lines.indices) {
            val lineNum = i + 1
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue

            val indent = line.takeWhile { it == ' ' || it == '\t' }.length

            while (indentStack.isNotEmpty() && indent <= indentStack.last().second) {
                val (startLine, _) = indentStack.removeLast()
                val prevContentLine = (i downTo 1).firstOrNull { lines[it - 1].isNotBlank() } ?: (lineNum - 1)
                if (prevContentLine > startLine) {
                    folds.add(
                        FoldableRange(
                            startLine = startLine,
                            endLine = prevContentLine,
                            type = FoldType.INDENTATION,
                            previewText = ": ...",
                        )
                    )
                }
            }

            if (line.trimEnd().endsWith(":")) {
                indentStack.addLast(lineNum to indent)
            }
        }

        while (indentStack.isNotEmpty()) {
            val (startLine, _) = indentStack.removeLast()
            val lastLine = lines.size
            if (lastLine > startLine) {
                folds.add(
                    FoldableRange(
                        startLine = startLine,
                        endLine = lastLine,
                        type = FoldType.INDENTATION,
                        previewText = ": ...",
                    )
                )
            }
        }

        return folds
    }

    private fun findImportBlockFolds(lines: List<String>): List<FoldableRange> {
        val folds = mutableListOf<FoldableRange>()
        var importStart: Int? = null
        var lastImportLine: Int? = null

        for (i in lines.indices) {
            val lineNum = i + 1
            val trimmed = lines[i].trim()

            if (trimmed.startsWith("import ") || (trimmed.startsWith("from ") && trimmed.contains(" import "))) {
                if (importStart == null) importStart = lineNum
                lastImportLine = lineNum
            } else if (trimmed.isNotBlank()) {
                if (importStart != null && lastImportLine != null && (lastImportLine - importStart >= 2)) {
                    folds.add(
                        FoldableRange(
                            startLine = importStart,
                            endLine = lastImportLine,
                            type = FoldType.IMPORT_BLOCK,
                            previewText = "imports ...",
                        )
                    )
                }
                importStart = null
                lastImportLine = null
            }
        }

        if (importStart != null && lastImportLine != null && (lastImportLine - importStart >= 2)) {
            folds.add(
                FoldableRange(
                    startLine = importStart,
                    endLine = lastImportLine,
                    type = FoldType.IMPORT_BLOCK,
                    previewText = "imports ...",
                )
            )
        }

        return folds
    }

    private fun findCommentBlockFolds(lines: List<String>): List<FoldableRange> {
        val folds = mutableListOf<FoldableRange>()
        var commentStart: Int? = null

        for (i in lines.indices) {
            val lineNum = i + 1
            val line = lines[i]

            if (commentStart == null) {
                if (line.contains("/*") && !line.contains("*/")) {
                    commentStart = lineNum
                } else if (line.trim().startsWith("\"\"\"") && line.trim().count { it == '"' } <= 3) {
                    commentStart = lineNum
                }
            } else {
                if (line.contains("*/") || (line.contains("\"\"\""))) {
                    folds.add(
                        FoldableRange(
                            startLine = commentStart,
                            endLine = lineNum,
                            type = FoldType.COMMENT_BLOCK,
                            previewText = "/* ... */",
                        )
                    )
                    commentStart = null
                }
            }
        }
        return folds
    }

    /**
     * Determines whether a specific line falls within any currently active (collapsed) folded range.
     * Returns true if line is collapsed and should be hidden.
     */
    fun isLineHiddenByFold(lineNumber: Int, activeFolds: Set<FoldableRange>): Boolean {
        return activeFolds.any { range -> lineNumber > range.startLine && lineNumber <= range.endLine }
    }
}
