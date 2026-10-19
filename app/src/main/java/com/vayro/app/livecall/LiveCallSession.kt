package com.vayro.app.livecall

import com.vayro.app.data.ApiKeyCredential
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The transport the Live session talks over. The production
 * implementation is OkHttp's WebSocket; unit tests substitute a fake
 * server. All listener callbacks may arrive on any thread — the session
 * funnels them onto its own single-threaded event scope before touching
 * state, so implementations never need to synchronize.
 */
interface LiveTransport {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(code: Int, reason: String)
        /** [error] carries the real reason (an "HTTP <code> …" line for a rejected handshake). */
        fun onFailure(error: String)
    }

    fun connect(url: String, listener: Listener)
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

/** What one Live call runs with. The key pool is ordered (active key first) and may be empty — the caller checks first. */
data class LiveCallConfig(
    val voiceName: String = LiveCallProtocol.DEFAULT_VOICE,
    val model: String = LiveCallProtocol.MODEL,
    val systemInstruction: String = LiveCallProtocol.SYSTEM_INSTRUCTION,
)

/**
 * v3.2.40: one Gemini Live call, as a testable state machine.
 *
 * Owns: the setup handshake (nothing is sent before `setupComplete`),
 * mic-frame forwarding, model-audio forwarding, barge-in signalling,
 * in-stream transcription accumulation (a turn is emitted on
 * `turnComplete`, on interruption, or at call end — partial text is
 * saved as-is, never invented), session resumption across the server's
 * connection recycle (`goAway` → reconnect with the latest handle), and
 * key failover across the user's own AI Studio key pool.
 *
 * Failover mirrors home chat exactly: with Auto-switch ON, a
 * quota/auth-class failure marks the key (the caller persists the
 * health mark — quota-class only), activates the next key and reports
 * the switch with the same "Switched to key N" wording; with it OFF,
 * the first failure ends the call with its real reason shown. Quota is
 * per Google PROJECT, not per key — rotating keys of one project adds
 * nothing; the pool only helps across accounts/projects.
 */
class LiveCallSession(
    private val transportFactory: (String) -> LiveTransport,
    private val credentials: List<ApiKeyCredential>,
    private val autoSwitch: Boolean,
    private val config: LiveCallConfig,
    private val listener: Listener,
    private val setupTimeoutMs: Long = 20_000L,
    /** Keys inside their quota cooldown (KeyHealthStore): tried last, never hard-skipped — same rule as home chat. */
    private val restingKeyIds: Set<String> = emptySet(),
) {
    interface Listener {
        /** The session is set up and the conversation is live (also after a silent resumption reconnect). */
        fun onReady()

        /** A chunk of the model's voice (24 kHz PCM16) to play. */
        fun onAudio(bytes: ByteArray)

        /** The user barged in: stop playback and clear the queue immediately. */
        fun onInterrupted()

        /** Live transcript buffers for the in-progress turn (either may be blank). */
        fun onTranscript(userText: String, assistantText: String)

        /** A completed turn to save into the thread. Either side may be null (never invented). */
        fun onTurn(userText: String?, assistantText: String?)

        /** A key was marked exhausted (the caller persists it — quota-class only) and the next key took over. */
        fun onKeySwitched(line: String, exhaustedKeyId: String, quota: Boolean, activatedKeyId: String)

        /** The call is over because of a failure; [message] is the real reason, fit to show. */
        fun onTerminalError(message: String)
    }

    private val eventScope = CoroutineScope(
        Executors.newSingleThreadExecutor { r -> Thread(r, "live-call-session") }.asCoroutineDispatcher() +
            SupervisorJob(),
    )

    // --- state (touched only on the event scope, except where @Volatile) ---
    private var transport: LiveTransport? = null
    private var keyIndex = -1
    private val triedKeyIds = mutableSetOf<String>()
    private var resumptionHandle: String? = null
    private var resumable = false
    private var userClosed = false
    private var terminal = false
    private var ready = false
    private var attempt = 0
    private val userBuffer = StringBuilder()
    private val assistantBuffer = StringBuilder()
    private val resumptionTimes = ArrayDeque<Long>()
    private var abnormalDrops = 0

    @Volatile
    private var readyVolatile = false

    @Volatile
    private var transportVolatile: LiveTransport? = null

    val currentCredential: ApiKeyCredential?
        get() = credentials.getOrNull(keyIndex)

    fun start() {
        eventScope.launch {
            if (credentials.isEmpty()) {
                terminal = true
                listener.onTerminalError("No Google AI Studio key is connected.")
                return@launch
            }
            // Healthy keys first (the active key leads its group); a
            // resting key still starts the call when it is all we have.
            val startIndex = credentials.indices.firstOrNull { credentials[it].id !in restingKeyIds } ?: 0
            connectWithKey(startIndex, resume = false)
        }
    }

    /** Streams one mic chunk. Frames are dropped until the session is set up (the protocol forbids sending earlier). */
    fun sendAudio(pcm: ByteArray) {
        if (!readyVolatile || userClosed) return
        transportVolatile?.send(LiveCallProtocol.buildAudioFrameJson(pcm))
    }

    /** Flushes the server's cached mic audio (used when the mic is muted). */
    fun sendAudioStreamEnd() {
        if (!readyVolatile || userClosed) return
        transportVolatile?.send(LiveCallProtocol.buildAudioStreamEndJson())
    }

    /** Ends the call: saves any half-finished turn, then closes. */
    fun stop() {
        eventScope.launch {
            if (userClosed) return@launch
            userClosed = true
            ready = false
            readyVolatile = false
            flushTurn()
            runCatching { transport?.close(1000, "Call ended") }
            transport = null
            transportVolatile = null
            eventScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Connection attempts
    // ------------------------------------------------------------------

    private fun connectWithKey(index: Int, resume: Boolean) {
        val credential = credentials.getOrNull(index) ?: return
        keyIndex = index
        triedKeyIds += credential.id
        ready = false
        readyVolatile = false
        val myAttempt = ++attempt
        val created = transportFactory(credential.secret)
        transport = created
        transportVolatile = created
        created.connect(
            LiveCallProtocol.endpointFor(credential.secret),
            object : LiveTransport.Listener {
                override fun onOpen() = post(myAttempt, created) { handleOpen(resume) }
                override fun onMessage(text: String) = post(myAttempt, created) { handleMessage(text) }
                override fun onClosed(code: Int, reason: String) =
                    post(myAttempt, created) { handleClosed(code, reason) }
                override fun onFailure(error: String) = post(myAttempt, created) { handleFailure(error) }
            },
        )
        eventScope.launch {
            delay(setupTimeoutMs)
            if (!userClosed && !terminal && attempt == myAttempt && !ready) {
                handleFailure("The Live service did not answer in time — check your connection and try again.")
            }
        }
    }

    /** Reconnects on the SAME key with the resumption handle (the goAway / dropped-connection path). */
    private fun reconnectWithHandle() {
        val now = System.currentTimeMillis()
        while (resumptionTimes.isNotEmpty() && now - resumptionTimes.first() > 60_000L) {
            resumptionTimes.removeFirst()
        }
        resumptionTimes.addLast(now)
        if (resumptionTimes.size > 3) {
            terminal = true
            listener.onTerminalError("The connection kept dropping, so the call was ended.")
            runCatching { transport?.close(1000, "Unstable connection") }
            return
        }
        val index = keyIndex
        runCatching { transport?.close(1000, "Reconnecting") }
        connectWithKey(index, resume = true)
    }

    private fun post(expectedAttempt: Int, from: LiveTransport, block: () -> Unit) {
        eventScope.launch {
            // Stale transports (replaced by a reconnect or a key switch) no longer count.
            if (userClosed || terminal) return@launch
            if (attempt != expectedAttempt || transport !== from) return@launch
            block()
        }
    }

    // ------------------------------------------------------------------
    // Transport events
    // ------------------------------------------------------------------

    private fun handleOpen(resume: Boolean) {
        val handle = if (resume) resumptionHandle else null
        transport?.send(
            LiveCallProtocol.buildSetupJson(
                voiceName = config.voiceName,
                resumptionHandle = handle,
                model = config.model,
                systemInstruction = config.systemInstruction,
            ),
        )
    }

    private fun handleMessage(text: String) {
        for (event in LiveCallProtocol.parseServerMessage(text)) {
            when (event) {
                is LiveServerEvent.SetupComplete -> {
                    ready = true
                    readyVolatile = true
                    abnormalDrops = 0
                    listener.onReady()
                }
                is LiveServerEvent.AudioChunk -> listener.onAudio(event.bytes)
                is LiveServerEvent.InputTranscription -> {
                    userBuffer.append(event.text)
                    listener.onTranscript(userBuffer.toString(), assistantBuffer.toString())
                }
                is LiveServerEvent.OutputTranscription -> {
                    assistantBuffer.append(event.text)
                    listener.onTranscript(userBuffer.toString(), assistantBuffer.toString())
                }
                is LiveServerEvent.Interrupted -> {
                    listener.onInterrupted()
                    // What was actually said of this turn still counts.
                    flushTurn()
                }
                is LiveServerEvent.GenerationComplete -> Unit
                is LiveServerEvent.TurnComplete -> flushTurn()
                is LiveServerEvent.GoAway -> reconnectWithHandle()
                is LiveServerEvent.ResumptionUpdate -> {
                    resumable = event.resumable
                    if (!event.handle.isNullOrBlank()) resumptionHandle = event.handle
                }
            }
        }
    }

    private fun handleClosed(code: Int, reason: String) {
        if (ready && resumable && !resumptionHandle.isNullOrBlank() && abnormalDrops < 2) {
            // A live session dropped without a goAway: one quiet
            // resumption attempt beats ending the user's call.
            abnormalDrops++
            reconnectWithHandle()
            return
        }
        val detail = reason.trim()
        handleFailure(
            if (detail.isNotBlank()) "Connection closed ($code): $detail" else "Connection closed ($code)",
        )
    }

    private fun handleFailure(error: String) {
        if (terminal || userClosed) return
        val current = currentCredential
        if (current != null && autoSwitch && LiveFailure.isKeyFailure(error)) {
            val quota = LiveFailure.isQuotaFailure(error)
            val nextIndex = credentials.indices
                .filter { credentials[it].id !in triedKeyIds }
                .minByOrNull { if (credentials[it].id in restingKeyIds) 1 else 0 }
            if (nextIndex != null) {
                val fromPos = keyIndex + 1
                val toPos = nextIndex + 1
                val why = if (quota) "key $fromPos limit reached" else "key $fromPos was rejected"
                // A different project's session cannot resume this one.
                resumptionHandle = null
                resumable = false
                listener.onKeySwitched(
                    line = "Switched to key $toPos \u2014 $why",
                    exhaustedKeyId = current.id,
                    quota = quota,
                    activatedKeyId = credentials[nextIndex].id,
                )
                runCatching { transport?.close(1000, "Switching key") }
                connectWithKey(nextIndex, resume = false)
                return
            }
        }
        terminal = true
        ready = false
        readyVolatile = false
        listener.onTerminalError(error.trim().ifBlank { "The Live call failed." })
    }

    private fun flushTurn() {
        val user = userBuffer.toString().trim()
        val assistant = assistantBuffer.toString().trim()
        userBuffer.setLength(0)
        assistantBuffer.setLength(0)
        if (user.isEmpty() && assistant.isEmpty()) return
        listener.onTranscript("", "")
        listener.onTurn(user.ifEmpty { null }, assistant.ifEmpty { null })
    }
}
