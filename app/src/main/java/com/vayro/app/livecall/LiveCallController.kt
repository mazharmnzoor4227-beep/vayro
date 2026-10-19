package com.vayro.app.livecall

import android.content.Context
import android.media.AudioManager
import com.vayro.app.data.ApiKeyCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveCallUiState(
    val phase: Phase = Phase.CONNECTING,
    /** A line worth showing under the orb: a key switch, or the real reason a call failed. */
    val statusLine: String? = null,
    val statusIsError: Boolean = false,
    /** The in-progress turn's transcriptions, exactly as the Live stream reports them. */
    val liveUserText: String = "",
    val liveReplyText: String = "",
    val muted: Boolean = false,
) {
    enum class Phase { CONNECTING, LISTENING, SPEAKING, ERROR, ENDED }
}

/**
 * v3.2.40: everything one Live call needs, behind one small surface for
 * the call screen: a [LiveCallSession] (protocol + failover), the mic
 * capture, the audio player, and the communication audio mode (the
 * device's hardware echo control engages in that mode — on loudspeaker
 * without it the model hears itself and interrupts itself; how well a
 * given phone's AEC behaves is a device property, not something the
 * app can promise).
 *
 * Turns and key switches are reported through the callbacks the host
 * wires to the view model, which is what saves them into the home
 * thread — the controller itself stores nothing.
 */
class LiveCallController(
    context: Context,
    private val credentials: List<ApiKeyCredential>,
    private val autoSwitch: Boolean,
    private val restingKeyIds: Set<String>,
    private val voiceName: String,
    private val onTurn: (userText: String?, assistantText: String?) -> Unit,
    private val onSystemLine: (String) -> Unit,
    private val onKeyExhausted: (keyId: String, quota: Boolean) -> Unit,
    private val onKeyActivated: (keyId: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode = AudioManager.MODE_NORMAL

    private val player = LiveAudioPlayer(scope)

    @Volatile
    private var muted = false

    @Volatile
    private var ready = false

    @Volatile
    private var stopped = false

    @Volatile
    private var started = false

    private var session: LiveCallSession? = null

    private val mic = LiveMicCapture(
        scope = scope,
        onFrame = { frame -> if (!muted && !stopped) session?.sendAudio(frame) },
        onError = { message -> audioFailed(message) },
    )

    private val _uiState = MutableStateFlow(LiveCallUiState())
    val uiState: StateFlow<LiveCallUiState> = _uiState

    private val sessionListener = object : LiveCallSession.Listener {
        override fun onReady() {
            ready = true
            _uiState.update {
                if (it.phase == LiveCallUiState.Phase.ERROR) it
                else it.copy(phase = LiveCallUiState.Phase.LISTENING)
            }
            if (!muted && !stopped) mic.start()
        }

        override fun onAudio(bytes: ByteArray) {
            player.enqueue(bytes)
        }

        override fun onInterrupted() {
            player.stopAndClear()
        }

        override fun onTranscript(userText: String, assistantText: String) {
            _uiState.update { it.copy(liveUserText = userText, liveReplyText = assistantText) }
        }

        override fun onTurn(userText: String?, assistantText: String?) {
            onTurn.invoke(userText, assistantText)
        }

        override fun onKeySwitched(line: String, exhaustedKeyId: String, quota: Boolean, activatedKeyId: String) {
            onKeyExhausted(exhaustedKeyId, quota)
            onKeyActivated(activatedKeyId)
            onSystemLine(line)
            _uiState.update { it.copy(statusLine = line, statusIsError = false) }
        }

        override fun onTerminalError(message: String) {
            ready = false
            mic.stop()
            player.stopAndClear()
            _uiState.update {
                it.copy(
                    phase = LiveCallUiState.Phase.ERROR,
                    statusLine = message,
                    statusIsError = true,
                )
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        previousAudioMode = audioManager.mode
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        player.start()
        val created = LiveCallSession(
            transportFactory = { OkHttpLiveTransport() },
            credentials = credentials,
            autoSwitch = autoSwitch,
            config = LiveCallConfig(voiceName = voiceName),
            listener = sessionListener,
            restingKeyIds = restingKeyIds,
        )
        session = created
        created.start()
        scope.launch {
            while (!stopped) {
                val sounding = player.isSounding
                _uiState.update {
                    when {
                        it.phase == LiveCallUiState.Phase.LISTENING && sounding ->
                            it.copy(phase = LiveCallUiState.Phase.SPEAKING)
                        it.phase == LiveCallUiState.Phase.SPEAKING && !sounding ->
                            it.copy(phase = LiveCallUiState.Phase.LISTENING)
                        else -> it
                    }
                }
                delay(120)
            }
        }
    }

    /** Mute truly stops the mic: capture is released, and the server is told the stream ended. */
    fun setMuted(value: Boolean) {
        if (stopped || muted == value) return
        muted = value
        _uiState.update { it.copy(muted = value) }
        if (value) {
            mic.stop()
            session?.sendAudioStreamEnd()
        } else if (ready) {
            mic.start()
        }
    }

    /** Orb energy: the model's voice while it sounds, otherwise the mic. Polled by the screen. */
    fun orbLevel(): Float = if (player.isSounding) player.level else mic.level

    fun isSpeaking(): Boolean = player.isSounding

    fun stop() {
        if (stopped) return
        stopped = true
        ready = false
        mic.stop()
        // The session saves any half-finished turn through onTurn before closing.
        session?.stop()
        player.stop()
        runCatching { audioManager.mode = previousAudioMode }
        if (_uiState.value.phase != LiveCallUiState.Phase.ERROR) {
            _uiState.update { it.copy(phase = LiveCallUiState.Phase.ENDED) }
        }
        scope.cancel()
    }

    private fun audioFailed(message: String) {
        if (stopped) return
        ready = false
        session?.stop()
        player.stopAndClear()
        _uiState.update {
            it.copy(
                phase = LiveCallUiState.Phase.ERROR,
                statusLine = message,
                statusIsError = true,
            )
        }
    }
}
