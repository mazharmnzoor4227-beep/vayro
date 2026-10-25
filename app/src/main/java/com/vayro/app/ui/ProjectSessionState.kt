package com.vayro.app.ui

import com.vayro.app.model.ActivityItem
import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.ChangeItem
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ProjectChat
import com.vayro.app.model.ScratchpadItem
import com.vayro.app.model.ToolRequest
import com.vayro.app.session.EngineeringState
import com.vayro.app.session.OrchestratorSnapshot
import com.vayro.app.session.PersistedAgentSession

data class TaskOutcome(
    val state: EngineeringState,
    val title: String,
    val finishedAtMillis: Long,
)

fun EngineeringState.isTerminalBannerState(): Boolean =
    this == EngineeringState.COMPLETED ||
        this == EngineeringState.FAILED ||
        this == EngineeringState.ROLLED_BACK

private fun EngineeringState.isWorkingBannerState(): Boolean =
    this != EngineeringState.IDLE &&
        this != EngineeringState.ANALYZING &&
        !isTerminalBannerState()

/**
 * Stable identity keys for the completion event the banner would currently
 * show, or an empty set when no completion event is on display (a live,
 * still-working state, or nothing to show).
 *
 * One completion produces several keys (stored outcome, shared finish
 * timestamp, latched live snapshot) because the same event is visible
 * through more than one source: dismissing the banner acknowledges ALL of
 * them, so the event can never resurface through a different source after
 * it has been dismissed — not on scroll, tab switch, session reopen, or
 * (the keys are persisted) app restart. A later task finishes with
 * different timestamps, produces different keys, and shows normally.
 */
fun completionEventKeys(
    live: OrchestratorSnapshot?,
    outcome: TaskOutcome?,
    taskFinishedAtMillis: Long?,
): Set<String> {
    if (live != null && live.state.isWorkingBannerState()) return emptySet()
    val keys = LinkedHashSet<String>()
    if (outcome != null) {
        keys += "outcome:${outcome.state}:${outcome.finishedAtMillis}"
        if (taskFinishedAtMillis != null) keys += "finished:${outcome.state}:$taskFinishedAtMillis"
    }
    if (live != null && live.state.isTerminalBannerState()) {
        val historyTs = live.history.lastOrNull { it.state == live.state }?.timestampMillis
            ?: live.history.lastOrNull()?.timestampMillis
        keys += "live:${live.state}:${historyTs ?: 0L}:${live.currentTaskGoal ?: ""}"
        if (taskFinishedAtMillis != null) keys += "finished:${live.state}:$taskFinishedAtMillis"
    }
    return keys
}

fun bannerSnapshotFor(
    live: OrchestratorSnapshot?,
    outcome: TaskOutcome?,
    taskFinishedAtMillis: Long? = null,
    acknowledgedEventKeys: Set<String> = emptySet(),
): OrchestratorSnapshot? {
    // A live, still-working state always wins: the banner is the progress UI.
    if (live != null && live.state.isWorkingBannerState()) {
        return live
    }
    // A completion event shows unless it was already dismissed/acknowledged.
    // The stored outcome is preferred over a latched terminal live snapshot:
    // both describe the same finished task, and only the outcome carries the
    // canonical finish timestamp the auto-dismissal is keyed to.
    val eventKeys = completionEventKeys(live, outcome, taskFinishedAtMillis)
    if (eventKeys.isNotEmpty() && eventKeys.none { it in acknowledgedEventKeys }) {
        if (outcome != null) {
            return OrchestratorSnapshot(
                state = outcome.state,
                currentIteration = 0,
                maxIterations = 0,
                planSteps = emptyList(),
                currentStepIndex = 0,
                history = emptyList(),
                currentTaskGoal = outcome.title,
            )
        }
        if (live != null && live.state.isTerminalBannerState()) return live
    }
    // No unacknowledged completion event: only a non-terminal live snapshot
    // may show (IDLE/ANALYZING render nothing in the banner composable). A
    // terminal live snapshot whose event was acknowledged must NOT fall
    // through to here — that was the pinning bug.
    return if (live != null && !live.state.isTerminalBannerState()) live else null
}

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
    val lastTaskOutcome: TaskOutcome? = null,
)
