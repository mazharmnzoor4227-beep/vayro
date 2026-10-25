package com.vayro.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Conversation-scoped (CHAT) grants: the fix for the launch-gate dialog
 * re-prompting on every task run. A task run gets a fresh sessionId, so
 * SESSION grants never survive it; a CHAT grant is keyed by project slug +
 * chat id instead, so it survives task runs inside the same chat, never
 * applies to another chat or project, and persists across restarts.
 */
class ChatGrantTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var policyDir: File
    private lateinit var engine: PermissionPolicyEngine

    @Before
    fun setUp() {
        policyDir = tempFolder.newFolder("security_policies")
        engine = PermissionPolicyEngine(
            policyDir = policyDir,
            auditLogger = SecurityAuditLogger(policyDir),
            commandDetector = DangerousCommandDetector()
        )
    }

    @Test
    fun `chat grant allows later run with fresh session id in same chat`() {
        val chatKey = chatGrantKey("beauty-salon", "chat-1")!!
        engine.grantPermission(
            category = PermissionCategory.SHELL_EXEC,
            level = PermissionLevel.CHAT,
            targetPattern = "/workspace/beauty-salon",
            projectId = "beauty-salon",
            chatId = chatKey
        )
        // Fresh task run: brand-new sessionId, same chat.
        val decision = engine.evaluatePermission(
            PermissionRequest(
                category = PermissionCategory.SHELL_EXEC,
                target = "/workspace/beauty-salon",
                sessionId = "run-2-fresh-session-id",
                projectId = "beauty-salon",
                chatId = chatKey
            )
        )
        assertTrue(decision.allowed)
        assertEquals(PermissionLevel.CHAT, decision.level)
    }

    @Test
    fun `chat grant does not apply to another chat in same project`() {
        val chatKey = chatGrantKey("beauty-salon", "chat-1")!!
        engine.grantPermission(
            category = PermissionCategory.FILE_DELETE,
            level = PermissionLevel.CHAT,
            targetPattern = "/workspace/beauty-salon",
            projectId = "beauty-salon",
            chatId = chatKey
        )
        val otherChatKey = chatGrantKey("beauty-salon", "chat-2")!!
        val decision = engine.evaluatePermission(
            PermissionRequest(
                category = PermissionCategory.FILE_DELETE,
                target = "/workspace/beauty-salon",
                sessionId = "some-session",
                projectId = "beauty-salon",
                chatId = otherChatKey
            )
        )
        // FILE_DELETE baseline requires a prompt; a chat-1 grant must not satisfy it.
        assertFalse(decision.allowed)
        assertTrue(decision.requiresPrompt)
    }

    @Test
    fun `chat grant does not apply to another project`() {
        val chatKey = chatGrantKey("beauty-salon", "chat-1")!!
        engine.grantPermission(
            category = PermissionCategory.FILE_DELETE,
            level = PermissionLevel.CHAT,
            targetPattern = "/workspace/beauty-salon",
            projectId = "beauty-salon",
            chatId = chatKey
        )
        val otherProjectKey = chatGrantKey("other-project", "chat-1")!!
        val decision = engine.evaluatePermission(
            PermissionRequest(
                category = PermissionCategory.FILE_DELETE,
                target = "/workspace/other-project",
                sessionId = "some-session",
                projectId = "other-project",
                chatId = otherProjectKey
            )
        )
        assertFalse(decision.allowed)
        assertTrue(decision.requiresPrompt)
    }

    @Test
    fun `chat grant persists across engine restarts`() {
        val chatKey = chatGrantKey("beauty-salon", "chat-1")!!
        engine.grantPermission(
            category = PermissionCategory.SHELL_EXEC,
            level = PermissionLevel.CHAT,
            targetPattern = "/workspace/beauty-salon",
            projectId = "beauty-salon",
            chatId = chatKey
        )
        val restarted = PermissionPolicyEngine(
            policyDir = policyDir,
            auditLogger = SecurityAuditLogger(policyDir),
            commandDetector = DangerousCommandDetector()
        )
        val decision = restarted.evaluatePermission(
            PermissionRequest(
                category = PermissionCategory.SHELL_EXEC,
                target = "/workspace/beauty-salon",
                sessionId = "run-after-restart",
                projectId = "beauty-salon",
                chatId = chatKey
            )
        )
        assertTrue(decision.allowed)
        assertEquals(PermissionLevel.CHAT, decision.level)
    }

    @Test
    fun `chat grant key rejects blank identity`() {
        assertNull(chatGrantKey("beauty-salon", null))
        assertNull(chatGrantKey("beauty-salon", ""))
        assertNull(chatGrantKey(null, "chat-1"))
        assertNull(chatGrantKey("", "chat-1"))
    }

    @Test
    fun `explicit deny wins over chat grant`() {
        val chatKey = chatGrantKey("beauty-salon", "chat-1")!!
        engine.grantPermission(
            category = PermissionCategory.FILE_DELETE,
            level = PermissionLevel.CHAT,
            targetPattern = "/workspace/beauty-salon",
            projectId = "beauty-salon",
            chatId = chatKey
        )
        engine.grantPermission(
            category = PermissionCategory.FILE_DELETE,
            level = PermissionLevel.DENY,
            projectId = "beauty-salon"
        )
        val decision = engine.evaluatePermission(
            PermissionRequest(
                category = PermissionCategory.FILE_DELETE,
                target = "/workspace/beauty-salon",
                sessionId = "some-session",
                projectId = "beauty-salon",
                chatId = chatKey
            )
        )
        assertFalse(decision.allowed)
    }

    @Test
    fun `kill switch blocks despite chat grant`() {
        val chatKey = chatGrantKey("beauty-salon", "chat-1")!!
        engine.grantPermission(
            category = PermissionCategory.SHELL_EXEC,
            level = PermissionLevel.CHAT,
            targetPattern = "/workspace/beauty-salon",
            projectId = "beauty-salon",
            chatId = chatKey
        )
        engine.engageEmergencyKillSwitch("test")
        val decision = engine.evaluatePermission(
            PermissionRequest(
                category = PermissionCategory.SHELL_EXEC,
                target = "/workspace/beauty-salon",
                sessionId = "some-session",
                projectId = "beauty-salon",
                chatId = chatKey
            )
        )
        assertFalse(decision.allowed)
    }

    @Test
    fun `clearing session permissions leaves chat grants intact`() {
        val chatKey = chatGrantKey("beauty-salon", "chat-1")!!
        engine.grantPermission(
            category = PermissionCategory.SHELL_EXEC,
            level = PermissionLevel.SESSION,
            targetPattern = "/workspace/beauty-salon",
            sessionId = "run-1"
        )
        engine.grantPermission(
            category = PermissionCategory.SHELL_EXEC,
            level = PermissionLevel.CHAT,
            targetPattern = "/workspace/beauty-salon",
            projectId = "beauty-salon",
            chatId = chatKey
        )
        engine.clearSessionPermissions("run-1")
        val decision = engine.evaluatePermission(
            PermissionRequest(
                category = PermissionCategory.SHELL_EXEC,
                target = "/workspace/beauty-salon",
                sessionId = "run-2",
                projectId = "beauty-salon",
                chatId = chatKey
            )
        )
        assertTrue(decision.allowed)
        assertEquals(PermissionLevel.CHAT, decision.level)
    }

    @Test
    fun `gateway allows via chat grant and reports its level`() {
        val gateway = SafetyGateway(engine)
        val operation = SafetyOperation.NetworkRequest("https://example.com")
        val chatKey = chatGrantKey("beauty-salon", "chat-1")!!
        gateway.recordGrant(operation, PermissionLevel.CHAT, chatId = chatKey)
        val allowed = gateway.authorize(operation, sessionId = "fresh-run", chatId = chatKey)
        assertEquals(SafetyVerdict.ALLOW, allowed.verdict)
        assertEquals(PermissionLevel.CHAT, allowed.level)
        // Another conversation gets no grant: the gateway must not report ALLOW-with-level.
        val otherKey = chatGrantKey("beauty-salon", "chat-2")!!
        val other = gateway.authorize(operation, sessionId = "fresh-run", chatId = otherKey)
        assertFalse(other.verdict == SafetyVerdict.ALLOW && other.level == PermissionLevel.CHAT)
    }
}