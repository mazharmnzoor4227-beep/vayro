package com.vayro.app.runtime

enum class SymbolRelationType {
    EXTENDS,
    IMPLEMENTS,
    CALLS,
    CONTAINS,
    DEPENDS_ON,
}

data class SymbolNode(
    val id: String,
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val line: Int,
    val containerName: String? = null,
    val signature: String? = null,
    val docComment: String? = null,
    val superTypes: List<String> = emptyList(),
)

data class SymbolEdge(
    val sourceId: String,
    val targetId: String,
    val relation: SymbolRelationType,
)

/**
 * Symbol Graph & Relationship Intelligence Engine.
 * Builds and queries multi-language relationship graphs covering inheritance,
 * interface implementations, method callers/callees, and symbol containers.
 */
class SymbolGraph(
    val nodes: Map<String, SymbolNode> = emptyMap(),
    val edges: List<SymbolEdge> = emptyList(),
) {

    // Fast indexed adjacency lookups
    private val outgoingBySource: Map<String, List<SymbolEdge>> = edges.groupBy { it.sourceId }
    private val incomingByTarget: Map<String, List<SymbolEdge>> = edges.groupBy { it.targetId }
    private val nodesByName: Map<String, List<SymbolNode>> = nodes.values.groupBy { it.name.lowercase() }

    fun findNodesByName(name: String, exactMatch: Boolean = true): List<SymbolNode> {
        val query = name.trim().lowercase()
        return if (exactMatch) {
            nodesByName[query] ?: emptyList()
        } else {
            nodes.values.filter { it.name.lowercase().contains(query) }
        }
    }

    /**
     * Finds base classes/types that [symbolName] extends or implements.
     */
    fun getSuperTypes(symbolName: String): List<SymbolNode> {
        val matchingNodes = findNodesByName(symbolName)
        val result = mutableListOf<SymbolNode>()

        for (node in matchingNodes) {
            // Check direct superTypes strings
            for (superTypeName in node.superTypes) {
                result.addAll(findNodesByName(superTypeName))
            }
            // Check outgoing EXTENDS / IMPLEMENTS edges
            val outgoingEdges = outgoingBySource[node.id] ?: emptyList()
            for (edge in outgoingEdges) {
                if (edge.relation == SymbolRelationType.EXTENDS || edge.relation == SymbolRelationType.IMPLEMENTS) {
                    nodes[edge.targetId]?.let { result.add(it) }
                }
            }
        }
        return result.distinctBy { it.id }
    }

    /**
     * Finds classes or structs that inherit from or implement [symbolName].
     */
    fun getSubTypes(symbolName: String): List<SymbolNode> {
        val targetNodes = findNodesByName(symbolName)
        val result = mutableListOf<SymbolNode>()

        // 1. Through edges
        for (target in targetNodes) {
            val incoming = incomingByTarget[target.id] ?: emptyList()
            for (edge in incoming) {
                if (edge.relation == SymbolRelationType.EXTENDS || edge.relation == SymbolRelationType.IMPLEMENTS) {
                    nodes[edge.sourceId]?.let { result.add(it) }
                }
            }
        }

        // 2. Direct superTypes declaration matching
        val targetLower = symbolName.trim().lowercase()
        for (node in nodes.values) {
            if (node.superTypes.any { it.trim().lowercase() == targetLower }) {
                result.add(node)
            }
        }

        return result.distinctBy { it.id }
    }

    /**
     * Finds all concrete or abstract implementations of interface [interfaceName].
     */
    fun getImplementations(interfaceName: String): List<SymbolNode> {
        val targetLower = interfaceName.trim().lowercase()
        val results = mutableListOf<SymbolNode>()

        // Find incoming IMPLEMENTS edges
        val targetNodes = findNodesByName(interfaceName).filter {
            it.kind == SymbolKind.INTERFACE || it.kind == SymbolKind.CLASS
        }
        for (target in targetNodes) {
            val incoming = incomingByTarget[target.id] ?: emptyList()
            for (edge in incoming) {
                if (edge.relation == SymbolRelationType.IMPLEMENTS) {
                    nodes[edge.sourceId]?.let { results.add(it) }
                }
            }
        }

        // Also check superTypes list of any class/struct node
        for (node in nodes.values) {
            if ((node.kind == SymbolKind.CLASS || node.kind == SymbolKind.OBJECT || node.kind == SymbolKind.STRUCT) &&
                node.superTypes.any { it.trim().lowercase() == targetLower }
            ) {
                results.add(node)
            }
        }

        val direct = results.distinctBy { it.id }
        val all = mutableListOf<SymbolNode>()
        all.addAll(direct)
        val visited = direct.map { it.name.lowercase() }.toMutableSet()
        val queue = ArrayDeque(direct)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val subs = getSubTypes(current.name)
            for (sub in subs) {
                if (visited.add(sub.name.lowercase())) {
                    all.add(sub)
                    queue.add(sub)
                }
            }
        }

        return all.distinctBy { it.id }
    }

    /**
     * Finds functions or methods that call [functionName].
     */
    fun getCallers(functionName: String): List<SymbolNode> {
        val targetNodes = findNodesByName(functionName).filter {
            it.kind == SymbolKind.FUNCTION || it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR
        }
        val callers = mutableListOf<SymbolNode>()

        for (target in targetNodes) {
            val incoming = incomingByTarget[target.id] ?: emptyList()
            for (edge in incoming) {
                if (edge.relation == SymbolRelationType.CALLS) {
                    nodes[edge.sourceId]?.let { callers.add(it) }
                }
            }
        }
        return callers.distinctBy { it.id }
    }

    /**
     * Finds functions or methods invoked by [functionName].
     */
    fun getCallees(functionName: String): List<SymbolNode> {
        val sourceNodes = findNodesByName(functionName).filter {
            it.kind == SymbolKind.FUNCTION || it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR
        }
        val callees = mutableListOf<SymbolNode>()

        for (source in sourceNodes) {
            val outgoing = outgoingBySource[source.id] ?: emptyList()
            for (edge in outgoing) {
                if (edge.relation == SymbolRelationType.CALLS) {
                    nodes[edge.targetId]?.let { callees.add(it) }
                }
            }
        }
        return callees.distinctBy { it.id }
    }

    /**
     * Finds child members of [containerName] (methods, properties, constructors).
     */
    fun getMembers(containerName: String): List<SymbolNode> {
        val containerLower = containerName.trim().lowercase()
        return nodes.values.filter {
            it.containerName?.lowercase() == containerLower
        }.distinctBy { it.id }
    }

    companion object {
        fun buildFromSummaries(summaries: Collection<FileSemanticSummary>): SymbolGraph {
            val nodesMap = mutableMapOf<String, SymbolNode>()
            val edgesList = mutableListOf<SymbolEdge>()

            // 1. Create nodes for all declarations
            for (summary in summaries) {
                for (sym in summary.symbols) {
                    val id = "${summary.filePath}#${sym.containerName.orEmpty()}#${sym.name}:${sym.kind.name}:${sym.line}"
                    val node = SymbolNode(
                        id = id,
                        name = sym.name,
                        kind = sym.kind,
                        filePath = summary.filePath,
                        line = sym.line,
                        containerName = sym.containerName,
                        signature = sym.signature,
                        docComment = sym.docComment,
                        superTypes = sym.superTypes,
                    )
                    nodesMap[id] = node
                }
            }

            // Quick index by symbol name
            val nameToNodeIds = mutableMapOf<String, MutableList<String>>()
            nodesMap.values.forEach { node ->
                nameToNodeIds.getOrPut(node.name.lowercase()) { mutableListOf() }.add(node.id)
            }

            // 2. Build edges
            for (summary in summaries) {
                for (sym in summary.symbols) {
                    val sourceId = "${summary.filePath}#${sym.containerName.orEmpty()}#${sym.name}:${sym.kind.name}:${sym.line}"

                    // Inheritance edges (EXTENDS / IMPLEMENTS)
                    for (superName in sym.superTypes) {
                        val targets = nameToNodeIds[superName.lowercase()] ?: emptyList()
                        for (targetId in targets) {
                            val targetNode = nodesMap[targetId] ?: continue
                            val relation = if (targetNode.kind == SymbolKind.INTERFACE) {
                                SymbolRelationType.IMPLEMENTS
                            } else {
                                SymbolRelationType.EXTENDS
                            }
                            edgesList.add(SymbolEdge(sourceId, targetId, relation))
                        }
                    }

                    // Container containment edge
                    sym.containerName?.let { cName ->
                        val containers = nameToNodeIds[cName.lowercase()] ?: emptyList()
                        for (cId in containers) {
                            edgesList.add(SymbolEdge(cId, sourceId, SymbolRelationType.CONTAINS))
                        }
                    }
                }

                // Call edges: connect methods/functions in this file to callee targets
                val funcsInFile = summary.symbols.filter {
                    it.kind == SymbolKind.FUNCTION || it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR
                }
                for (call in summary.calls) {
                    val calleeNodeIds = nameToNodeIds[call.lowercase()] ?: emptyList()
                    for (calleeId in calleeNodeIds) {
                        for (callerSym in funcsInFile) {
                            val callerId = "${summary.filePath}#${callerSym.containerName.orEmpty()}#${callerSym.name}:${callerSym.kind.name}:${callerSym.line}"
                            edgesList.add(SymbolEdge(callerId, calleeId, SymbolRelationType.CALLS))
                        }
                    }
                }
            }

            return SymbolGraph(nodesMap, edgesList.distinct())
        }
    }
}
