package com.vayro.app.runtime

import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.RuntimeEvent
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA Medium: CustomAgentBridge.stopSession used to be a no-op — a
 * streaming request could not be cancelled and kept reading until the
 * server closed or the 120s timeout.
 */
class CustomAgentBridgeStopTest {

    @Test
    fun `stopSession cancels an in-flight SSE stream promptly`() {
        // A raw-socket server (com.sun.net.httpserver is not on the
        // unit-test classpath) that holds the SSE stream open for 30s
        // after one chunk.
        val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        thread(isDaemon = true, name = "sse-test-server") {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    var line: String?
                    do {
                        line = reader.readLine()
                    } while (line != null && line.isNotEmpty())
                    val out = socket.getOutputStream()
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n".toByteArray())
                    out.flush()
                    try {
                        Thread.sleep(30_000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } catch (_: Exception) {
                // Client went away (expected on stop): nothing to do.
            }
        }
        try {
            val provider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = "http://127.0.0.1:$port",
                model = "test-model",
                dshApi = "openai-completions",
            )
            val bridge = CustomAgentBridge(secretFor = { "test-key" })

            val sessionIdRef = AtomicReference<String>()
            val startedLatch = CountDownLatch(1)
            val firstDeltaLatch = CountDownLatch(1)
            val terminalRef = AtomicReference<RuntimeEvent>()
            val doneLatch = CountDownLatch(1)
            thread(isDaemon = true) {
                runBlocking {
                    bridge.events.collect { event ->
                        when (event) {
                            is RuntimeEvent.SessionStarted -> {
                                sessionIdRef.set(event.sessionId)
                                startedLatch.countDown()
                            }
                            // The first delta proves the SSE stream is live
                            // and the connection is tracked, so stopSession
                            // below cannot race ahead of it.
                            is RuntimeEvent.AssistantDelta -> firstDeltaLatch.countDown()
                            is RuntimeEvent.SessionCompleted,
                            is RuntimeEvent.SessionFailed -> {
                                terminalRef.set(event)
                                doneLatch.countDown()
                            }
                            else -> Unit
                        }
                    }
                }
            }

            val sessionThread = thread {
                runBlocking {
                    bridge.startSession(
                        projectId = "p1",
                        projectSlug = "p1",
                        projectKind = ProjectKind.PROJECT,
                        prompt = "hi",
                        conversationHistory = listOf(ChatMessage(fromUser = true, text = "hi")),
                        provider = provider,
                    )
                }
            }

            assertTrue("session never started", startedLatch.await(10, TimeUnit.SECONDS))
            assertTrue("no SSE data arrived", firstDeltaLatch.await(10, TimeUnit.SECONDS))
            val stopAt = System.currentTimeMillis()
            runBlocking { bridge.stopSession(sessionIdRef.get()) }
            assertTrue("session did not end after stop", doneLatch.await(10, TimeUnit.SECONDS))
            val stopMs = System.currentTimeMillis() - stopAt
            assertTrue(
                "stop took ${stopMs}ms; the stream was not cancelled promptly",
                stopMs < 10_000,
            )
            assertTrue(
                "expected SessionCompleted after stop, got ${terminalRef.get()}",
                terminalRef.get() is RuntimeEvent.SessionCompleted,
            )

            sessionThread.join(10_000)
            assertTrue("session thread still alive after stop", !sessionThread.isAlive)
        } finally {
            runCatching { serverSocket.close() }
        }
    }
}
