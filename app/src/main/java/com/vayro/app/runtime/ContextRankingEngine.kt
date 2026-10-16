package com.vayro.app.runtime

import com.vayro.app.context.ContextTraceBuilder
import com.vayro.app.context.TraceSignal
import java.io.File

enum class UserIntent {
    BUG_FIX,
    FEATURE_IMPLEMENTATION,
    REFACTORING,
    BUILD_OR_TEST,
    EXPLANATION_QUESTION,
    DOCUMENTATION,
    DEPENDENCY_UPDATE,
    GENERAL,
}

data class ErrorContext(
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val targetFiles: List<String> = emptyList(),
    val lineNumbers: Map<String, Int> = emptyMap(),
    val rawSnippet: String = "",
)

data class ExtractedCodeBlock(
    val filePath: String,
    val symbolName: String? = null,
    val startLine: Int,
    val endLine: Int,
    val code: String,
)

data class RankedContextFile(
    val file: IndexedFile,
    val score: Int,
    val matchedSymbols: List<String>,
    val reason: String,
    val targetLine: Int? = null,
)

object ErrorContextExtractor {
    private val stackTraceRegex = Regex("""at\s+([A-Za-z0-9_.$]+)\(([A-Za-z0-9_]+\.[a-zA-Z]+):(\d+)\)""")
    private val fileLineRegex = Regex("""(?:^|[\s(])([A-Za-z0-9_./\\-]+\.[a-zA-Z]+):(\d+)(?::\d+)?(?:\)?|$)""")
    private val exceptionRegex = Regex("""(?:Caused by:\s+)?([A-Za-z0-9_.]*(?:Exception|Error))(?::\s*(.+))?""")

    fun extractErrorContext(request: String): ErrorContext? {
        val lines = request.lines()
        val targetFiles = mutableListOf<String>()
        val lineNumbers = mutableMapOf<String, Int>()
        var excType: String? = null
        var excMsg: String? = null
        val snippetLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()

            // Check exception pattern
            val excMatch = exceptionRegex.find(trimmed)
            if (excMatch != null && excType == null) {
                excType = excMatch.groupValues[1].substringAfterLast('.')
                excMsg = excMatch.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                snippetLines.add(trimmed)
            }

            // Check stack trace pattern: at com.example.Foo(Foo.kt:42)
            val stMatch = stackTraceRegex.find(trimmed)
            if (stMatch != null) {
                val fileName = stMatch.groupValues[2]
                val lineNum = stMatch.groupValues[3].toIntOrNull() ?: 1
                targetFiles.add(fileName)
                lineNumbers[fileName] = lineNum
                snippetLines.add(trimmed)
                continue
            }

            // Check file:line coordinate pattern: Foo.kt:42
            val flMatch = fileLineRegex.find(trimmed)
            if (flMatch != null && (trimmed.contains("error") || trimmed.contains("fail") || trimmed.contains("exception") || trimmed.contains(".kt") || trimmed.contains(".java") || trimmed.contains(".dart") || trimmed.contains(".php") || trimmed.contains(".ts"))) {
                val fileRef = flMatch.groupValues[1]
                val lineNum = flMatch.groupValues[2].toIntOrNull() ?: 1
                val base = fileRef.substringAfterLast('/')
                targetFiles.add(base)
                targetFiles.add(fileRef)
                lineNumbers[base] = lineNum
                lineNumbers[fileRef] = lineNum
                snippetLines.add(trimmed)
            }
        }

        if (targetFiles.isEmpty() && excType == null) return null

        return ErrorContext(
            exceptionType = excType,
            exceptionMessage = excMsg,
            targetFiles = targetFiles.distinct(),
            lineNumbers = lineNumbers,
            rawSnippet = snippetLines.take(6).joinToString("\n"),
        )
    }
}

/**
 * Intelligent context ranking, intent classification, and token budget management engine.
 */
object ContextRankingEngine {

    fun detectIntent(request: String): UserIntent {
        val lower = request.trim().lowercase()
        val errorKeywords = setOf("error", "crash", "exception", "fail", "failed", "bug", "fix", "broken", "trace", "nullpointer", "stacktrace")
        val buildKeywords = setOf("build", "compile", "test", "verify", "gradle", "make", "unit test", "check")
        val depKeywords = setOf("dependency", "package", "library", "upgrade", "version", "dependencies")
        val docKeywords = setOf("doc", "docs", "documentation", "readme", "comment", "javadoc", "kdoc")
        val featureKeywords = setOf("add", "create", "implement", "new", "integrate", "support", "feature")
        val refactorKeywords = setOf("refactor", "cleanup", "clean up", "optimize", "restructure", "simplify", "extract")
        val questionKeywords = setOf("why", "how", "what is", "explain", "where", "tell me", "does")

        return when {
            errorKeywords.any { lower.contains(it) } || lower.contains("at com.") || lower.contains("caused by:") -> UserIntent.BUG_FIX
            depKeywords.any { lower.contains(it) } -> UserIntent.DEPENDENCY_UPDATE
            buildKeywords.any { lower.contains(it) } -> UserIntent.BUILD_OR_TEST
            docKeywords.any { lower.contains(it) } -> UserIntent.DOCUMENTATION
            featureKeywords.any { lower.contains(it) } -> UserIntent.FEATURE_IMPLEMENTATION
            refactorKeywords.any { lower.contains(it) } -> UserIntent.REFACTORING
            questionKeywords.any { lower.contains(it) } -> UserIntent.EXPLANATION_QUESTION
            else -> UserIntent.GENERAL
        }
    }

    fun rankFiles(
        files: List<IndexedFile>,
        request: String,
        mentionedPaths: List<String> = emptyList(),
        dependencyGraph: DependencyGraph? = null,
        symbolGraph: SymbolGraph? = null,
        gitModifiedPaths: List<String> = emptyList(),
        errorContext: ErrorContext? = null,
        limit: Int = 15,
        trace: ContextTraceBuilder? = null,
    ): List<RankedContextFile> {
        val queryTokens = request.lowercase()
            .split(Regex("[^a-zA-Z0-9_]"))
            .filter { it.length > 2 }
            .toSet()

        val mentionedSet = mentionedPaths.toSet()
        val gitModifiedSet = gitModifiedPaths.map { it.lowercase() }.toSet()
        val errorTargets = errorContext?.targetFiles?.map { it.lowercase() }?.toSet().orEmpty()

        val directConnectedFiles = mutableSetOf<String>()
        if (dependencyGraph != null) {
            mentionedPaths.forEach { path ->
                dependencyGraph.imports[path]?.let { directConnectedFiles.addAll(it) }
                dependencyGraph.dependents[path]?.let { directConnectedFiles.addAll(it) }
            }
            errorTargets.forEach { errTarget ->
                files.filter { it.path.lowercase().endsWith(errTarget) }.forEach { match ->
                    dependencyGraph.imports[match.path]?.let { directConnectedFiles.addAll(it) }
                    dependencyGraph.dependents[match.path]?.let { directConnectedFiles.addAll(it) }
                }
            }
        }

        val symbolGraphConnectedFiles = mutableSetOf<String>()
        if (symbolGraph != null) {
            queryTokens.forEach { token ->
                val nodes = symbolGraph.findNodesByName(token)
                nodes.forEach { node ->
                    symbolGraph.getCallers(node.name).forEach { symbolGraphConnectedFiles.add(it.filePath) }
                    symbolGraph.getCallees(node.name).forEach { symbolGraphConnectedFiles.add(it.filePath) }
                    symbolGraph.getSuperTypes(node.name).forEach { symbolGraphConnectedFiles.add(it.filePath) }
                    symbolGraph.getSubTypes(node.name).forEach { symbolGraphConnectedFiles.add(it.filePath) }
                }
            }
        }

        return files.map { file ->
            var score = 0
            val reasons = mutableListOf<String>()
            val matchedSymbols = mutableListOf<String>()
            // Milestone 4: per-signal contributions for the context trace.
            val signalContributions = mutableListOf<Triple<TraceSignal, Int, String>>()
            val filePathLower = file.path.lowercase()
            val fileName = file.path.substringAfterLast('/').lowercase()

            // Tier 1: Explicit mentions (+1000)
            if (file.path in mentionedSet) {
                score += 1000
                reasons.add("Explicit @mention")
                signalContributions.add(Triple(TraceSignal.EXPLICIT_MENTION, 1000, "explicitly mentioned in request"))
            }

            // Tier 2: Stack trace error target (+800)
            val isErrorTarget = errorTargets.any { filePathLower.endsWith(it) || fileName == it }
            if (isErrorTarget) {
                score += 800
                reasons.add("Stack trace error target")
                signalContributions.add(Triple(TraceSignal.ERROR_TARGET, 800, "matches stack-trace target file"))
            }

            // Tier 3: Git modified / uncommitted (+400)
            val isGitModified = gitModifiedSet.any { filePathLower.endsWith(it) || fileName == it }
            if (isGitModified) {
                score += 400
                reasons.add("Recently modified in Git")
                signalContributions.add(Triple(TraceSignal.GIT_MODIFIED, 400, "uncommitted/modified in git"))
            }

            // Tier 4: Symbol Graph relation (+250)
            if (file.path in symbolGraphConnectedFiles) {
                score += 250
                reasons.add("Symbol graph relation")
                signalContributions.add(Triple(TraceSignal.SYMBOL_GRAPH, 250, "caller/callee/inheritance relation to query symbol"))
            }

            // Tier 5: Direct dependency (+200)
            if (file.path in directConnectedFiles) {
                score += 200
                reasons.add("Direct dependency of target")
                signalContributions.add(Triple(TraceSignal.DEPENDENCY, 200, "imports or is imported by target file"))
            }

            // Tier 6: Filename match (+120)
            if (queryTokens.any { fileName.contains(it) }) {
                score += 120
                reasons.add("File name match")
                signalContributions.add(Triple(TraceSignal.FILENAME_MATCH, 120, "file name matches query token"))
            }

            // Tier 7: Symbol matching (+45 per symbol)
            file.symbols.forEach { sym ->
                val symLower = sym.lowercase()
                if (queryTokens.any { symLower.contains(it) || it.contains(symLower) }) {
                    score += 45
                    matchedSymbols.add(sym)
                }
            }
            if (matchedSymbols.isNotEmpty()) {
                signalContributions.add(
                    Triple(TraceSignal.SYMBOL_MATCH, matchedSymbols.size * 45, "symbols matched: ${matchedSymbols.distinct().take(6).joinToString(", ")}")
                )
            }

            // Tier 8: Import matching (+15 per import)
            val matchingImports = file.imports.count { imp ->
                queryTokens.any { imp.lowercase().contains(it) }
            }
            if (matchingImports > 0) {
                score += matchingImports * 15
                signalContributions.add(
                    Triple(TraceSignal.IMPORT_MATCH, matchingImports * 15, "$matchingImports imports match query tokens")
                )
            }

            val targetLine = errorContext?.lineNumbers?.get(fileName)
                ?: errorContext?.lineNumbers?.get(file.path)

            ScoredFile(
                ranked = RankedContextFile(
                    file = file,
                    score = score,
                    matchedSymbols = matchedSymbols.distinct().take(6),
                    reason = reasons.ifEmpty { listOf("Keyword relevance") }.joinToString(", "),
                    targetLine = targetLine,
                ),
                signalContributions = signalContributions,
            )
        }
            .filter { it.ranked.score > 0 }
            .sortedByDescending { it.ranked.score }
            .let { scored ->
                val top = scored.take(limit)
                val topPaths = top.map { it.ranked.file.path }.toSet()
                // Milestone 4: record the trace — why each file was included or excluded.
                if (trace != null) {
                    for (s in scored) {
                        val included = s.ranked.file.path in topPaths
                        if (s.signalContributions.isEmpty()) {
                            trace.record(
                                TraceSignal.KEYWORD, s.ranked.file.path, 0, included,
                                if (included) "keyword relevance only" else "no signal fired; excluded",
                            )
                        } else {
                            for ((signal, points, detail) in s.signalContributions) {
                                trace.record(signal, s.ranked.file.path, points, included, detail)
                            }
                        }
                    }
                    for (file in files) {
                        if (scored.none { it.ranked.file.path == file.path }) {
                            trace.record(
                                TraceSignal.KEYWORD, file.path, 0, false,
                                "score 0: no signal fired; excluded",
                            )
                        }
                    }
                }
                top.map { it.ranked }
            }
    }

    /** Internal holder pairing a ranked file with its per-signal trace contributions. */
    private data class ScoredFile(
        val ranked: RankedContextFile,
        val signalContributions: List<Triple<TraceSignal, Int, String>>,
    )

    /**
     * Extracts relevant source code blocks around matched symbols or error lines.
     */
    fun extractRelevantCodeBlocks(
        workspaceDir: File?,
        rankedFiles: List<RankedContextFile>,
        maxTotalChars: Int = 3000,
    ): List<ExtractedCodeBlock> {
        if (workspaceDir == null || !workspaceDir.isDirectory) return emptyList()
        val blocks = mutableListOf<ExtractedCodeBlock>()
        var totalChars = 0

        for (item in rankedFiles.take(4)) {
            val file = File(workspaceDir, item.file.path)
            if (!file.isFile) continue
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            if (lines.isEmpty()) continue

            val targetLine = item.targetLine
            val matchedSym = item.matchedSymbols.firstOrNull()

            val centerLine = when {
                targetLine != null && targetLine in 1..lines.size -> targetLine
                matchedSym != null -> {
                    val idx = lines.indexOfFirst { it.contains(matchedSym) }
                    if (idx >= 0) idx + 1 else 1
                }
                else -> 1
            }

            val start = (centerLine - 6).coerceAtLeast(1)
            val end = (centerLine + 10).coerceAtMost(lines.size)

            val snippet = buildString {
                for (ln in start..end) {
                    val lineContent = lines[ln - 1]
                    val marker = if (ln == targetLine) ">" else " "
                    appendLine("$marker $ln: $lineContent")
                }
            }.trimEnd()

            if (totalChars + snippet.length > maxTotalChars && blocks.isNotEmpty()) break

            blocks.add(
                ExtractedCodeBlock(
                    filePath = item.file.path,
                    symbolName = matchedSym,
                    startLine = start,
                    endLine = end,
                    code = snippet,
                )
            )
            totalChars += snippet.length
        }
        return blocks
    }

    /**
     * Formats ranked context into a compressed, budget-controlled XML block.
     */
    fun compressContext(
        intent: UserIntent,
        ranked: List<RankedContextFile>,
        memories: List<ProjectMemoryItem> = emptyList(),
        codeBlocks: List<ExtractedCodeBlock> = emptyList(),
        projectOverview: String? = null,
        errorContext: ErrorContext? = null,
        maxTokenBudget: Int = 3000,
    ): String {
        if (ranked.isEmpty() && memories.isEmpty() && projectOverview.isNullOrBlank() && errorContext == null) return ""

        val approxCharsPerToken = 4
        val maxChars = maxTokenBudget * approxCharsPerToken
        val sb = StringBuilder()

        sb.appendLine("<context_engine_v2 intent=\"${intent.name}\">")
        var currentLength = sb.length

        // 1. Project Overview
        if (!projectOverview.isNullOrBlank()) {
            val block = "  <project_overview>\n    ${projectOverview.replace("\n", "\n    ")}\n  </project_overview>"
            if (currentLength + block.length <= maxChars) {
                sb.appendLine(block)
                currentLength += block.length + 1
            }
        }

        // 2. Error Context
        if (errorContext != null) {
            val errBlock = buildString {
                appendLine("  <error_context>")
                errorContext.exceptionType?.let { appendLine("    exception: $it${if (!errorContext.exceptionMessage.isNullOrBlank()) " (${errorContext.exceptionMessage})" else ""}") }
                if (errorContext.targetFiles.isNotEmpty()) {
                    appendLine("    target_files: ${errorContext.targetFiles.joinToString(", ")}")
                }
                if (errorContext.rawSnippet.isNotBlank()) {
                    appendLine("    trace_snippet: |")
                    errorContext.rawSnippet.lines().take(4).forEach { appendLine("      $it") }
                }
                appendLine("  </error_context>")
            }.trimEnd()
            if (currentLength + errBlock.length <= maxChars) {
                sb.appendLine(errBlock)
                currentLength += errBlock.length + 1
            }
        }

        // 3. Project Memory
        if (memories.isNotEmpty()) {
            val memBlock = buildString {
                appendLine("  <project_memory>")
                memories.take(8).forEach { mem ->
                    appendLine("    - [${mem.category.name}] ${mem.key}: ${mem.content}")
                }
                appendLine("  </project_memory>")
            }.trimEnd()
            if (currentLength + memBlock.length <= maxChars) {
                sb.appendLine(memBlock)
                currentLength += memBlock.length + 1
            }
        }

        // 4. Relevant Files
        if (ranked.isNotEmpty()) {
            sb.appendLine("  <relevant_files count=\"${ranked.size}\">")
            for ((idx, item) in ranked.withIndex()) {
                val header = "- ${item.file.path} [${item.file.language}] — ${item.reason}"
                val line = buildString {
                    appendLine("    $header")
                    if (item.matchedSymbols.isNotEmpty()) {
                        appendLine("      symbols: ${item.matchedSymbols.joinToString(", ")}")
                    }
                    if (item.file.imports.isNotEmpty()) {
                        appendLine("      imports: ${item.file.imports.take(5).joinToString(", ")}")
                    }
                }.trimEnd()
                if (currentLength + line.length > maxChars) {
                    sb.appendLine("    <!-- Truncated ${ranked.size - idx} lower-priority files due to context budget -->")
                    break
                }
                sb.appendLine(line)
                currentLength += line.length + 1
            }
            sb.appendLine("  </relevant_files>")
        }

        // 5. Relevant Code Blocks
        if (codeBlocks.isNotEmpty()) {
            sb.appendLine("  <relevant_code_blocks count=\"${codeBlocks.size}\">")
            currentLength += 45
            for (block in codeBlocks) {
                val blockStr = buildString {
                    appendLine("    <block file=\"${block.filePath}\" lines=\"${block.startLine}-${block.endLine}\">")
                    appendLine(block.code)
                    appendLine("    </block>")
                }
                if (currentLength + blockStr.length > maxChars) {
                    sb.appendLine("    <!-- Truncated additional code blocks to conserve token budget -->")
                    break
                }
                sb.append(blockStr)
                currentLength += blockStr.length
            }
            sb.appendLine("  </relevant_code_blocks>")
        }

        sb.appendLine("</context_engine_v2>")
        return sb.toString().trim()
    }
}
