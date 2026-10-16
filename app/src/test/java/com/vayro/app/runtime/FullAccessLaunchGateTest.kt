package com.vayro.app.runtime

import com.vayro.app.model.RuntimeEvent
import com.vayro.app.security.PermissionCategory
import com.vayro.app.security.PermissionLevel
import com.vayro.app.security.PermissionPolicyEngine
import com.vayro.app.security.SafetyGateway
import com.vayro.app.security.SafetyOperation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FullAccessLaunchGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var policyEngine: PermissionPolicyEngine
    private lateinit var gateway: SafetyGateway

    @Before
    fun setUp() {
        policyEngine = PermissionPolicyEngine(policyDir = tempFolder.newFolder("security"))
        gateway = SafetyGateway(
            policyEngine = policyEngine,
            workspaceRoot = null,
            workspaceRootResolver = { java.io.File("/workspace/proj") }
        )
    }

    private fun op() = SafetyOperation.ShellExec("agent-launch-probe", "/workspace/proj")

    private fun snapshot(events: MutableList<RuntimeEvent>): List<RuntimeEvent> =
        synchronized(events) { events.toList() }

    private suspend fun awaitPrompt(events: MutableList<RuntimeEvent>): RuntimeEvent.ToolRequested =
        withTimeout(10_000) {
            var result: RuntimeEvent.ToolRequested? = null
            while (result == null) {
                result = snapshot(events).filterIsInstance<RuntimeEvent.ToolRequested>().firstOrNull()
                if (result == null) delay(25)
            }
            result
        }

    @Test
    fun killSwitchDeniesLaunchWithoutPrompt() = runBlocking {
        policyEngine.engageEmergencyKillSwitch("test")
        val events = java.util.Collections.synchronizedList(mutableListOf<RuntimeEvent>())
        val gate = FullAccessLaunchGate(gateway)
        val ok = gate.authorizeLaunch(
            agentLabel = "DeepSeek Harness",
            sessionId = "s-kill",
            projectId = "p1",
            operation = op(),
            explanation = "expl",
            affectedPaths = listOf("/workspace/proj"),
            interactive = true,
            emit = { events.add(it) },
        )
        assertFalse(ok)
        assertTrue(snapshot(events).none { it is RuntimeEvent.ToolRequested })
        val failed = snapshot(events).filterIsInstance<RuntimeEvent.SessionFailed>().single()
        assertTrue(failed.reason.contains("kill switch", ignoreCase = true))
    }

    @Test
    fun interactiveLaunchPromptsAndApprovalIsSessionScoped() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<RuntimeEvent>())
        val gate = FullAccessLaunchGate(gateway)
        var first: Boolean? = null
        val job = launch {
            first = gate.authorizeLaunch(
                agentLabel = "Antigravity CLI",
                sessionId = "s-approve",
                projectId = "p1",
                operation = op(),
                explanation = "expl",
                affectedPaths = listOf("/workspace/proj"),
                interactive = true,
                emit = { events.add(it) },
            )
        }
        val requested = awaitPrompt(events)
        assertEquals("s-approve", requested.sessionId)
        assertTrue(gate.respondToApproval(requested.request.approvalId, true))
        job.join()
        assertEquals(true, first)
        assertTrue(snapshot(events).any { it is RuntimeEvent.ToolApproved })
        val second = gate.authorizeLaunch(
            agentLabel = "Antigravity CLI",
            sessionId = "s-approve",
            projectId = "p1",
            operation = op(),
            explanation = "expl",
            affectedPaths = listOf("/workspace/proj"),
            interactive = true,
            emit = { events.add(it) },
        )
        assertTrue(second)
        assertEquals(1, snapshot(events).filterIsInstance<RuntimeEvent.ToolRequested>().size)
    }

    @Test
    fun rejectedLaunchFailsSession() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<RuntimeEvent>())
        val gate = FullAccessLaunchGate(gateway)
        var result: Boolean? = null
        val job = launch {
            result = gate.authorizeLaunch(
                agentLabel = "DeepSeek Harness",
                sessionId = "s-reject",
                projectId = "p1",
                operation = op(),
                explanation = "expl",
                affectedPaths = listOf("/workspace/proj"),
                interactive = true,
                emit = { events.add(it) },
            )
        }
        val requested = awaitPrompt(events)
        assertTrue(gate.respondToApproval(requested.request.approvalId, false))
        job.join()
        assertEquals(false, result)
        assertTrue(snapshot(events).any { it is RuntimeEvent.ToolRejected })
        assertTrue(snapshot(events).any { it is RuntimeEvent.SessionFailed })
    }

    @Test
    fun nonInteractiveLaunchIsDeniedWithoutPrompt() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<RuntimeEvent>())
        val gate = FullAccessLaunchGate(gateway)
        val ok = gate.authorizeLaunch(
            agentLabel = "Antigravity CLI",
            sessionId = "s-team",
            projectId = "p1",
            operation = op(),
            explanation = "expl",
            affectedPaths = listOf("/workspace/proj"),
            interactive = false,
            emit = { events.add(it) },
        )
        assertFalse(ok)
        assertTrue(snapshot(events).none { it is RuntimeEvent.ToolRequested })
        val failed = snapshot(events).filterIsInstance<RuntimeEvent.SessionFailed>().single()
        assertTrue(failed.reason.contains("acknowledgment", ignoreCase = true))
    }

    @Test
    fun explicitDenyPolicyDeniesLaunchWithoutPrompt() = runBlocking {
        policyEngine.grantPermission(PermissionCategory.SHELL_EXEC, PermissionLevel.DENY)
        val events = java.util.Collections.synchronizedList(mutableListOf<RuntimeEvent>())
        val gate = FullAccessLaunchGate(gateway)
        val ok = gate.authorizeLaunch(
            agentLabel = "DeepSeek Harness",
            sessionId = "s-deny",
            projectId = "p1",
            operation = op(),
            explanation = "expl",
            affectedPaths = listOf("/workspace/proj"),
            interactive = true,
            emit = { events.add(it) },
        )
        assertFalse(ok)
        assertTrue(snapshot(events).none { it is RuntimeEvent.ToolRequested })
        assertTrue(snapshot(events).any { it is RuntimeEvent.SessionFailed })
    }

    @Test
    fun unknownApprovalIdIsIgnored() {
        val gate = FullAccessLaunchGate(gateway)
        assertFalse(gate.respondToApproval("no-such-id", true))
    }

    @Test
    fun stopAbortsPendingApproval() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<RuntimeEvent>())
        val gate = FullAccessLaunchGate(gateway)
        var result: Boolean? = null
        val job = launch {
            result = gate.authorizeLaunch(
                agentLabel = "Antigravity CLI",
                sessionId = "s-stop",
                projectId = "p1",
                operation = op(),
                explanation = "expl",
                affectedPaths = listOf("/workspace/proj"),
                interactive = true,
                emit = { events.add(it) },
            )
        }
        awaitPrompt(events)
        gate.abortPending()
        job.join()
        assertEquals(false, result)
        assertTrue(snapshot(events).any { it is RuntimeEvent.ToolRejected })
    }
}
