package com.vayro.app.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI state for the inline voice input panel. */
data class VoiceInputState(
    val listening: Boolean = false,
    /** Segments the recogniser has already finalised. */
    val committed: String = "",
    /** Live words from the current, still-open segment. */
    val partial: String = "",
    /** Fatal problem, human wording first (permission, unavailable). Pauses are not errors. */
    val error: String? = null,
    /** Raw SpeechRecognizer error code behind [error], for support. */
    val errorCode: Int? = null,
    /** True while a dropped voice service is being reconnected automatically. */
    val reconnecting: Boolean = false,
    /** Human line shown while [reconnecting] (e.g. "Voice service dropped — reconnecting…"). */
    val reconnectMessage: String? = null,
    /** Soft, non-fatal line (e.g. "Didn't catch that") — a pause the user may want to see. */
    val softMessage: String? = null,
) {
    val fullText: String
        get() = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ").trim()
}

/**
 * In-app speech recognition for the chat composer. Uses [SpeechRecognizer]
 * directly, so tapping the mic never opens the system speech dialog.
 *
 * Android ends a recognition session after a pause; this wrapper restarts it
 * and stitches the finalised segments together, so one voice session keeps
 * listening until the user taps cancel / stop / send. "No match" and speech
 * timeouts count as pauses, not errors.
 *
 * Must be created, used and released on the main thread.
 */
class VoiceRecognizer(context: Context) {

    companion object {
        /** Recognition language: Hindi (also handles Hinglish well). Change here only. */
        const val LANGUAGE_TAG = "hi-IN"

        // Waveform normalisation: at/below the floor the bars stay at minimum,
        // at/above the peak they reach full height. Android RMS is roughly -2..10 dB.
        private const val NOISE_FLOOR_DB = 1f
        private const val PEAK_DB = 10f

        /** How long [finish] waits for the final words before completing anyway. */
        private const val FINISH_TIMEOUT_MS = 1200L

        // v3.2.37 (A2): SpeechRecognizer.ERROR_SERVER_DISCONNECTED (11) is an
        // API 31+ constant; the literal is inlined here so older runtimes are
        // safe. A dropped recognition service is transient on many devices —
        // the call must heal itself instead of dying on the first error.
        const val ERROR_SERVER_DISCONNECTED: Int = 11

        /** Consecutive heal attempts before a transient error becomes fatal. */
        private const val MAX_HEAL_ATTEMPTS = 3

        /** Backoff before each heal attempt (index 0 = first retry). */
        private val HEAL_BACKOFF_MS = longArrayOf(500L, 800L, 1200L)

        /** Human-first wording for a recognizer error code. */
        fun humanError(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off"
            ERROR_SERVER_DISCONNECTED -> "Voice service disconnected"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network problem"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice service is busy"
            SpeechRecognizer.ERROR_SERVER -> "Voice service problem"
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
            SpeechRecognizer.ERROR_AUDIO -> "Microphone problem"
            SpeechRecognizer.ERROR_CLIENT -> "Voice input problem"
            else -> "Voice input problem"
        }

        /** True for errors that justify destroying + recreating the recognizer and trying again. */
        fun isTransient(error: Int): Boolean = when (error) {
            ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> true
            else -> false
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var healAttempts = 0
    private var healRunnable: Runnable? = null
    private var wantsListening = false
    private var finishing = false
    private var released = false
    private var finishCallback: ((String) -> Unit)? = null

    private val _state = MutableStateFlow(VoiceInputState())
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    /**
     * Normalised mic level, 0..1. A plain field on purpose: the waveform reads
     * it once per frame and must not pay for state-flow emissions.
     */
    @Volatile
    var level: Float = 0f
        private set

    fun isAvailable(): Boolean =
        !released && SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start() {
        if (released || wantsListening) return
        if (!isAvailable()) {
            _state.value = VoiceInputState(error = "Speech recognition is not available on this device")
            return
        }
        wantsListening = true
        finishing = false
        healAttempts = 0
        _state.value = VoiceInputState(listening = true)
        beginSession()
    }

    /** Stop listening and hand back everything heard (waits briefly for the last words). */
    fun finish(onDone: (String) -> Unit) {
        if (!wantsListening) {
            onDone(_state.value.fullText)
            return
        }
        finishing = true
        finishCallback = onDone
        runCatching { recognizer?.stopListening() }
        mainHandler.postDelayed({ completeFinish() }, FINISH_TIMEOUT_MS)
    }

    /** Discard the whole session. */
    fun cancel() {
        finishing = false
        finishCallback = null
        teardown()
    }

    /** Permanent shutdown (composer disposed / screen left). */
    fun release() {
        released = true
        cancel()
    }

    // ------------------------------------------------------------------ internals

    private fun beginSession() {
        if (!wantsListening || finishing || released) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
        runCatching { recognizer?.startListening(intent) }
    }

    private fun restartLater(delayMs: Long) {
        mainHandler.postDelayed({ if (wantsListening && !finishing) beginSession() }, delayMs)
    }

    private fun commitSegment(text: String) {
        if (text.isBlank()) return
        _state.update { current ->
            val joined = listOf(current.committed, text).filter { it.isNotBlank() }.joinToString(" ").trim()
            current.copy(committed = joined, partial = "")
        }
    }

    private fun onSegmentEnded() {
        if (finishing) completeFinish() else if (wantsListening) restartLater(60)
    }

    private fun completeFinish() {
        if (!finishing) return
        finishing = false
        val text = _state.value.fullText
        val callback = finishCallback
        finishCallback = null
        teardown()
        callback?.invoke(text)
    }

    /**
     * v3.2.37 (A2) self-heal: the recognizer that just errored is destroyed
     * NOW (never startListening against a session that is still tearing
     * down), then a fresh one is created after a short backoff. Bounded:
     * after [MAX_HEAL_ATTEMPTS] consecutive failures the error is surfaced
     * as a human line with its code.
     */
    private fun heal(error: Int) {
        recognizer?.destroy()
        recognizer = null
        healRunnable?.let { mainHandler.removeCallbacks(it) }
        if (healAttempts >= MAX_HEAL_ATTEMPTS) {
            failFatally(humanError(error), error)
            return
        }
        val backoff = HEAL_BACKOFF_MS[healAttempts.coerceIn(0, HEAL_BACKOFF_MS.lastIndex)]
        healAttempts++
        val line = when (error) {
            ERROR_SERVER_DISCONNECTED -> "Voice service dropped — reconnecting…"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network problem — reconnecting…"
            else -> "Voice service busy — reconnecting…"
        }
        _state.update { it.copy(listening = true, reconnecting = true, reconnectMessage = line) }
        val runnable = Runnable {
            healRunnable = null
            if (wantsListening && !finishing && !released) beginSession()
        }
        healRunnable = runnable
        mainHandler.postDelayed(runnable, backoff)
    }

    private fun teardown() {
        wantsListening = false
        healRunnable?.let { mainHandler.removeCallbacks(it) }
        healRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        level = 0f
        if (!released) _state.value = VoiceInputState()
    }

    private fun failFatally(message: String, code: Int? = null) {
        wantsListening = false
        healRunnable?.let { mainHandler.removeCallbacks(it) }
        healRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        level = 0f
        _state.value = VoiceInputState(error = message, errorCode = code)
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            healAttempts = 0
            _state.update { it.copy(reconnecting = false, reconnectMessage = null) }
        }

        override fun onBeginningOfSpeech() {
            _state.update { it.copy(softMessage = null) }
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onEndOfSpeech() {
            level = 0f
        }

        override fun onRmsChanged(rmsdB: Float) {
            level = ((rmsdB - NOISE_FLOOR_DB) / (PEAK_DB - NOISE_FLOOR_DB)).coerceIn(0f, 1f)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstResult() ?: return
            _state.update { it.copy(partial = text, softMessage = null) }
        }

        override fun onResults(results: Bundle?) {
            val text = results.firstResult().orEmpty().ifBlank { _state.value.partial }
            commitSegment(text)
            onSegmentEnded()
        }

        override fun onError(error: Int) {
            level = 0f
            when {
                // A pause, not a failure: keep whatever was heard and listen on.
                // Surfaced only as a soft line ("Didn't catch that"), never an error.
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    commitSegment(_state.value.partial)
                    if (!finishing) {
                        _state.update { it.copy(softMessage = humanError(error)) }
                    }
                    onSegmentEnded()
                }
                // A dropped/busy voice service: destroy, wait, recreate, resume.
                isTransient(error) -> {
                    if (finishing) {
                        completeFinish()
                    } else if (wantsListening) {
                        heal(error)
                    }
                }
                error == SpeechRecognizer.ERROR_CLIENT -> {
                    if (finishing) completeFinish() else if (wantsListening) restartLater(350)
                }
                else -> failFatally(humanError(error), error)
            }
        }
    }
}
