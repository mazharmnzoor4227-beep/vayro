package com.vayro.app.livecall

import com.vayro.app.data.ApiKeyCredential
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.40: the Live session state machine against a fake Live server —
 * the same scenarios a real call hits (handshake gating, barge-in,
 * transcript saving, the 10-minute recycle, a dead key), proven on the
 * JVM. Actual audio and Google's live answers remain device-only.
 */
class LiveCallSessionTest {

    private class FakeTransport : LiveTransport {
        @Volatile var listener: LiveTransport.Listener? = null
        @Volatile var url: String? = null
        val sent = CopyOnWriteArrayList<String>()

        override fun connect(url: String, listener: LiveTransport.Listener) {
            this.url = url
            this.listener = listener
        }

        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun close(code: Int, reason: String) = Unit

        fun serverOpen() = listener!!.onOpen()
        fun serverSend(text: String) = listener!!.onMessage(text)
        fun serverClose(code: Int, reason: String) = listener!!.onClosed(code, reason)
        fun serverFail(error: String) = listener!!.onFailure(error)
    }

    private class Recorder : LiveCallSession.Listener {
        val readyLatch = CountDownLatch(1)
        val terminalLatch = CountDownLatch(1)
        val interruptedLatch = CountDownLatch(1)
        @Volatile var terminalMessage: String? = null
        val audioChunks = CopyOnWriteArrayList<ByteArray>()
        val turns = CopyOnWriteArrayList<Pair<String?, String?>>()
        val switchLines = CopyOnWriteArrayList<String>()
        val switchQuota = CopyOnWriteArrayList<Boolean>()
        val switchExhausted = CopyOnWriteArrayList<String>()
        val switchActivated = CopyOnWriteArrayList<String>()

        override fun onReady() = readyLatch.countDown()
        override fun onAudio(bytes: ByteArray) {
            audioChunks += bytes
        }

        override fun onInterrupted() = interruptedLatch.countDown()
        override fun onTranscript(userText: String, assistantText: String) = Unit
        override fun onTurn(userText: String?, assistantText: String?) {
            turns += userText to assistantText
        }

        override fun onKeySwitched(line: String, exhaustedKeyId: String, quota: Boolean, activatedKeyId: String) {
            switchLines += line
            switchQuota += quota
            switchExhausted += exhaustedKeyId
            switchActivated += activatedKeyId
        }

        override fun onTerminalError(message: String) {
            terminalMessage = message
            terminalLatch.countDown()
        }
    }

    private fun cred(id: String, secret: String) =
        ApiKeyCredential(id = id, name = id, secret = secret, isActive = id == "k1")

    private class Rig(
        credentials: List<ApiKeyCredential>,
        autoSwitch: Boolean = true,
        resting: Set<String> = emptySet(),
        setupTimeoutMs: Long = 60_000L,
    ) {
        val transports = CopyOnWriteArrayList<FakeTransport>()
        val recorder = Recorder()
        val session = LiveCallSession(
            transportFactory = { FakeTransport().also { transports += it } },
            credentials = credentials,
            autoSwitch = autoSwitch,
            config = LiveCallConfig(),
            listener = recorder,
            setupTimeoutMs = setupTimeoutMs,
            restingKeyIds = resting,
        )

        fun startAndReady(): FakeTransport {
            session.start()
            awaitTrue { transports.size == 1 }
            val transport = transports[0]
            transport.serverOpen()
            awaitTrue { transport.sent.size == 1 }
            transport.serverSend("""{"setupComplete":{}}""")
            assertTrue(recorder.readyLatch.await(3, TimeUnit.SECONDS))
            return transport
        }
    }

    @Test
    fun `setup goes first and mic audio waits for setupComplete`() {
        val rig = Rig(listOf(cred("k1", "key-one")))
        rig.session.start()
        awaitTrue { rig.transports.size == 1 }
        val transport = rig.transports[0]
        assertTrue(transport.url!!.contains("key=key-one"))
        transport.serverOpen()
        awaitTrue { transport.sent.size == 1 }
        val setup = JSONObject(transport.sent[0]).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))

        // The protocol forbids sending before setupComplete — frames drop.
        rig.session.sendAudio(byteArrayOf(1, 2))
        Thread.sleep(150)
        assertEquals(1, transport.sent.size)

        transport.serverSend("""{"setupComplete":{}}""")
        assertTrue(rig.recorder.readyLatch.await(3, TimeUnit.SECONDS))
        val pcm = byteArrayOf(5, 6, 7, 8)
        rig.session.sendAudio(pcm)
        awaitTrue { transport.sent.size == 2 }
        val frame = JSONObject(transport.sent[1])
            .getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", frame.getString("mimeType"))
        assertTrue(pcm.contentEquals(Base64.getDecoder().decode(frame.getString("data"))))
        rig.session.stop()
    }

    @Test
    fun `transcriptions accumulate and save as one turn`() {
        val rig = Rig(listOf(cred("k1", "key-one")))
        val transport = rig.startAndReady()
        transport.serverSend("""{"serverContent":{"inputTranscription":{"text":"What is","finished":false}}}""")
        transport.serverSend("""{"serverContent":{"inputTranscription":{"text":" the time?"}}}""")
        transport.serverSend("""{"serverContent":{"outputTranscription":{"text":"It is noon."}}}""")
        transport.serverSend("""{"serverContent":{"generationComplete":true,"turnComplete":true}}""")
        awaitTrue { rig.recorder.turns.size == 1 }
        assertEquals("What is the time?" to "It is noon.", rig.recorder.turns[0])
        rig.session.stop()
    }

    @Test
    fun `interruption signals playback stop and still saves what was said`() {
        val rig = Rig(listOf(cred("k1", "key-one")))
        val transport = rig.startAndReady()
        val audio = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        transport.serverSend(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$audio"}}]},"outputTranscription":{"text":"Hello, let me tell you"}}}""",
        )
        awaitTrue { rig.recorder.audioChunks.size == 1 }
        transport.serverSend("""{"serverContent":{"interrupted":true}}""")
        assertTrue(rig.recorder.interruptedLatch.await(3, TimeUnit.SECONDS))
        awaitTrue { rig.recorder.turns.size == 1 }
        assertEquals(null to "Hello, let me tell you", rig.recorder.turns[0])
        rig.session.stop()
    }

    @Test
    fun `a turn without any transcription saves nothing`() {
        val rig = Rig(listOf(cred("k1", "key-one")))
        val transport = rig.startAndReady()
        val audio = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        transport.serverSend(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$audio"}}]},"turnComplete":true}}""",
        )
        awaitTrue { rig.recorder.audioChunks.size == 1 }
        Thread.sleep(200)
        assertTrue(rig.recorder.turns.isEmpty())
        rig.session.stop()
    }

    @Test
    fun `ending the call saves the half finished turn`() {
        val rig = Rig(listOf(cred("k1", "key-one")))
        val transport = rig.startAndReady()
        transport.serverSend("""{"serverContent":{"inputTranscription":{"text":"okay bye"}}}""")
        rig.session.stop()
        awaitTrue { rig.recorder.turns.size == 1 }
        assertEquals("okay bye" to null, rig.recorder.turns[0])
    }

    @Test
    fun `goAway reconnects on the same key with the resumption handle`() {
        val rig = Rig(listOf(cred("k1", "key-one")))
        val transport = rig.startAndReady()
        transport.serverSend("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"h-123"}}""")
        transport.serverSend("""{"goAway":{"timeLeft":"5s"}}""")
        awaitTrue { rig.transports.size == 2 }
        val second = rig.transports[1]
        assertTrue(second.url!!.contains("key=key-one"))
        second.serverOpen()
        awaitTrue { second.sent.size == 1 }
        val setup = JSONObject(second.sent[0]).getJSONObject("setup")
        assertEquals("h-123", setup.getJSONObject("sessionResumption").getString("handle"))
        second.serverSend("""{"setupComplete":{}}""")
        // Mic frames flow again on the resumed session.
        rig.session.sendAudio(byteArrayOf(9, 9))
        awaitTrue { second.sent.size == 2 }
        rig.session.stop()
    }

    @Test
    fun `quota close switches to the next key with the home chat wording`() {
        val rig = Rig(listOf(cred("k1", "key-one"), cred("k2", "key-two")))
        val transport = rig.startAndReady()
        transport.serverClose(1011, "RESOURCE_EXHAUSTED: quota exceeded for this project")
        awaitTrue { rig.transports.size == 2 }
        awaitTrue { rig.recorder.switchLines.size == 1 }
        assertEquals("Switched to key 2 — key 1 limit reached", rig.recorder.switchLines[0])
        assertEquals(listOf(true), rig.recorder.switchQuota.toList())
        assertEquals(listOf("k1"), rig.recorder.switchExhausted.toList())
        assertEquals(listOf("k2"), rig.recorder.switchActivated.toList())
        assertTrue(rig.transports[1].url!!.contains("key=key-two"))
        assertEquals("k2", rig.session.currentCredential?.id)
        rig.session.stop()
    }

    @Test
    fun `a rejected key switches without a quota mark`() {
        val rig = Rig(listOf(cred("k1", "key-one"), cred("k2", "key-two")))
        rig.session.start()
        awaitTrue { rig.transports.size == 1 }
        rig.transports[0].serverFail("HTTP 403 — API key not valid")
        awaitTrue { rig.transports.size == 2 }
        awaitTrue { rig.recorder.switchLines.size == 1 }
        assertEquals("Switched to key 2 — key 1 was rejected", rig.recorder.switchLines[0])
        assertEquals(listOf(false), rig.recorder.switchQuota.toList())
        rig.session.stop()
    }

    @Test
    fun `auto switch off fails honestly with the real reason`() {
        val rig = Rig(listOf(cred("k1", "key-one"), cred("k2", "key-two")), autoSwitch = false)
        rig.session.start()
        awaitTrue { rig.transports.size == 1 }
        rig.transports[0].serverFail("HTTP 429 — RESOURCE_EXHAUSTED")
        assertTrue(rig.recorder.terminalLatch.await(3, TimeUnit.SECONDS))
        assertTrue(rig.recorder.terminalMessage!!.contains("429"))
        Thread.sleep(150)
        assertEquals(1, rig.transports.size)
        assertTrue(rig.recorder.switchLines.isEmpty())
        rig.session.stop()
    }

    @Test
    fun `when every key is spent the call ends with the reason`() {
        val rig = Rig(listOf(cred("k1", "key-one")))
        rig.session.start()
        awaitTrue { rig.transports.size == 1 }
        rig.transports[0].serverFail("HTTP 429 — RESOURCE_EXHAUSTED")
        assertTrue(rig.recorder.terminalLatch.await(3, TimeUnit.SECONDS))
        assertTrue(rig.recorder.switchLines.isEmpty())
        rig.session.stop()
    }

    @Test
    fun `a resting key is tried after a healthy one`() {
        val rig = Rig(
            listOf(cred("k1", "key-one"), cred("k2", "key-two")),
            resting = setOf("k1"),
        )
        rig.session.start()
        awaitTrue { rig.transports.size == 1 }
        assertTrue(rig.transports[0].url!!.contains("key=key-two"))
        rig.session.stop()
    }

    @Test
    fun `no answer in time ends the call with a plain reason`() {
        val rig = Rig(listOf(cred("k1", "key-one")), setupTimeoutMs = 300L)
        rig.session.start()
        awaitTrue { rig.transports.size == 1 }
        rig.transports[0].serverOpen()
        assertTrue(rig.recorder.terminalLatch.await(4, TimeUnit.SECONDS))
        assertTrue(rig.recorder.terminalMessage!!.contains("did not answer"))
        rig.session.stop()
    }

    private fun awaitTrue(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }
}
