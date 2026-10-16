package com.vayro.app.agent.team.blackboard

import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-Safe Centralized Shared Knowledge Blackboard (Phase 11).
 * Serves as the single source of truth for all cross-role contracts, architecture records,
 * database schemas, API specs, and test reports.
 */
class TeamBlackboard {

    private val artifactsById = ConcurrentHashMap<String, TeamArtifact>()
    private val listeners = CopyOnWriteArrayList<(TeamArtifact) -> Unit>()

    /**
     * Publishes or updates an artifact on the blackboard.
     * Automatically increments the version if an artifact with matching title and type already exists.
     */
    fun publishArtifact(artifact: TeamArtifact): TeamArtifact {
        val existing = artifactsById.values.firstOrNull {
            it.title.equals(artifact.title, ignoreCase = true) && it.type == artifact.type
        }

        val finalized = if (existing != null) {
            artifact.copy(
                id = existing.id,
                version = existing.version + 1,
                timestamp = System.currentTimeMillis()
            )
        } else {
            artifact
        }

        artifactsById[finalized.id] = finalized
        notifyListeners(finalized)
        return finalized
    }

    fun getArtifact(id: String): TeamArtifact? = artifactsById[id]

    fun getLatestArtifact(title: String, type: ArtifactType): TeamArtifact? {
        return artifactsById.values
            .filter { it.title.equals(title, ignoreCase = true) && it.type == type }
            .maxByOrNull { it.version }
    }

    fun getArtifactsByType(type: ArtifactType): List<TeamArtifact> {
        return artifactsById.values
            .filter { it.type == type }
            .sortedByDescending { it.timestamp }
    }

    fun getArtifactsByTag(tag: String): List<TeamArtifact> {
        val lowerTag = tag.trim().lowercase()
        return artifactsById.values
            .filter { it.tags.any { t -> t.lowercase() == lowerTag } }
            .sortedByDescending { it.timestamp }
    }

    fun getArtifactsByRole(role: TeamRole): List<TeamArtifact> {
        return artifactsById.values
            .filter { it.authorRole == role }
            .sortedByDescending { it.timestamp }
    }

    fun getAllArtifacts(): List<TeamArtifact> {
        return artifactsById.values.sortedByDescending { it.timestamp }
    }

    fun searchArtifacts(query: String): List<TeamArtifact> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return getAllArtifacts()
        return artifactsById.values.filter {
            it.title.lowercase().contains(q) ||
                it.content.lowercase().contains(q) ||
                it.tags.any { tag -> tag.lowercase().contains(q) } ||
                it.authorRole.name.lowercase().contains(q) ||
                it.type.name.lowercase().contains(q)
        }.sortedByDescending { it.timestamp }
    }

    fun deleteArtifact(id: String): Boolean {
        return artifactsById.remove(id) != null
    }

    fun subscribe(listener: (TeamArtifact) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun clear() {
        artifactsById.clear()
    }

    private fun notifyListeners(artifact: TeamArtifact) {
        listeners.forEach { listener ->
            runCatching { listener(artifact) }
        }
    }
}
