package com.vayro.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5: authorization prompts must be precise — naming the exact operation and
 * target, the risk, what will happen, and the available grant scopes.
 */
class AuthorizationPromptTest {

    @Test
    fun `file delete prompt names the exact target and warns it is irreversible`() {
        val prompt = AuthorizationPrompt.forOperation(
            SafetyOperation.FileOp(FileOpKind.DELETE, "/projects/demo/notes.txt")
        )
        val text = prompt.renderText()
        assertTrue(text.contains("/projects/demo/notes.txt"))
        assertTrue(text.contains("delete", ignoreCase = true))
        assertTrue(text.contains("cannot be undone", ignoreCase = true))
        assertEquals(PromptRisk.HIGH, prompt.risk)
    }

    @Test
    fun `shell prompt names the exact command and working dir`() {
        val prompt = AuthorizationPrompt.forOperation(
            SafetyOperation.ShellExec("rm -rf /tmp/cache", "/projects/demo")
        )
        val text = prompt.renderText()
        assertTrue(text.contains("rm -rf /tmp/cache"))
        assertTrue(text.contains("/projects/demo"))
    }

    @Test
    fun `critical command prompt carries critical risk`() {
        val prompt = AuthorizationPrompt.forOperation(
            SafetyOperation.ShellExec("rm -rf /")
        )
        assertEquals(PromptRisk.CRITICAL, prompt.risk)
        assertTrue(prompt.renderText().contains("Critical"))
    }

    @Test
    fun `git push prompt names repo and remote`() {
        val prompt = AuthorizationPrompt.forOperation(
            SafetyOperation.GitOp(GitOpKind.PUSH, "/projects/demo", "https://git.example/repo.git")
        )
        val text = prompt.renderText()
        assertTrue(text.contains("/projects/demo"))
        assertTrue(text.contains("https://git.example/repo.git"))
    }

    @Test
    fun `mcp tool prompt names server and tool`() {
        val prompt = AuthorizationPrompt.forOperation(
            SafetyOperation.McpToolCall("fs-server", "read_file")
        )
        val text = prompt.renderText()
        assertTrue(text.contains("fs-server"))
        assertTrue(text.contains("read_file"))
    }

    @Test
    fun `apk install prompt warns about device capabilities`() {
        val prompt = AuthorizationPrompt.forOperation(
            SafetyOperation.ApkInstall("/tmp/app.apk", "com.example.app")
        )
        val text = prompt.renderText()
        assertTrue(text.contains("/tmp/app.apk"))
        assertTrue(text.contains("device", ignoreCase = true))
        assertEquals(PromptRisk.HIGH, prompt.risk)
    }

    @Test
    fun `low risk operations offer all scopes, high risk only narrow scopes`() {
        val low = AuthorizationPrompt.forOperation(
            SafetyOperation.FileOp(FileOpKind.READ, "a.txt")
        )
        assertTrue(low.scopes.contains(PermissionLevel.ALWAYS))

        val high = AuthorizationPrompt.forOperation(
            SafetyOperation.FileOp(FileOpKind.DELETE, "a.txt")
        )
        assertFalse(high.scopes.contains(PermissionLevel.ALWAYS))
        assertFalse(high.scopes.contains(PermissionLevel.PROJECT))
        assertTrue(high.scopes.contains(PermissionLevel.ONCE))
        assertTrue(high.scopes.contains(PermissionLevel.SESSION))
    }

    @Test
    fun `prompt always includes operation target and grant scopes`() {
        val ops = listOf<SafetyOperation>(
            SafetyOperation.FileOp(FileOpKind.WRITE, "x.txt"),
            SafetyOperation.NetworkRequest("https://example.com", "POST"),
            SafetyOperation.RemoteExec("worker-1", "ls")
        )
        for (op in ops) {
            val prompt = AuthorizationPrompt.forOperation(op)
            val text = prompt.renderText()
            assertNotNull(prompt.title)
            assertTrue("prompt text must describe the operation", text.contains(op.describe().substringBefore(":").take(12)))
            assertTrue("prompt text must list scopes", text.contains("Grant for:"))
            assertTrue(prompt.scopes.isNotEmpty())
        }
    }
}
