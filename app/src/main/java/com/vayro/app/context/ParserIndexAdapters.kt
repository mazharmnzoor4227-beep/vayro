package com.vayro.app.context

import com.vayro.app.lsp.ParserProjectIndex
import com.vayro.app.runtime.DependencyGraph

/**
 * Milestone 4 — adapters that expose M3's real parser project index
 * ([ParserProjectIndex]) to the ranked context pipeline as dependency data.
 *
 * The parser index holds real per-file imports/dependents derived from actual
 * parsing (not regex heuristics); this adapter converts them into the
 * [DependencyGraph] shape the ranking engine consumes.
 */
object ParserIndexAdapters {

    /**
     * Builds a [DependencyGraph] from the real parser index. Only files
     * present in the parser index contribute; files unknown to the index are
     * simply absent (never invented).
     */
    fun toDependencyGraph(parserIndex: ParserProjectIndex): DependencyGraph {
        val imports = mutableMapOf<String, List<String>>()
        val dependents = mutableMapOf<String, MutableList<String>>()

        for (path in parserIndex.indexedPaths()) {
            val fileImports = parserIndex.getImports(path).toList()
            imports[path] = fileImports
            dependents.getOrPut(path) { mutableListOf() }
            for (imported in fileImports) {
                dependents.getOrPut(imported) { mutableListOf() }.add(path)
            }
            // Merge the index's own dependents view (it tracks reverse edges too).
            for (dependent in parserIndex.getDependents(path)) {
                dependents.getOrPut(path) { mutableListOf() }.add(dependent)
            }
        }

        return DependencyGraph(
            imports = imports,
            dependents = dependents.mapValues { it.value.distinct() },
        )
    }

    /**
     * Merges the parser-backed dependency graph with a heuristic one.
     * Parser edges win on conflicts; heuristic edges fill gaps for files the
     * parser index has not seen. Never duplicates an edge.
     */
    fun mergeDependencyGraphs(
        parserGraph: DependencyGraph,
        heuristicGraph: DependencyGraph?,
    ): DependencyGraph {
        if (heuristicGraph == null) return parserGraph
        val imports = parserGraph.imports.toMutableMap()
        val dependents = parserGraph.dependents.mapValues { it.value.toMutableList() }.toMutableMap()

        for ((path, heuristicImports) in heuristicGraph.imports) {
            if (path !in imports) {
                imports[path] = heuristicImports
            }
        }
        for ((path, heuristicDependents) in heuristicGraph.dependents) {
            val existing = dependents.getOrPut(path) { mutableListOf() }
            for (dep in heuristicDependents) {
                if (dep !in existing) existing.add(dep)
            }
        }
        return DependencyGraph(imports = imports, dependents = dependents)
    }
}
