package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexRuntimeBridgeTest {
    @Test
    fun `thread started exposes thread id`() {
        val event = CodexEventParser.parse(
            """{"type":"thread.started","thread_id":"thread-1"}""",
        )
        assertEquals(CodexParsedEvent.ThreadStarted("thread-1"), event)
    }

    @Test
    fun `parses completed agent message text`() {
        val parsed = CodexEventParser.parse(
            """{"type":"item.completed","item":{"id":"m1","type":"agent_message","text":"hello from codex"}}""",
        )
        assertEquals(CodexParsedEvent.Text("hello from codex"), parsed)
    }

    @Test
    fun `item updated is ignored so message text is never duplicated`() {
        assertNull(
            CodexEventParser.parse(
                """{"type":"item.updated","item":{"id":"m1","type":"agent_message","text":"hello"}}""",
            ),
        )
    }

    @Test
    fun `parses started and completed command execution`() {
        assertEquals(
            CodexParsedEvent.ToolStarted("Bash", "npm test"),
            CodexEventParser.parse(
                """{"type":"item.started","item":{"id":"c1","type":"command_execution","command":"npm test"}}""",
            ),
        )
        assertEquals(
            CodexParsedEvent.ToolCompleted("Bash", "npm test"),
            CodexEventParser.parse(
                """{"type":"item.completed","item":{"id":"c1","type":"command_execution","command":"npm test"}}""",
            ),
        )
    }

    @Test
    fun `parses file change with first changed path`() {
        val parsed = CodexEventParser.parse(
            """{"type":"item.completed","item":{"id":"f1","type":"file_change","changes":[{"path":"/workspace/app/main.py","kind":"update"}]}}""",
        )
        assertEquals(CodexParsedEvent.ToolCompleted("Write", "/workspace/app/main.py"), parsed)
    }

    @Test
    fun `parses turn completed`() {
        val parsed = CodexEventParser.parse(
            """{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":20}}""",
        )
        assertEquals(CodexParsedEvent.TurnCompleted, parsed)
    }

    @Test
    fun `parses turn failed with object error`() {
        val parsed = CodexEventParser.parse(
            """{"type":"turn.failed","error":{"message":"rate limited"}}""",
        )
        assertEquals(CodexParsedEvent.TurnFailed("rate limited"), parsed)
    }

    @Test
    fun `parses turn failed with string error`() {
        val parsed = CodexEventParser.parse(
            """{"type":"turn.failed","error":"boom"}""",
        )
        assertEquals(CodexParsedEvent.TurnFailed("boom"), parsed)
    }

    @Test
    fun `parses top level error event`() {
        val parsed = CodexEventParser.parse(
            """{"type":"error","message":"something broke"}""",
        )
        assertEquals(CodexParsedEvent.TurnFailed("something broke"), parsed)
    }

    @Test
    fun `ignores unknown and malformed events`() {
        assertNull(CodexEventParser.parse("not json"))
        assertNull(CodexEventParser.parse("""{"type":"future_event","payload":{}}"""))
        assertNull(CodexEventParser.parse("""{"type":"turn.started"}"""))
    }

    @Test
    fun `headless command uses json mode, workspace dir, model and prompt`() {
        val command = codexCommand("gpt-5-codex", "/workspace/bold-kalam", "Create hello.py")
        assertTrue(
            command.containsAll(
                listOf(
                    RuntimeInstaller.CODEX_GUEST_PATH,
                    "exec",
                    "--json",
                    "-C", "/workspace/bold-kalam",
                    "-m", "gpt-5-codex",
                    "Create hello.py",
                ),
            ),
        )
    }

    @Test
    fun `headless command omits model flag when model is blank`() {
        val command = codexCommand("", "/workspace/bold-kalam", "Create hello.py")
        assertTrue("-m" !in command)
        assertTrue("Create hello.py" in command)
    }

    @Test
    fun `workspace prompt keeps generated files in the mounted project`() {
        val prompt = codexWorkspacePrompt("bold-kalam", "Create hello.py")
        assertTrue("/workspace/bold-kalam" in prompt)
        assertTrue("only inside this directory" in prompt)
        assertTrue(prompt.endsWith("Create hello.py"))
    }
}
