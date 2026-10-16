package com.vayro.app.lsp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 — validates the real LSP lifecycle against a fake language
 * server speaking genuine JSON-RPC over stdio: initialize handshake,
 * definition requests, completion and publishDiagnostics notifications.
 *
 * The fake server implements just enough of the protocol to prove the
 * client's framing, id matching and notification dispatch are correct.
 */
class LspProtocolClientTest {

    /**
     * Minimal fake LSP server: reads Content-Length-framed JSON-RPC messages
     * and answers initialize/definition/completion; emits one
     * publishDiagnostics notification after didOpen.
     */
    private class FakeServer(
        private val input: InputStream,
        private val output: OutputStream,
    ) : Runnable {
        override fun run() {
            try {
                while (true) {
                    val msg = readMessage() ?: break
                    val id = msg.opt("id")
                    when (msg.optString("method", "")) {
                        "initialize" -> writeMessage(
                            JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", id)
                                .put(
                                    "result", JSONObject()
                                        .put("capabilities", JSONObject())
                                )
                        )
                        "textDocument/definition" -> {
                            val params = msg.optJSONObject("params")
                            val uri = params?.optJSONObject("textDocument")?.optString("uri", "")
                            writeMessage(
                                JSONObject()
                                    .put("jsonrpc", "2.0")
                                    .put("id", id)
                                    .put(
                                        "result", JSONArray().put(
                                            JSONObject()
                                                .put("uri", uri)
                                                .put(
                                                    "range", JSONObject()
                                                        .put(
                                                            "start", JSONObject()
                                                                .put("line", 4)
                                                                .put("character", 2)
                                                        )
                                                        .put(
                                                            "end", JSONObject()
                                                                .put("line", 4)
                                                                .put("character", 7)
                                                        )
                                                )
                                        )
                                    )
                            )
                        }
                        "textDocument/completion" -> writeMessage(
                            JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", id)
                                .put(
                                    "result", JSONArray().put(
                                        JSONObject().put("label", "computeTotal")
                                    )
                                )
                        )
                        "textDocument/didOpen" -> {
                            val uri = msg.optJSONObject("params")
                                ?.optJSONObject("textDocument")?.optString("uri", "")
                            // Emit a diagnostics notification for the opened file.
                            writeMessage(
                                JSONObject()
                                    .put("jsonrpc", "2.0")
                                    .put("method", "textDocument/publishDiagnostics")
                                    .put(
                                        "params", JSONObject()
                                            .put("uri", uri)
                                            .put(
                                                "diagnostics", JSONArray().put(
                                                    JSONObject()
                                                        .put(
                                                            "range", JSONObject()
                                                                .put(
                                                                    "start", JSONObject()
                                                                        .put("line", 0)
                                                                        .put("character", 0)
                                                                )
                                                                .put(
                                                                    "end", JSONObject()
                                                                        .put("line", 0)
                                                                        .put("character", 5)
                                                                )
                                                        )
                                                        .put("severity", 1)
                                                        .put("message", "Fake error for test")
                                                )
                                            )
                                    )
                            )
                        }
                        "shutdown" -> writeMessage(
                            JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", id)
                                .put("result", JSONObject())
                        )
                        // initialized / exit / didChange: no response needed.
                    }
                }
            } catch (e: Exception) {
                // Test teardown closes the pipes; that is expected.
            }
        }

        private fun readMessage(): JSONObject? {
            var contentLength = -1
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) break
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = parts[1].trim().toIntOrNull() ?: -1
                }
            }
            if (contentLength < 0) return JSONObject()
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n == -1) return null
                read += n
            }
            return JSONObject(String(body, Charsets.UTF_8))
        }

        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }

        @Synchronized
        private fun writeMessage(msg: JSONObject) {
            val body = msg.toString().toByteArray(Charsets.UTF_8)
            output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
    }

    /** client -> server pipe, server -> client pipe. */
    private class Harness {
        val clientToServer = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient)
        val serverThread: Thread

        init {
            serverThread = Thread(FakeServer(serverIn, serverToClient), "fake-lsp-server").apply {
                isDaemon = true
                start()
            }
        }

        fun client(timeoutMs: Long = 10_000) =
            LspProtocolClient(clientIn, clientToServer, requestTimeoutMs = timeoutMs)

        fun close() {
            runCatching { clientToServer.close() }
            runCatching { serverToClient.close() }
        }
    }

    @Test
    fun `initialize handshake succeeds with real framing`() {
        val h = Harness()
        val client = h.client()
        try {
            assertTrue(client.initialize("/workspace"))
            assertTrue(client.isInitialized)
        } finally {
            client.shutdown()
            h.close()
        }
    }

    @Test
    fun `definition request returns protocol locations`() {
        val h = Harness()
        val client = h.client()
        try {
            assertTrue(client.initialize("/workspace"))
            val locs = client.definition("/workspace/Main.kt", 10, 5)
            assertEquals(1, locs.size)
            assertEquals(4, locs[0].range.start.line) // 0-based from server
            assertEquals(2, locs[0].range.start.character)
        } finally {
            client.shutdown()
            h.close()
        }
    }

    @Test
    fun `completion request returns labels`() {
        val h = Harness()
        val client = h.client()
        try {
            assertTrue(client.initialize("/workspace"))
            val items = client.completion("/workspace/Main.kt", 3, 4)
            assertEquals(1, items.size)
            assertEquals("computeTotal", items[0].label)
        } finally {
            client.shutdown()
            h.close()
        }
    }

    @Test
    fun `publishDiagnostics notifications are dispatched`() {
        val h = Harness()
        val client = h.client()
        try {
            assertTrue(client.initialize("/workspace"))
            val latch = CountDownLatch(1)
            val received = AtomicReference<List<LspDiagnostic>>()
            client.onDiagnostics { _, diags ->
                received.set(diags)
                latch.countDown()
            }
            client.didOpen("/workspace/Main.kt", "kotlin", "fun main() {}\n")
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            val diags = received.get()
            assertEquals(1, diags.size)
            assertEquals("Fake error for test", diags[0].message)
            assertEquals(LspDiagnosticSeverity.ERROR, diags[0].severity)
        } finally {
            client.shutdown()
            h.close()
        }
    }

    @Test
    fun `requests before initialize return empty, never hang`() {
        val h = Harness()
        // Short timeout so a hang would fail fast.
        val client = h.client(timeoutMs = 2_000)
        try {
            assertTrue(client.definition("/workspace/Main.kt", 1, 0).isEmpty())
            assertTrue(client.completion("/workspace/Main.kt", 1, 0).isEmpty())
        } finally {
            client.shutdown()
            h.close()
        }
    }

    @Test
    fun `uri helpers round-trip`() {
        assertEquals("file:///workspace/Main.kt", LspProtocolClient.pathToUri("/workspace/Main.kt"))
        assertEquals("/workspace/Main.kt", LspProtocolClient.uriToPath("file:///workspace/Main.kt"))
    }

    @Test
    fun `output framing uses content-length`() {
        val h = Harness()
        val rawOut = ByteArrayOutputStream()
        // Tee: bytes go to the fake server AND are captured for assertion.
        val tee = TeeOutputStream(h.clientToServer, rawOut)
        val rawClient = LspProtocolClient(h.clientIn, tee, requestTimeoutMs = 10_000)
        try {
            assertTrue(rawClient.initialize("/workspace"))
            val written = rawOut.toString(Charsets.US_ASCII.name())
            assertTrue(written.startsWith("Content-Length: "))
            assertTrue(written.contains("\r\n\r\n"))
            val headerEnd = written.indexOf("\r\n\r\n")
            val claimed = written.substring("Content-Length: ".length, written.indexOf("\r\n"))
                .trim().toInt()
            val bodyBytes = written.substring(headerEnd + 4).toByteArray(Charsets.UTF_8)
            // First message is the initialize request; its body is exactly
            // `claimed` bytes (an `initialized` notification follows it).
            val firstBody = JSONObject(bodyBytes.toString(Charsets.UTF_8).substring(0, claimed))
            assertEquals("initialize", firstBody.optString("method"))
            assertEquals("2.0", firstBody.optString("jsonrpc"))
        } finally {
            rawClient.shutdown()
            h.close()
        }
    }

    /** Writes every byte to two downstream streams. */
    private class TeeOutputStream(
        private val a: OutputStream,
        private val b: OutputStream,
    ) : OutputStream() {
        override fun write(value: Int) {
            a.write(value)
            b.write(value)
        }

        override fun write(buf: ByteArray, off: Int, len: Int) {
            a.write(buf, off, len)
            b.write(buf, off, len)
        }

        override fun flush() {
            a.flush()
            b.flush()
        }
    }
}
