package com.vayro.app.workspace

import java.io.File
import java.util.regex.Pattern

data class SearchMatch(
    val filePath: String,
    val lineNumber: Int,
    val lineContent: String,
    val matchStart: Int,
    val matchEnd: Int,
    val matchText: String,
)

data class SearchResult(
    val query: String,
    val matches: List<SearchMatch>,
    val totalFilesSearched: Int,
    val totalMatchesCount: Int,
    val isTruncated: Boolean = false,
)

/**
 * Milestone 2 — one changed line shown in a replace preview.
 */
data class ReplacePreviewLine(
    val lineNumber: Int,
    val before: String,
    val after: String,
)

/**
 * Milestone 2 — the planned replacement for a single file.
 *
 * [originalContent] is retained for undo; it is empty when
 * [ReplacePreview.undoAvailable] is false.
 */
data class FileReplacePlan(
    val filePath: String,
    val matchCount: Int,
    val previewLines: List<ReplacePreviewLine>,
    val originalContent: String,
    val newContent: String,
)

/**
 * Milestone 2 — a replace preview: what WOULD change, computed without
 * touching any file.
 */
data class ReplacePreview(
    val query: String,
    val replacement: String,
    val filePlans: List<FileReplacePlan>,
    val totalMatches: Int,
    /** False when originals were too large to retain — undo unavailable. */
    val undoAvailable: Boolean = true,
    /** Optional human-readable note (e.g. validation warnings). */
    val note: String? = null,
) {
    val totalFiles: Int get() = filePlans.size
}

/**
 * Milestone 2 — record of an applied replace, usable for undo via
 * [GlobalSearchEngine.revertReplace].
 */
data class ReplaceTransaction(
    val preview: ReplacePreview,
    val appliedFiles: List<String>,
    val filesChanged: Int,
    val matchesReplaced: Int,
    val appliedAt: Long = System.currentTimeMillis(),
)

/**
 * High-performance project-wide search and replace engine.
 */
object GlobalSearchEngine {

    private const val MAX_MATCHES_LIMIT = 500
    private const val MAX_FILE_SIZE_BYTES = 2L * 1024L * 1024L // 2 MB per file
    private const val MAX_PREVIEW_LINES = 60 // changed lines shown per file in a preview
    private const val UNDO_CONTENT_CAP_BYTES = 10L * 1024L * 1024L // 10 MB originals retained for undo

    private val IGNORED_DIRECTORIES = setOf(
        ".git", ".gradle", ".idea", "build", "node_modules", "target", "dist", ".agents",
    )

    fun search(
        workspaceDir: File,
        query: String,
        isRegex: Boolean = false,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
        fileFilter: String? = null,
    ): SearchResult {
        if (query.isEmpty() || !workspaceDir.isDirectory) {
            return SearchResult(query, emptyList(), 0, 0)
        }

        val pattern = buildRegexPattern(query, isRegex, matchCase, wholeWord) ?: return SearchResult(query, emptyList(), 0, 0)
        val filterRegex = fileFilter?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }

        val canonicalRoot = workspaceDir.canonicalFile
        val matches = mutableListOf<SearchMatch>()
        var filesSearched = 0
        var isTruncated = false

        canonicalRoot.walkTopDown()
            .onEnter { dir ->
                dir == canonicalRoot || dir.name !in IGNORED_DIRECTORIES
            }
            .filter { file ->
                file.isFile && file.length() <= MAX_FILE_SIZE_BYTES &&
                    (filterRegex == null || filterRegex.matches(file.name))
            }
            .forEach { file ->
                if (matches.size >= MAX_MATCHES_LIMIT) {
                    isTruncated = true
                    return@forEach
                }

                filesSearched++
                val relPath = file.relativeTo(canonicalRoot).invariantSeparatorsPath

                runCatching {
                    file.useLines { lines ->
                        var lineNum = 1
                        for (line in lines) {
                            if (matches.size >= MAX_MATCHES_LIMIT) {
                                isTruncated = true
                                break
                            }
                            val matcher = pattern.matcher(line)
                            while (matcher.find()) {
                                matches.add(
                                    SearchMatch(
                                        filePath = relPath,
                                        lineNumber = lineNum,
                                        lineContent = line.trim(),
                                        matchStart = matcher.start(),
                                        matchEnd = matcher.end(),
                                        matchText = matcher.group(),
                                    )
                                )
                                if (matches.size >= MAX_MATCHES_LIMIT) {
                                    isTruncated = true
                                    break
                                }
                            }
                            lineNum++
                        }
                    }
                }
            }

        return SearchResult(
            query = query,
            matches = matches,
            totalFilesSearched = filesSearched,
            totalMatchesCount = matches.size,
            isTruncated = isTruncated,
        )
    }

    /**
     * Replaces occurrences of [query] in a specific file.
     */
    fun replaceInFile(
        file: File,
        query: String,
        replacement: String,
        isRegex: Boolean = false,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
    ): Int {
        if (!file.isFile || query.isEmpty()) return 0
        val pattern = buildRegexPattern(query, isRegex, matchCase, wholeWord) ?: return 0
        val original = file.readText(Charsets.UTF_8)
        val matcher = pattern.matcher(original)
        var count = 0
        val sb = StringBuffer()
        while (matcher.find()) {
            count++
            matcher.appendReplacement(sb, MatcherQuoteReplacement(replacement))
        }
        matcher.appendTail(sb)

        if (count > 0) {
            SafeFileOps.atomicWriteText(file, sb.toString())
        }
        return count
    }

    /**
     * Replaces all occurrences across all matched files in workspace.
     */
    fun replaceAll(
        workspaceDir: File,
        query: String,
        replacement: String,
        isRegex: Boolean = false,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
        fileFilter: String? = null,
    ): Int {
        val search = search(workspaceDir, query, isRegex, matchCase, wholeWord, fileFilter)
        val uniqueFiles = search.matches.map { File(workspaceDir, it.filePath) }.distinct()
        var totalReplaced = 0
        for (file in uniqueFiles) {
            totalReplaced += replaceInFile(file, query, replacement, isRegex, matchCase, wholeWord)
        }
        return totalReplaced
    }

    /**
     * Milestone 2 — builds a replace preview WITHOUT touching any file.
     *
     * Returns per-file plans with before/after line previews and captures the
     * original content of every affected file so the change can be undone via
     * [revertReplace]. When the combined original content exceeds
     * [UNDO_CONTENT_CAP_BYTES], originals are not retained and
     * [ReplacePreview.undoAvailable] is false (the preview itself is still
     * complete and the replace can still be applied).
     */
    fun previewReplace(
        workspaceDir: File,
        query: String,
        replacement: String,
        isRegex: Boolean = false,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
        fileFilter: String? = null,
        note: String? = null,
    ): ReplacePreview {
        if (query.isEmpty() || !workspaceDir.isDirectory) {
            return ReplacePreview(query, replacement, emptyList(), 0, undoAvailable = true, note = note)
        }
        val pattern = buildRegexPattern(query, isRegex, matchCase, wholeWord)
            ?: return ReplacePreview(query, replacement, emptyList(), 0, undoAvailable = true, note = note)
        val search = search(workspaceDir, query, isRegex, matchCase, wholeWord, fileFilter)
        val matchesByFile = search.matches.groupBy { it.filePath }
        val plans = mutableListOf<FileReplacePlan>()
        var totalBytes = 0L
        var undoAvailable = true
        for ((filePath, _) in matchesByFile) {
            val file = File(workspaceDir, filePath)
            val original = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val matcher = pattern.matcher(original)
            var count = 0
            val sb = StringBuffer()
            while (matcher.find()) {
                count++
                matcher.appendReplacement(sb, MatcherQuoteReplacement(replacement))
            }
            matcher.appendTail(sb)
            if (count == 0) continue
            val newContent = sb.toString()
            val previewLines = buildPreviewLines(original, newContent)
            totalBytes += original.toByteArray(Charsets.UTF_8).size
            val retainOriginal = if (totalBytes > UNDO_CONTENT_CAP_BYTES) {
                undoAvailable = false
                ""
            } else {
                original
            }
            plans.add(
                FileReplacePlan(
                    filePath = filePath,
                    matchCount = count,
                    previewLines = previewLines,
                    originalContent = retainOriginal,
                    newContent = newContent,
                ),
            )
        }
        // If undo was disabled part-way, drop retained originals for consistency:
        // undo is all-or-nothing.
        val finalPlans = if (!undoAvailable) {
            plans.map { it.copy(originalContent = "") }
        } else {
            plans
        }
        return ReplacePreview(
            query = query,
            replacement = replacement,
            filePlans = finalPlans.sortedBy { it.filePath },
            totalMatches = finalPlans.sumOf { it.matchCount },
            undoAvailable = undoAvailable,
            note = note,
        )
    }

    /**
     * Milestone 2 — applies a [ReplacePreview], optionally limited to
     * [includeFiles] (per-file toggles from the preview UI; null = all files).
     * Writes are atomic per file. Returns a [ReplaceTransaction] that can be
     * passed to [revertReplace] for undo.
     */
    fun applyReplacePlan(
        workspaceDir: File,
        preview: ReplacePreview,
        includeFiles: Set<String>? = null,
    ): ReplaceTransaction {
        var filesChanged = 0
        var matchesReplaced = 0
        for (plan in preview.filePlans) {
            if (includeFiles != null && plan.filePath !in includeFiles) continue
            val file = File(workspaceDir, plan.filePath)
            if (SafeFileOps.atomicWriteText(file, plan.newContent)) {
                filesChanged++
                matchesReplaced += plan.matchCount
            }
        }
        return ReplaceTransaction(
            preview = preview,
            appliedFiles = preview.filePlans
                .filter { includeFiles == null || it.filePath in includeFiles }
                .map { it.filePath },
            filesChanged = filesChanged,
            matchesReplaced = matchesReplaced,
        )
    }

    /**
     * Milestone 2 — undoes a [ReplaceTransaction] by restoring the original
     * content captured in the preview. Returns the number of files restored.
     * Returns 0 (and changes nothing) when the preview was created with
     * `undoAvailable = false`.
     */
    fun revertReplace(workspaceDir: File, transaction: ReplaceTransaction): Int {
        val preview = transaction.preview
        if (!preview.undoAvailable) return 0
        var restored = 0
        for (plan in preview.filePlans) {
            if (plan.filePath !in transaction.appliedFiles) continue
            val file = File(workspaceDir, plan.filePath)
            if (SafeFileOps.atomicWriteText(file, plan.originalContent)) {
                restored++
            }
        }
        return restored
    }

    private fun buildPreviewLines(original: String, newContent: String): List<ReplacePreviewLine> {
        val beforeLines = original.split('\n')
        val afterLines = newContent.split('\n')
        val preview = mutableListOf<ReplacePreviewLine>()
        // Line-oriented diff: pair up lines; record changed ones with context.
        val maxLines = maxOf(beforeLines.size, afterLines.size)
        var i = 0
        while (i < maxLines && preview.size < MAX_PREVIEW_LINES) {
            val before = beforeLines.getOrNull(i).orEmpty()
            val after = afterLines.getOrNull(i).orEmpty()
            if (before != after) {
                preview.add(ReplacePreviewLine(lineNumber = i + 1, before = before, after = after))
            }
            i++
        }
        return preview
    }

    private fun buildRegexPattern(
        query: String,
        isRegex: Boolean,
        matchCase: Boolean,
        wholeWord: Boolean,
    ): Pattern? {
        return runCatching {
            val baseRegex = if (isRegex) query else Pattern.quote(query)
            val wordRegex = if (wholeWord) "\\b$baseRegex\\b" else baseRegex
            val flags = if (matchCase) 0 else Pattern.CASE_INSENSITIVE
            Pattern.compile(wordRegex, flags)
        }.getOrNull()
    }

    private fun globToRegex(glob: String): Regex {
        val pattern = glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex("^$pattern$", RegexOption.IGNORE_CASE)
    }

    private fun MatcherQuoteReplacement(s: String): String =
        java.util.regex.Matcher.quoteReplacement(s)
}
