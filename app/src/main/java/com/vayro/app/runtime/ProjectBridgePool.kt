package com.vayro.app.runtime

import com.vayro.app.model.AgentKind
import com.vayro.app.model.RuntimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ProjectBridgePool(
    private val bridgeFactory: (AgentKind) -> RuntimeBridge,
) {
    private data class Entry(val bridge: RuntimeBridge, val collector: Job)

    private val entries = ConcurrentHashMap<Pair<String, AgentKind>, Entry>()

    fun getOrCreate(
        projectId: String,
        kind: AgentKind,
        rootPath: String,
        scope: CoroutineScope,
        onEvent: (String, RuntimeEvent) -> Unit,
    ): RuntimeBridge {
        entries[projectId to kind]?.let { return it.bridge }
        val bridge = bridgeFactory(kind)
        if (rootPath.isNotBlank()) configureBridgeRoot(bridge, projectId, rootPath)
        val collector = scope.launch {
            bridge.events.collect { event -> onEvent(projectId, event) }
        }
        val entry = Entry(bridge, collector)
        val existing = entries.putIfAbsent(projectId to kind, entry)
        if (existing != null) {
            collector.cancel()
            return existing.bridge
        }
        return bridge
    }

    fun get(projectId: String, kind: AgentKind): RuntimeBridge? =
        entries[projectId to kind]?.bridge

    fun configureRoot(projectId: String, rootPath: String) {
        entries.forEach { (key, entry) ->
            if (key.first == projectId) configureBridgeRoot(entry.bridge, projectId, rootPath)
        }
    }

    fun release(projectId: String) {
        val removed = mutableListOf<Entry>()
        entries.entries.removeIf { (key, entry) ->
            (key.first == projectId).also { matched -> if (matched) removed.add(entry) }
        }
        removed.forEach { it.collector.cancel() }
    }

    private fun configureBridgeRoot(bridge: RuntimeBridge, projectId: String, rootPath: String) {
        when (bridge) {
            is ClaudeRuntimeBridge -> bridge.configureProjectRoot(projectId, rootPath)
            is DshRuntimeBridge -> bridge.configureProjectRoot(projectId, rootPath)
            is AntigravityRuntimeBridge -> bridge.configureProjectRoot(projectId, rootPath)
            is CodexRuntimeBridge -> bridge.configureProjectRoot(projectId, rootPath)
            else -> Unit
        }
    }
}
