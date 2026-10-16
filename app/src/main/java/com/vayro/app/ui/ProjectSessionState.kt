package com.vayro.app.ui

import com.vayro.app.model.ActivityItem
import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.ChangeItem
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ProjectChat
import com.vayro.app.model.ScratchpadItem
import com.vayro.app.model.ToolRequest
import com.vayro.app.session.EngineeringState
import com.vayro.app.session.PersistedAgentSession

data class OrchestratorTransition(
    val state: EngineeringState,
    val stepTitle: String,
    val details: String? = null,
)

data class ProjectSessionState(
    val projectId: String,
    val messages: List<ChatMessage> = emptyList(),
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val liveThinking: Boolean = false,
    val activeThinkingBlockId: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskFinishedAtMillis: Long? = null,
    val workSegmentStartedAtMillis: Long? = null,
    val currentTaskRequest: String? = null,
    val previewReady: Boolean = false,
    val previewUrl: String? = null,
    val isRunning: Boolean = false,
    val streamingMessageId: String? = null,
    val activeSessionId: String? = null,
    val interruptedSession: PersistedAgentSession? = null,
    val runtimeRecoveryStatus: String? = null,
    val runtimeRecoveryAttempt: Int = 0,
    val runtimeRecoveryCanResume: Boolean = false,
    val activeRoadmap: ActiveRoadmap? = null,
    val scratchpadItems: List<ScratchpadItem> = emptyList(),
    val pendingOrchestratorTransition: OrchestratorTransition? = null,
)
