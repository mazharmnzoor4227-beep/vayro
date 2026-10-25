package com.vayro.app.runtime

import com.vayro.app.model.RiskLevel
import com.vayro.app.model.RuntimeEvent
import com.vayro.app.model.ToolRequest
import com.vayro.app.security.PermissionLevel
import com.vayro.app.security.SafetyGateway
import com.vayro.app.security.SafetyOperation
import com.vayro.app.security.SafetyVerdict
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

internal class FullAccessLaunchGate(
    private val safetyGateway: SafetyGateway,
) {
    private data class PendingLaunch(
        val operation: SafetyOperation,
        val sessionId: String,
        val projectId: String?,
        /** Conversation key (project slug + chat id) or null when unknown. */
        val chatId: String?,
        val gate: CompletableDeferred<Boolean>,
    )

    private val pending = ConcurrentHashMap<String, PendingLaunch>()

    suspend fun authorizeLaunch(
        agentLabel: String,
        sessionId: String,
        projectId: String?,
        operation: SafetyOperation,
        explanation: String,
        affectedPaths: List<String>,
        interactive: Boolean,
        emit: suspend (RuntimeEvent) -> Unit,
        chatId: String? = null,
    ): Boolean {
        val decision = safetyGateway.authorize(operation, sessionId = sessionId, projectId = projectId, chatId = chatId)
        if (decision.verdict == SafetyVerdict.DENY) {
            emit(RuntimeEvent.SessionFailed(sessionId, "$agentLabel launch refused: ${decision.reason}"))
            return false
        }
        if (decision.verdict == SafetyVerdict.ALLOW && decision.level != null) {
            return true
        }
        if (!interactive) {
            emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "$agentLabel runs with full access and needs your acknowledgment. Launch it from the main chat instead.",
                ),
            )
            return false
        }
        val request = ToolRequest(
            sessionId = sessionId,
            toolName = agentLabel,
            explanation = explanation,
            affectedPaths = affectedPaths,
            commandPreview = operation.describe(),
            risk = RiskLevel.HIGH,
        )
        val gate = CompletableDeferred<Boolean>()
        pending[request.approvalId] = PendingLaunch(operation, sessionId, projectId, chatId, gate)
        emit(RuntimeEvent.ToolRequested(sessionId, request))
        val approved = try {
            gate.await()
        } finally {
            pending.remove(request.approvalId)
        }
        emit(
            if (approved) RuntimeEvent.ToolApproved(sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(sessionId, request.approvalId),
        )
        if (!approved) {
            emit(RuntimeEvent.SessionFailed(sessionId, "$agentLabel launch was not approved."))
        }
        return approved
    }

    /**
     * Completes a pending launch. The recorded grant keeps the level the
     * user actually picked: a Chat approval records a CHAT grant (it must
     * NOT be downgraded to a plain SESSION grant, or the next task run in
     * the same conversation would prompt again). Defaults to SESSION for
     * callers that do not carry a level (e.g. other bridges' tool paths).
     */
    fun respondToApproval(approvalId: String, approved: Boolean, level: PermissionLevel = PermissionLevel.SESSION): Boolean {
        val launch = pending.remove(approvalId) ?: return false
        if (approved) {
            safetyGateway.recordGrant(
                launch.operation,
                level,
                sessionId = launch.sessionId,
                projectId = launch.projectId,
                chatId = launch.chatId,
            )
        }
        launch.gate.complete(approved)
        return true
    }

    fun abortPending() {
        val stale = pending.values.toList()
        pending.clear()
        stale.forEach { it.gate.complete(false) }
    }
}
