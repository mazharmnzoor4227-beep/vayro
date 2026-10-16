package com.vayro.app.agent.team.comm

import com.vayro.app.agent.team.model.TeamMessage
import com.vayro.app.agent.team.model.TeamMessageType
import com.vayro.app.agent.team.model.TeamRole
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Inter-Agent Communication Bus & Protocol (Phase 11).
 * Routes structured messages, proposal requests, feedback handoffs, and escalations
 * across the 10 engineering team agents.
 */
class TeamCommunicationBus {

    private val messages = CopyOnWriteArrayList<TeamMessage>()
    private val listeners = CopyOnWriteArrayList<(TeamMessage) -> Unit>()

    /**
     * Publishes a typed message to the bus.
     */
    fun sendMessage(
        fromRole: TeamRole,
        toRole: TeamRole? = null,
        type: TeamMessageType = TeamMessageType.STATUS_UPDATE,
        content: String,
        taskId: String? = null
    ): TeamMessage {
        val msg = TeamMessage(
            fromRole = fromRole,
            toRole = toRole,
            type = type,
            content = content,
            taskId = taskId,
            timestamp = System.currentTimeMillis()
        )
        messages.add(msg)
        notifyListeners(msg)
        return msg
    }

    /**
     * Helper to escalate an unresolvable issue or blocking dependency directly to Engineering Manager.
     */
    fun escalateToManager(fromRole: TeamRole, reason: String, taskId: String? = null): TeamMessage {
        return sendMessage(
            fromRole = fromRole,
            toRole = TeamRole.MANAGER,
            type = TeamMessageType.ESCALATION,
            content = "[ESCALATION from ${fromRole.title}]: $reason",
            taskId = taskId
        )
    }

    fun getMessages(): List<TeamMessage> = messages.toList()

    fun getMessagesForRole(role: TeamRole): List<TeamMessage> {
        return messages.filter {
            it.fromRole == role || it.toRole == role || it.toRole == null
        }
    }

    fun getThreadForTask(taskId: String): List<TeamMessage> {
        return messages.filter { it.taskId == taskId }
    }

    fun markAsRead(messageId: String) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val current = messages[idx]
            messages[idx] = current.copy(read = true)
        }
    }

    fun subscribe(listener: (TeamMessage) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun clear() {
        messages.clear()
    }

    private fun notifyListeners(msg: TeamMessage) {
        listeners.forEach { listener ->
            runCatching { listener(msg) }
        }
    }
}
