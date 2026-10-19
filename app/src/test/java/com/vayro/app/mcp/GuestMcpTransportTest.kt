package com.vayro.app.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v3.2.39 (Scope 1): the guest MCP transport, proven at JVM level.
 *
 * The real spawn goes through the native proot launcher, which only
 * exists on the device — so here a fake [Process] stands in for the
 * guest child: the session's NDJSON goes into the fake's stdin pipe,
 * and the fake "server" answers by APPENDING to the stdout file,
 * exactly like the native spawn's output file behaves. This exercises
 * the real [GuestMcpManagedProcess] + [FileTailLineReader] + the real
 * [McpServerSession] over them: handshake, tools/list, tools/call,
 * stderr separation and stop.
 */
class GuestMcpTransportTest {

    /** A fake guest child: consumes NDJSON on stdin, appends answers to the stdout file. */
    private class FakeGuestProcess(
        private val stdoutFile: File,
        private val responders: Map<String, (JSONObject) -> String>,
    ) : Process() {
        @Volatile
        var alive: Boolean = true

        private var carry = ""

        val stdin: OutputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                val text = synchronized(this) {
                    toString(Charsets.UTF_8.name()).also { reset() }
                }
                // Keep any partial line for the next flush; answer the rest.
                val parts = (carry + text).split('\n')
                carry = parts.last()
                parts.dropLast(1).forEach { line -> answer(line.trimEnd('\r')) }
            }
        }

        private fun answer(line: String) {
            val req = runCatching { JSONObject(line) }.getOrNull() ?: return
            if (req.isNull("id")) return // notification: no response
            val responder = responders[req.optString("method", "")] ?: return
            synchronized(stdoutFile) {
                FileOutputStream(stdoutFile, true).use { out ->
                    out.write((responder(req) + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int {
            while (alive) Thread.sleep(10)
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (alive && System.nanoTime() < deadline) Thread.sleep(5)
            return !alive
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    @Test
    fun `session handshake tools and call run over the guest transport`() {
        val dir = Files.createTempDirectory("guest-mcp").toFile()
        val stdoutFile = File(dir, "mcp-stdout.log").apply { createNewFile() }
        val stderrFile = File(dir, "stderr.log").apply { writeText("fake server warning\n") }
        val process = FakeGuestProcess(stdoutFile, mcpHandshakeResponders())
        val managed = GuestMcpManagedProcess(process, stdoutFile, stderrFile)
        val factory = object : McpProcessFactory {
            override fun start(
                command: String,
                args: List<String>,
                env: Map<String, String>,
                workingDir: File?,
            ): McpManagedProcess = managed
        }
        val session = McpServerSession(
            testStdioServer(),
            factory,
            ioTimeoutMs = 10_000,
            restartBackoffMs = 0,
        )

        assertTrue(session.start(emptyMap()))
        assertEquals(McpSessionState.RUNNING, session.state)

        val tools = session.listTools()
        assertEquals(listOf("echo"), tools.map { it.name })

        val outcome = session.callTool("echo", JSONObject().put("text", "hi"))
        assertFalse(outcome.isError)
        assertEquals("echo-ok", outcome.text)

        // stderr stayed OFF the protocol stream and is available as
        // diagnostics (tailed from the separate stderr file).
        assertTrue(awaitStderr(managed, "fake server warning"))

        session.stop()
        assertFalse("stop must terminate the guest process", process.alive)
    }

    /** drainStderr consumes the ring, so poll until the line shows up. */
    private fun awaitStderr(managed: McpManagedProcess, needle: String, timeoutMs: Long = 5_000): Boolean {
        val seen = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            seen += managed.drainStderr()
            if (needle in seen) return true
            Thread.sleep(50)
        }
        return needle in seen
    }

    @Test
    fun `tail reader joins split writes decodes split utf8 and flushes tail on death`() {
        val dir = Files.createTempDirectory("tail").toFile()
        val file = File(dir, "out.log").apply { createNewFile() }
        val lines = ConcurrentLinkedQueue<String>()
        val alive = AtomicBoolean(true)
        val reader = FileTailLineReader(file, { alive.get() }) { lines.add(it) }
        val thread = Thread(reader, "test-tail").apply { isDaemon = true; start() }

        file.appendBytes("hel".toByteArray(Charsets.UTF_8))
        Thread.sleep(250)
        file.appendBytes("lo\r\nwor".toByteArray(Charsets.UTF_8))
        Thread.sleep(250)
        file.appendBytes("ld\ncaf".toByteArray(Charsets.UTF_8))
        // 'é' is two bytes (0xC3 0xA9) — split across the two writes.
        file.appendBytes(byteArrayOf(0xC3.toByte()))
        Thread.sleep(250)
        file.appendBytes(byteArrayOf(0xA9.toByte()))
        file.appendBytes("\ntail-without-newline".toByteArray(Charsets.UTF_8))
        Thread.sleep(250)

        alive.set(false)
        thread.join(10_000)
        assertFalse(thread.isAlive)
        assertEquals(
            listOf("hello", "world", "café", "tail-without-newline"),
            lines.toList(),
        )
    }

    @Test
    fun `runner problem maps runners to runtime components`() {
        // uvx missing + no Python → the plain component message.
        val pythonMsg = mcpRunnerProblem("uvx", programFound = false, hasPython = false, hasNode = false)
        assertNotNull(pythonMsg)
        assertTrue(pythonMsg!!.contains("Python component"))
        assertTrue(pythonMsg.contains("Environment updates"))
        // uvx missing + Python present → the wrapper bootstraps, no problem.
        assertNull(mcpRunnerProblem("uvx", programFound = false, hasPython = true, hasNode = false))
        // npx missing → Node.js / Core runtime message.
        val nodeMsg = mcpRunnerProblem("npx", programFound = false, hasPython = true, hasNode = false)
        assertNotNull(nodeMsg)
        assertTrue(nodeMsg!!.contains("Node.js"))
        assertTrue(nodeMsg.contains("Core runtime"))
        // Unknown program → the message names the program.
        val unknown = mcpRunnerProblem("weirdbin", programFound = false, hasPython = true, hasNode = true)
        assertNotNull(unknown)
        assertTrue(unknown!!.contains("weirdbin"))
        // Found → no problem regardless.
        assertNull(mcpRunnerProblem("uvx", programFound = true, hasPython = false, hasNode = false))
        // Component mapping used in messages.
        assertEquals("Python", mcpRunnerComponent("uvx"))
        assertTrue(mcpRunnerComponent("npx")!!.contains("Node.js"))
        assertNull(mcpRunnerComponent("ruby"))
    }

    // ------------------------------------------------------------------
    // v3.2.40 (Scope C): device-failure hardening
    // ------------------------------------------------------------------

    @Test
    fun `spawn environment forces unbuffered python output`() {
        // The device root cause: a Python server whose stdout is a file
        // block-buffers its initialize answer past the handshake
        // deadline. The spawn env must force unbuffered output even
        // when the server's own config says otherwise.
        val env = mcpSpawnEnvironment(mapOf("FOO" to "bar", "PYTHONUNBUFFERED" to "0"))
        assertEquals("1", env["PYTHONUNBUFFERED"])
        assertEquals("utf-8", env["PYTHONIOENCODING"])
        assertEquals("bar", env["FOO"])
    }

    @Test
    fun `guest wrapper and probe are POSIX sh`() {
        for (script in listOf(MCP_GUEST_WRAPPER_SCRIPT, MCP_PROBE_SCRIPT)) {
            assertFalse("bashism [[ in: $script", script.contains("[["))
            assertFalse("bashism ]] in: $script", script.contains("]]"))
            assertFalse("bashism function in: $script", script.contains("function "))
            assertFalse("bashism <<< in: $script", script.contains("<<<"))
        }
        // stderr is redirected BEFORE the server execs, so the protocol
        // stream can never carry stderr chatter.
        val redirectAt = MCP_GUEST_WRAPPER_SCRIPT.indexOf("exec 2>")
        val serverAt = MCP_GUEST_WRAPPER_SCRIPT.indexOf("exec \"\$prog\"")
        assertTrue(redirectAt >= 0 && serverAt > redirectAt)
        // The probe sees a bootstrapped runner where the wrapper does.
        assertTrue(MCP_PROBE_SCRIPT.contains("\$HOME/.local/bin/"))
    }

    /** A scripted process: silent stdout for a while, stderr that grows (a download), then the answer. */
    private class ScriptedProcess(
        private val nullReadsBeforeAnswer: Int,
        private val stderrLines: List<String>,
        private val alive: Boolean = true,
    ) : McpManagedProcess {
        private var reads = 0
        private var stderrIndex = 0

        @Volatile
        private var initializeRequest: JSONObject? = null

        override fun writeLine(line: String) {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return
            if (json.optString("method") == "initialize") initializeRequest = json
        }

        override fun readLine(timeoutMs: Long): String? {
            reads++
            if (reads <= nullReadsBeforeAnswer) {
                Thread.sleep(25)
                return null
            }
            val req = initializeRequest ?: return null
            return testRpcOk(
                req,
                JSONObject()
                    .put("protocolVersion", McpProtocolVersion.PROTOCOL_VERSION)
                    .put("capabilities", JSONObject())
                    .put("serverInfo", JSONObject().put("name", "fake").put("version", "0.1")),
            )
        }

        override fun drainStderr(): List<String> =
            if (stderrIndex < stderrLines.size) listOf(stderrLines[stderrIndex++]) else emptyList()

        override fun isAlive(): Boolean = alive
        override fun destroyGracefully(graceMs: Long): Boolean = true
        override fun destroyForcibly() = Unit
        override fun exitCode(): Int? = if (alive) null else 1
    }

    private fun factoryFor(process: McpManagedProcess): McpProcessFactory = object : McpProcessFactory {
        override fun start(
            command: String,
            args: List<String>,
            env: Map<String, String>,
            workingDir: File?,
        ): McpManagedProcess = process
    }

    @Test
    fun `handshake deadline extends while the server downloads`() {
        val stderr = (1..40).map { "Downloading package chunk $it" }
        val process = ScriptedProcess(nullReadsBeforeAnswer = 30, stderrLines = stderr)
        val session = McpServerSession(
            testStdioServer(),
            factoryFor(process),
            ioTimeoutMs = 5_000,
            startTimeoutMs = 300,
            maxStartTotalMs = 30_000,
            restartBackoffMs = 0,
        )
        val labels = mutableSetOf<String>()
        val worker = Thread {
            val started = session.start(emptyMap())
            synchronized(labels) { labels += "result:$started" }
        }
        worker.start()
        // 30 silent reads x 25ms ≈ 750ms — far past the 300ms handshake
        // budget. Only the download extensions can save this start, and
        // the busy label must say "Downloading" while it happens.
        val deadline = System.currentTimeMillis() + 10_000
        while (worker.isAlive && System.currentTimeMillis() < deadline) {
            session.startupStageLabel?.let { synchronized(labels) { labels += it } }
            Thread.sleep(10)
        }
        worker.join(10_000)
        assertTrue("start must succeed on download progress", "result:true" in labels)
        assertEquals(McpSessionState.RUNNING, session.state)
        assertTrue(
            "the busy stage must name the download",
            labels.any { it.contains("Downloading") },
        )
        session.stop()
    }

    @Test
    fun `a server that dies mid start fails fast with its stage named`() {
        val process = ScriptedProcess(nullReadsBeforeAnswer = Int.MAX_VALUE, stderrLines = emptyList(), alive = false)
        val session = McpServerSession(
            testStdioServer(),
            factoryFor(process),
            ioTimeoutMs = 5_000,
            startTimeoutMs = 30_000,
            restartBackoffMs = 0,
        )
        val began = System.currentTimeMillis()
        assertFalse(session.start(emptyMap()))
        val elapsed = System.currentTimeMillis() - began
        assertTrue("a dead process must not burn the handshake budget (took ${elapsed}ms)", elapsed < 10_000)
        val reason = session.lastFailureReason.orEmpty()
        assertTrue("reason must name the stage: $reason", reason.startsWith("handshake:"))
        assertTrue("reason must say the server exited: $reason", reason.contains("exited"))
    }
}
