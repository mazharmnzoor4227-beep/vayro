package com.vayro.app.agent.team

import com.vayro.app.agent.team.comm.TeamCommunicationBus
import com.vayro.app.agent.team.model.TeamMessage
import com.vayro.app.agent.team.model.TeamMessageType
import com.vayro.app.agent.team.model.TeamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeamCommunicationBusTest {

    private lateinit var bus: TeamCommunicationBus

    @Before
    fun setUp() {
        bus = TeamCommunicationBus()
    }

    @Test
    fun testSendDirectMessage() {
        val msg = bus.sendMessage(
            fromRole = TeamRole.ARCHITECT,
            toRole = TeamRole.DEVELOPER,
            type = TeamMessageType.PROPOSAL,
            content = "Use Repository pattern for Room database access.",
            taskId = "task-dev-1"
        )

        assertNotNull(msg.id)
        assertEquals(TeamRole.ARCHITECT, msg.fromRole)
        assertEquals(TeamRole.DEVELOPER, msg.toRole)
        assertEquals(TeamMessageType.PROPOSAL, msg.type)
        assertEquals(1, bus.getMessages().size)
    }

    @Test
    fun testBroadcastMessage() {
        val broadcast = bus.sendMessage(
            fromRole = TeamRole.MANAGER,
            toRole = null,
            type = TeamMessageType.BROADCAST,
            content = "Sprint 1 has started."
        )

        assertNull(broadcast.toRole)
        assertEquals(1, bus.getMessages().size)

        // Broadcast messages should be visible to any role query
        val devMessages = bus.getMessagesForRole(TeamRole.DEVELOPER)
        assertEquals(1, devMessages.size)
        assertEquals("Sprint 1 has started.", devMessages.first().content)
    }

    @Test
    fun testTaskThreadFiltering() {
        bus.sendMessage(
            fromRole = TeamRole.DEVELOPER,
            toRole = TeamRole.TESTER,
            type = TeamMessageType.HANDOFF,
            content = "Code merged. Ready for QA test suite.",
            taskId = "task-qa-1"
        )

        bus.sendMessage(
            fromRole = TeamRole.TESTER,
            toRole = TeamRole.DEVELOPER,
            type = TeamMessageType.FEEDBACK,
            content = "Found boundary test failure on empty list input.",
            taskId = "task-qa-1"
        )

        bus.sendMessage(
            fromRole = TeamRole.DEVOPS_ENGINEER,
            toRole = null,
            type = TeamMessageType.STATUS_UPDATE,
            content = "CI build runner initialized.",
            taskId = "task-ci-1"
        )

        val taskQaThread = bus.getThreadForTask("task-qa-1")
        assertEquals(2, taskQaThread.size)
    }

    @Test
    fun testEscalateToManager() {
        val escalation = bus.escalateToManager(
            fromRole = TeamRole.TESTER,
            reason = "Test environment missing database migration scripts.",
            taskId = "task-qa-1"
        )

        assertEquals(TeamRole.TESTER, escalation.fromRole)
        assertEquals(TeamRole.MANAGER, escalation.toRole)
        assertEquals(TeamMessageType.ESCALATION, escalation.type)
        assertTrue(escalation.content.contains("[ESCALATION"))
    }

    @Test
    fun testSubscriptionAndMarkAsRead() {
        var notifiedMessage: TeamMessage? = null
        val unsubscribe = bus.subscribe { msg ->
            notifiedMessage = msg
        }

        val msg = bus.sendMessage(
            fromRole = TeamRole.SECURITY_REVIEWER,
            toRole = TeamRole.DEVELOPER,
            content = "Sanitize SQL queries."
        )

        assertEquals(msg.id, notifiedMessage?.id)

        bus.markAsRead(msg.id)
        val readMsg = bus.getMessages().first { it.id == msg.id }
        assertTrue(readMsg.read)

        unsubscribe()
    }
}
