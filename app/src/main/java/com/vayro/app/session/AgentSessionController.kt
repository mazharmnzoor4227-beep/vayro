package com.vayro.app.session

import android.content.Context
import com.vayro.app.model.ActiveRoadmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AgentSessionDomainState(
    val activeSessions: Map<String, PersistedAgentSession> = emptyMap(),
    val interruptedSessions: List<PersistedAgentSession> = emptyList(),
    val sessionRoadmaps: Map<String, ActiveRoadmap?> = emptyMap(),
    val lastError: String? = null,
)

class AgentSessionController(
    private val context: Context,
    private val sessionManager: AgentSessionManager = AgentSessionManager(context),
) {
    private val _state = MutableStateFlow(AgentSessionDomainState())
    val state: StateFlow<AgentSessionDomainState> = _state.asStateFlow()

    init {
        val interrupted = sessionManager.detectInterruptedSessions()
        if (interrupted.isNotEmpty()) {
            _state.update { it.copy(interruptedSessions = interrupted) }
        }
    }

    fun startSession(
        sessionId: String,
        projectId: String,
        projectTitle: String,
        chatId: String,
        prompt: String,
        roadmap: ActiveRoadmap? = null,
    ) {
        sessionManager.recordSessionStart(
            sessionId = sessionId,
            projectId = projectId,
            projectTitle = projectTitle,
            chatId = chatId,
            prompt = prompt,
            roadmap = roadmap,
        )
        val session = PersistedAgentSession(
            sessionId = sessionId,
            projectId = projectId,
            projectTitle = projectTitle,
            chatId = chatId,
            initialPrompt = prompt,
            startedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            status = AgentSessionStatus.RUNNING,
            activeRoadmap = roadmap,
        )
        _state.update {
            it.copy(
                activeSessions = it.activeSessions + (sessionId to session),
                interruptedSessions = it.interruptedSessions.filterNot { s -> s.sessionId == sessionId },
                sessionRoadmaps = it.sessionRoadmaps + (sessionId to roadmap),
                lastError = null,
            )
        }
    }


    fun completeSession(sessionId: String, summary: String? = null) {
        sessionManager.recordSessionCompleted(sessionId, summary)
        _state.update {
            it.copy(
                activeSessions = it.activeSessions - sessionId,
                sessionRoadmaps = it.sessionRoadmaps - sessionId,
                lastError = null,
            )
        }
    }

    fun failSession(sessionId: String, reason: String) {
        sessionManager.recordSessionFailed(sessionId, reason)
        _state.update {
            it.copy(
                activeSessions = it.activeSessions - sessionId,
                sessionRoadmaps = it.sessionRoadmaps - sessionId,
                lastError = reason,
            )
        }
    }

    fun cancelSession(sessionId: String) {
        sessionManager.recordSessionCancelled(sessionId)
        _state.update {
            it.copy(
                activeSessions = it.activeSessions - sessionId,
                sessionRoadmaps = it.sessionRoadmaps - sessionId,
                lastError = "Cancelled by user",
            )
        }
    }


    fun dismissInterruptedSession(sessionId: String) {
        sessionManager.clearActiveSession(sessionId)
        _state.update { it.copy(interruptedSessions = it.interruptedSessions.filterNot { s -> s.sessionId == sessionId }) }
    }

    fun sessionProjectId(sessionId: String): String? =
        _state.value.activeSessions[sessionId]?.projectId
            ?: _state.value.interruptedSessions.firstOrNull { it.sessionId == sessionId }?.projectId


    fun cleanupOldSessions(days: Int = 14): Int = sessionManager.cleanupOldSessions(days)
}
