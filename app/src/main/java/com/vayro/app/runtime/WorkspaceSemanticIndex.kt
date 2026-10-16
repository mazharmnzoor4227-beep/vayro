package com.vayro.app.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Incremental Workspace Semantic Index.
 * Maintained in-memory index serving Symbol Graph and Dependency Graph queries
 * to both the mobile code editor and the AI Context Engine.
 */
class WorkspaceSemanticIndex(
    private val workspaceDir: File? = null,
) {

    private val summaries = ConcurrentHashMap<String, FileSemanticSummary>()
    private var cachedSymbolGraph: SymbolGraph = SymbolGraph()
    private var cachedDependencyGraph: DependencyGraph = DependencyGraph(emptyMap(), emptyMap())
    private var isDirty = true

    /**
     * Incrementally indexes all supported source files in the workspace.
     */
    fun indexWorkspace(maxFiles: Int = 120): WorkspaceSemanticIndex {
        if (workspaceDir == null || !workspaceDir.isDirectory) return this

        val candidateFiles = workspaceDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.length() in 1..800_000 &&
                    !file.path.contains("/build/") &&
                    !file.path.contains("/.git/") &&
                    !file.path.contains("/.gradle/") &&
                    !file.path.contains("/node_modules/")
            }
            .take(maxFiles)
            .toList()

        for (file in candidateFiles) {
            val relPath = file.relativeTo(workspaceDir).invariantSeparatorsPath
            val content = runCatching { file.readText() }.getOrDefault("")
            val summary = SemanticCodeEngine.parseFile(relPath, content)
            summaries[relPath] = summary
        }

        rebuildGraphs()
        return this
    }

    /**
     * Updates index for a single modified file.
     */
    fun updateFile(filePath: String, content: String) {
        val summary = SemanticCodeEngine.parseFile(filePath, content)
        summaries[filePath] = summary
        isDirty = true
    }

    /**
     * Removes a deleted file from the index.
     */
    fun removeFile(filePath: String) {
        if (summaries.remove(filePath) != null) {
            isDirty = true
        }
    }

    fun getSummaries(): Map<String, FileSemanticSummary> = summaries

    fun getSymbolGraph(): SymbolGraph {
        if (isDirty) rebuildGraphs()
        return cachedSymbolGraph
    }

    fun getDependencyGraph(): DependencyGraph {
        if (isDirty) rebuildGraphs()
        return cachedDependencyGraph
    }

    @Synchronized
    private fun rebuildGraphs() {
        val activeSummaries = summaries.values.toList()
        cachedSymbolGraph = SymbolGraph.buildFromSummaries(activeSummaries)

        // Build file contents map for dependency graph resolution
        val filesMap = summaries.mapValues { it.value.contentHash }
        val allPaths = summaries.keys.toList()
        val importsMap = mutableMapOf<String, List<String>>()
        val dependentsMap = mutableMapOf<String, MutableList<String>>()

        allPaths.forEach { dependentsMap[it] = mutableListOf() }
        for ((path, summary) in summaries) {
            val resolved = SemanticCodeEngine.resolveImportsToFilePaths(path, summary.rawImports, allPaths)
            importsMap[path] = resolved
            for (imported in resolved) {
                dependentsMap.getOrPut(imported) { mutableListOf() }.add(path)
            }
        }

        cachedDependencyGraph = DependencyGraph(
            imports = importsMap,
            dependents = dependentsMap.mapValues { it.value.distinct() },
        )
        isDirty = false
    }

    // --- AI Context Engine Query Surface ---

    /**
     * Produces a concise structured project overview for LLM context prompts.
     */
    fun getProjectOverview(): String {
        val graph = getSymbolGraph()
        val totalFiles = summaries.size
        val totalSymbols = graph.nodes.size
        val classes = graph.nodes.values.count { it.kind == SymbolKind.CLASS }
        val interfaces = graph.nodes.values.count { it.kind == SymbolKind.INTERFACE }
        val functions = graph.nodes.values.count { it.kind == SymbolKind.FUNCTION || it.kind == SymbolKind.METHOD }

        val langCounts = summaries.values.groupingBy { it.language }.eachCount()
        val langSummary = langCounts.entries.joinToString(", ") { "${it.key}: ${it.value}" }

        return buildString {
            appendLine("=== Project Semantic Overview ===")
            appendLine("Indexed Files: $totalFiles ($langSummary)")
            appendLine("Symbols: $totalSymbols (Classes: $classes, Interfaces: $interfaces, Functions/Methods: $functions)")
            if (interfaces > 0) {
                val ifaces = graph.nodes.values.filter { it.kind == SymbolKind.INTERFACE }.take(8).map { it.name }
                appendLine("Key Interfaces: ${ifaces.joinToString(", ")}")
            }
        }.trimEnd()
    }

    /**
     * Resolves the full inheritance hierarchy, interface implementations, and child members
     * for a given symbol name.
     */
    fun getSymbolHierarchy(symbolName: String): String {
        val graph = getSymbolGraph()
        val nodes = graph.findNodesByName(symbolName)
        if (nodes.isEmpty()) return "Symbol '$symbolName' not found in workspace."

        val target = nodes.first()
        val superTypes = graph.getSuperTypes(symbolName)
        val subTypes = graph.getSubTypes(symbolName)
        val implementations = if (target.kind == SymbolKind.INTERFACE) graph.getImplementations(symbolName) else emptyList()
        val members = graph.getMembers(symbolName)

        return buildString {
            appendLine("Symbol: ${target.name} (${target.kind.name.lowercase()}) in ${target.filePath}:${target.line}")
            target.signature?.let { appendLine("Signature: $it") }
            target.docComment?.let { appendLine("Doc: $it") }
            if (superTypes.isNotEmpty()) {
                appendLine("Inherits/Implements: ${superTypes.joinToString(", ") { "${it.name} (${it.kind.name.lowercase()})" }}")
            }
            if (subTypes.isNotEmpty()) {
                appendLine("Sub-types: ${subTypes.joinToString(", ") { it.name }}")
            }
            if (implementations.isNotEmpty()) {
                appendLine("Implementations: ${implementations.joinToString(", ") { it.name }}")
            }
            if (members.isNotEmpty()) {
                appendLine("Members (${members.size}): ${members.take(10).joinToString(", ") { it.name }}")
            }
        }.trimEnd()
    }

    /**
     * Computes all workspace files that would be directly impacted if [symbolName] changes.
     */
    fun getImpactedFilesOnSymbolChange(symbolName: String): List<String> {
        val graph = getSymbolGraph()
        val impacted = mutableSetOf<String>()

        // 1. Files containing the declaration itself
        graph.findNodesByName(symbolName).forEach { impacted.add(it.filePath) }

        // 2. Callers of this symbol
        graph.getCallers(symbolName).forEach { impacted.add(it.filePath) }

        // 3. Subtypes / Implementations
        graph.getSubTypes(symbolName).forEach { impacted.add(it.filePath) }

        // 4. Files with raw import references
        val queryLower = symbolName.lowercase()
        summaries.values.forEach { sum ->
            if (sum.rawImports.any { it.lowercase().contains(queryLower) } || sum.calls.any { it.lowercase() == queryLower }) {
                impacted.add(sum.filePath)
            }
        }

        return impacted.toList().sorted()
    }
}
