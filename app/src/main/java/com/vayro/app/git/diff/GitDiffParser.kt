package com.vayro.app.git.diff

import com.vayro.app.git.model.GitDiffHunk
import com.vayro.app.git.model.GitDiffLine
import com.vayro.app.git.model.GitDiffLineType
import com.vayro.app.git.model.GitFileDiff

/**
 * Parses raw Git unified diff outputs into structured FileDiffs, Hunks, and Lines.
 */
object GitDiffParser {

    private val HUNK_HEADER_REGEX = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@(.*)""")

    fun parse(rawDiff: String): List<GitFileDiff> {
        val fileDiffs = mutableListOf<GitFileDiff>()
        val lines = rawDiff.lines()
        var i = 0

        var currentOldPath: String? = null
        var currentNewPath: String? = null
        var isNewFile = false
        var isDeletedFile = false
        var isBinary = false
        val currentHunks = mutableListOf<GitDiffHunk>()
        var totalAdditions = 0
        var totalDeletions = 0

        fun flushFile() {
            if (currentOldPath != null || currentNewPath != null) {
                fileDiffs += GitFileDiff(
                    oldPath = currentOldPath,
                    newPath = currentNewPath,
                    isBinary = isBinary,
                    isNewFile = isNewFile,
                    isDeletedFile = isDeletedFile,
                    hunks = currentHunks.toList(),
                    additions = totalAdditions,
                    deletions = totalDeletions
                )
            }
            currentOldPath = null
            currentNewPath = null
            isNewFile = false
            isDeletedFile = false
            isBinary = false
            currentHunks.clear()
            totalAdditions = 0
            totalDeletions = 0
        }

        while (i < lines.size) {
            val line = lines[i]

            if (line.startsWith("diff --git ")) {
                flushFile()
                val parts = line.removePrefix("diff --git ").split(" ")
                currentOldPath = parts.getOrNull(0)?.removePrefix("a/")
                currentNewPath = parts.getOrNull(1)?.removePrefix("b/")
                i++
                continue
            }

            if (line.startsWith("new file mode ")) {
                isNewFile = true
                i++
                continue
            }
            if (line.startsWith("deleted file mode ")) {
                isDeletedFile = true
                i++
                continue
            }
            if (line.startsWith("Binary files ") || line.contains("GIT binary patch")) {
                isBinary = true
                i++
                continue
            }
            if (line.startsWith("--- a/")) {
                currentOldPath = line.removePrefix("--- a/").trim()
                i++
                continue
            }
            if (line.startsWith("--- /dev/null")) {
                isNewFile = true
                currentOldPath = null
                i++
                continue
            }
            if (line.startsWith("+++ b/")) {
                currentNewPath = line.removePrefix("+++ b/").trim()
                i++
                continue
            }
            if (line.startsWith("+++ /dev/null")) {
                isDeletedFile = true
                currentNewPath = null
                i++
                continue
            }

            // Hunk header @@ -X,Y +A,B @@
            val hunkMatch = HUNK_HEADER_REGEX.find(line)
            if (hunkMatch != null) {
                val oldStart = hunkMatch.groupValues[1].toInt()
                val oldCount = hunkMatch.groupValues[2].ifBlank { "1" }.toInt()
                val newStart = hunkMatch.groupValues[3].toInt()
                val newCount = hunkMatch.groupValues[4].ifBlank { "1" }.toInt()
                val headerComment = hunkMatch.groupValues[5].trim()

                var currentOldLine = oldStart
                var currentNewLine = newStart
                val hunkLines = mutableListOf<GitDiffLine>()

                i++
                while (i < lines.size && !lines[i].startsWith("@@ ") && !lines[i].startsWith("diff --git ")) {
                    val hunkLine = lines[i]
                    val trimmed = hunkLine.trimStart()
                    when {
                        hunkLine.startsWith("+") || (hunkLine.startsWith(" ") && trimmed.startsWith("+")) -> {
                            totalAdditions++
                            hunkLines += GitDiffLine(
                                type = GitDiffLineType.ADDITION,
                                text = if (hunkLine.startsWith("+")) hunkLine.removePrefix("+") else trimmed.removePrefix("+"),
                                oldLineNumber = null,
                                newLineNumber = currentNewLine++
                            )
                        }
                        hunkLine.startsWith("-") || (hunkLine.startsWith(" ") && trimmed.startsWith("-")) -> {
                            totalDeletions++
                            hunkLines += GitDiffLine(
                                type = GitDiffLineType.DELETION,
                                text = if (hunkLine.startsWith("-")) hunkLine.removePrefix("-") else trimmed.removePrefix("-"),
                                oldLineNumber = currentOldLine++,
                                newLineNumber = null
                            )
                        }
                        hunkLine.startsWith(" ") -> {
                            hunkLines += GitDiffLine(
                                type = GitDiffLineType.CONTEXT,
                                text = hunkLine.removePrefix(" "),
                                oldLineNumber = currentOldLine++,
                                newLineNumber = currentNewLine++
                            )
                        }
                        hunkLine.startsWith("\\ No newline at end of file") -> {
                            // ignore or metadata
                        }
                        else -> {
                            hunkLines += GitDiffLine(
                                type = GitDiffLineType.CONTEXT,
                                text = hunkLine,
                                oldLineNumber = currentOldLine++,
                                newLineNumber = currentNewLine++
                            )
                        }
                    }
                    i++
                }

                currentHunks += GitDiffHunk(
                    header = "@@ -$oldStart,$oldCount +$newStart,$newCount @@ $headerComment",
                    oldStart = oldStart,
                    oldCount = oldCount,
                    newStart = newStart,
                    newCount = newCount,
                    lines = hunkLines
                )
                continue
            }

            i++
        }

        flushFile()
        return fileDiffs
    }
}
