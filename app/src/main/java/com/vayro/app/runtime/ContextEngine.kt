package com.vayro.app.runtime

import com.vayro.app.context.ApproximateTokenizer
import com.vayro.app.context.BudgetReport
import com.vayro.app.context.BudgetSection
import com.vayro.app.context.ContextBudget
import com.vayro.app.context.ContextTrace
import com.vayro.app.context.ContextTraceBuilder
import com.vayro.app.context.ModelTokenizer
import com.vayro.app.context.PipelineInput
import com.vayro.app.context.RankedContextPipeline
import com.vayro.app.context.TraceSignal
import com.vayro.app.lsp.ParserProjectIndex
import com.vayro.app.workspace.GitDomainController
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ContextReference(
    val path: String,
    val language: String,
    val symbols: List<String>,
    val imports: List<String>,
    val reason: String,
)

/**
 * Result of the measurable context pipeline: the assembled context plus the
 * trace explaining every retrieval decision and the measured budget report.
 */
data class ContextBuildResult(
    val context: String,
    val trace: ContextTrace,
    val budget: BudgetReport,
    val parserBacked: Boolean,
)

/**
 * AI Context Engine 2.0.
 * Implements the 10-step context pipeline:
 * Request -> Intent -> Project Overview -> Symbol Retrieval -> Dependency Retrieval ->
 * Git Context -> Relevant Files & Code Blocks -> Context Ranking -> Token Budget Allocation -> Agent Context.
 *
 * Milestone 4: ranking runs through the single [RankedContextPipeline] (with
 * M3 real-parser dependency edges when a [ParserProjectIndex] is supplied),
 * the token budget is enforced measurably via [ContextBudget], every decision
 * is recorded in a [ContextTrace], and the cache can be invalidated on file
 * edits, branch switches, dependency changes and index rebuilds.
 */
class ContextEngine(
    private val projectIndex: ProjectIndex,
    val memoryStore: ProjectMemoryStore? = null,
    private val workspaceSemanticIndex: WorkspaceSemanticIndex? = null,
    private val gitDomainController: GitDomainController? = null,
    private val parserProjectIndex: ParserProjectIndex? = null,
    private val tokenizer: ModelTokenizer = ApproximateTokenizer(),
) {
    companion object {
        private val CONVERSATIONAL_KEYWORDS = setOf(
            "yes", "no", "ok", "okay", "continue", "proceed", "go ahead",
            "approve", "approved", "done", "next", "thanks", "thank you",
            "start", "run", "build", "stop", "cancel", "help",
        )
    }

    private data class CacheKey(
        val projectId: String,
        val workspaceFingerprint: String,
        val requestHash: Int,
    )

    private val cache = ConcurrentHashMap<CacheKey, Pair<Long, String>>()
    private val cacheTtlMillis = 60_000L // 60 seconds

    fun clearCache() {
        cache.clear()
    }

    // --- Milestone 4: explicit cache invalidation hooks ---
    // (File edits are additionally caught by ProjectIndex.ensureFresh, whose
    // workspace fingerprint covers path + size + mtime. These hooks cover the
    // events a fingerprint cannot see.)

    /** Call when a file is edited outside the indexed scan (e.g. editor save). */
    fun invalidateOnFileEdit(@Suppress("UNUSED_PARAMETER") relativePath: String) {
        cache.clear()
    }

    /** Call when the Git branch changes: modified-file sets are branch-scoped. */
    fun invalidateOnBranchSwitch() {
        cache.clear()
    }

    /** Call when dependencies change (manifest/lockfile edits, SDK updates). */
    fun invalidateOnDependencyChange() {
        cache.clear()
        workspaceSemanticIndex?.let {
            // Force the semantic index to rebuild its graphs on next query.
            it.indexWorkspace()
        }
    }

    /** Call when any backing index (project/parser/semantic) is rebuilt. */
    fun invalidateOnIndexRebuild() {
        cache.clear()
    }

    private fun isConversationalOrBrief(request: String): Boolean {
        val trimmed = request.trim().lowercase()
        if (trimmed.length < 3) return true
        if (trimmed in CONVERSATIONAL_KEYWORDS) return true
        val words = trimmed.split("\\s+".toRegex())
        return words.size <= 2 && words.all { it in CONVERSATIONAL_KEYWORDS }
    }

    fun buildPromptContext(
        projectId: String,
        workspace: File,
        request: String,
        mentionedPaths: List<String> = emptyList(),
        gitModifiedPaths: List<String> = emptyList(),
        maxReferences: Int = 12,
        maxTokenBudget: Int = 3000,
    ): String {
        if (isConversationalOrBrief(request) && mentionedPaths.isEmpty()) return ""

        val snapshot = projectIndex.ensureFresh(projectId, workspace)

        // Cache lookup
        val cacheKey = CacheKey(
            projectId = projectId,
            workspaceFingerprint = snapshot.rootFingerprint,
            requestHash = (request.trim().lowercase() + mentionedPaths.joinToString(",")).hashCode(),
        )
        val cached = cache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.first) < cacheTtlMillis) {
            return cached.second
        }

        // 1. Intent Detection
        val intent = ContextRankingEngine.detectIntent(request)

        // 2. Error & Stack Trace Extraction
        val errorContext = ErrorContextExtractor.extractErrorContext(request)

        // 3. Project Overview & Architectural Understanding
        val projectOverview = workspaceSemanticIndex?.getProjectOverview()

        // 4. Git-aware Context Retrieval
        val activeGitModified = if (gitModifiedPaths.isNotEmpty()) {
            gitModifiedPaths
        } else if (gitDomainController != null) {
            val statusRes = gitDomainController.status(workspace)
            if (statusRes.success) {
                statusRes.output.lines()
                    .filter { it.length > 3 }
                    .map { it.substring(3).trim() }
            } else emptyList()
        } else emptyList()

        // 5. Symbol & Dependency Graph Retrieval
        val dependencyGraph = workspaceSemanticIndex?.getDependencyGraph()
        val symbolGraph = workspaceSemanticIndex?.getSymbolGraph()

        // 6. Project & Task Memory Retrieval (all 7 categories + query search)
        val allMemories = memoryStore?.getMemories(projectId).orEmpty()
        val searchedMemories = memoryStore?.search(projectId, request, limit = 6).orEmpty()
        val combinedMemories = (searchedMemories + allMemories).distinctBy { it.id }

        // 7. Multi-Factor File Ranking
        val ranked = ContextRankingEngine.rankFiles(
            files = snapshot.files,
            request = request,
            mentionedPaths = mentionedPaths,
            dependencyGraph = dependencyGraph,
            symbolGraph = symbolGraph,
            gitModifiedPaths = activeGitModified,
            errorContext = errorContext,
            limit = maxReferences,
        )

        // 8. Relevant Code Block Extraction
        val codeBlocks = ContextRankingEngine.extractRelevantCodeBlocks(
            workspaceDir = workspace,
            rankedFiles = ranked,
            maxTotalChars = (maxTokenBudget * 2),
        )

        // 9. Context Compression & Token Budget Enforcement
        val compressed = ContextRankingEngine.compressContext(
            intent = intent,
            ranked = ranked,
            memories = combinedMemories,
            codeBlocks = codeBlocks,
            projectOverview = projectOverview,
            errorContext = errorContext,
            maxTokenBudget = maxTokenBudget,
        )

        // 10. Store in cache
        cache[cacheKey] = now to compressed

        return compressed
    }

    /**
     * Milestone 4 — measurable context build.
     *
     * Runs the single [RankedContextPipeline] (symbol/dependency/Git/error/
     * memory signals), records a [ContextTrace] for every retrieval decision,
     * and enforces the token budget measurably with [ContextBudget].
     * The returned [ContextBuildResult.budget] proves the budget was honored.
     */
    fun buildContextWithTrace(
        projectId: String,
        workspace: File,
        request: String,
        mentionedPaths: List<String> = emptyList(),
        gitModifiedPaths: List<String> = emptyList(),
        maxReferences: Int = 12,
        maxTokenBudget: Int = 3000,
    ): ContextBuildResult {
        if (isConversationalOrBrief(request) && mentionedPaths.isEmpty()) {
            return ContextBuildResult(
                context = "",
                trace = ContextTrace.empty(),
                budget = ContextBudget(maxTokenBudget, tokenizer).enforce(emptyList()).report,
                parserBacked = false,
            )
        }

        val snapshot = projectIndex.ensureFresh(projectId, workspace)
        val traceBuilder = ContextTraceBuilder()

        val intent = ContextRankingEngine.detectIntent(request)

        val activeGitModified = if (gitModifiedPaths.isNotEmpty()) {
            gitModifiedPaths
        } else if (gitDomainController != null) {
            val statusRes = gitDomainController.status(workspace)
            if (statusRes.success) {
                statusRes.output.lines()
                    .filter { it.length > 3 }
                    .map { it.substring(3).trim() }
            } else emptyList()
        } else emptyList()

        // Single ranked pipeline: all signals, one ranking, full trace.
        val pipelineResult = RankedContextPipeline.run(
            input = PipelineInput(
                files = snapshot.files,
                request = request,
                mentionedPaths = mentionedPaths,
                dependencyGraph = workspaceSemanticIndex?.getDependencyGraph(),
                symbolGraph = workspaceSemanticIndex?.getSymbolGraph(),
                parserProjectIndex = parserProjectIndex,
                gitModifiedPaths = activeGitModified,
                limit = maxReferences,
            ),
            traceBuilder = traceBuilder,
        )

        // Project memory: searched + full list, recorded in the trace.
        val allMemories = memoryStore?.getMemories(projectId).orEmpty()
        val searchedMemories = memoryStore?.search(projectId, request, limit = 6).orEmpty()
        val combinedMemories = (searchedMemories + allMemories).distinctBy { it.id }
        for (mem in combinedMemories.take(8)) {
            traceBuilder.record(
                TraceSignal.MEMORY_HIT,
                "<memory:${mem.id}>",
                0,
                true,
                "[${mem.category.name}] ${mem.key} (confidence ${mem.confidence})",
            )
        }

        val trace = traceBuilder.build()

        val codeBlocks = ContextRankingEngine.extractRelevantCodeBlocks(
            workspaceDir = workspace,
            rankedFiles = pipelineResult.ranked,
            maxTotalChars = (maxTokenBudget * 4),
        )

        // Assemble budget sections by priority (1 = most important).
        val projectOverview = workspaceSemanticIndex?.getProjectOverview()
        val sections = mutableListOf<BudgetSection>()
        var priority = 1
        if (!projectOverview.isNullOrBlank()) {
            sections.add(BudgetSection("project_overview", projectOverview, priority++, truncatable = false))
        }
        pipelineResult.errorContext?.let { err ->
            val errText = buildString {
                appendLine("exception: ${err.exceptionType ?: "unknown"}")
                if (err.targetFiles.isNotEmpty()) appendLine("target_files: ${err.targetFiles.joinToString(", ")}")
                if (err.rawSnippet.isNotBlank()) appendLine(err.rawSnippet.lines().take(4).joinToString("\n"))
            }.trimEnd()
            sections.add(BudgetSection("error_context", errText, priority++, truncatable = false))
        }
        if (combinedMemories.isNotEmpty()) {
            val memText = combinedMemories.take(8).joinToString("\n") { "- [${it.category.name}] ${it.key}: ${it.content}" }
            sections.add(BudgetSection("project_memory", memText, priority++))
        }
        if (pipelineResult.ranked.isNotEmpty()) {
            val filesText = pipelineResult.ranked.joinToString("\n") { item ->
                "- ${item.file.path} [${item.file.language}] — ${item.reason}" +
                    (if (item.matchedSymbols.isNotEmpty()) " (symbols: ${item.matchedSymbols.joinToString(", ")})" else "")
            }
            sections.add(BudgetSection("relevant_files", filesText, priority++))
        }
        codeBlocks.forEachIndexed { idx, block ->
            sections.add(
                BudgetSection(
                    "code_block_${idx}_${block.filePath.substringAfterLast('/')}",
                    "${block.filePath} lines ${block.startLine}-${block.endLine}:\n${block.code}",
                    priority++,
                )
            )
        }

        val budget = ContextBudget(maxTokenBudget, tokenizer)
        val enforcement = budget.enforce(sections)

        val context = buildString {
            appendLine("<context_engine_v2 intent=\"${intent.name}\" budget_tokens=\"${enforcement.report.tokensUsed}/${enforcement.report.maxTokens}\" tokenizer=\"${tokenizer.name}\">")
            for (section in enforcement.sections) {
                appendLine("  <${section.name}>")
                appendLine(section.content.lines().joinToString("\n") { "    $it" })
                appendLine("  </${section.name}>")
            }
            appendLine("</context_engine_v2>")
        }.trim()

        return ContextBuildResult(
            context = context,
            trace = trace,
            budget = enforcement.report,
            parserBacked = pipelineResult.parserBacked,
        )
    }
}
