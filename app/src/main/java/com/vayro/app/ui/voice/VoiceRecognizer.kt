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
    /** Fatal problem worth a toast (permission, network, unavailable). Pauses are not errors. */
    val error: String? = null,
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
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
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

    private fun teardown() {
        wantsListening = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        level = 0f
        if (!released) _state.value = VoiceInputState()
    }

    private fun failFatally(message: String) {
        wantsListening = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        level = 0f
        _state.value = VoiceInputState(error = message)
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
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
            _state.update { it.copy(partial = text) }
        }

        override fun onResults(results: Bundle?) {
            val text = results.firstResult().orEmpty().ifBlank { _state.value.partial }
            commitSegment(text)
            onSegmentEnded()
        }

        override fun onError(error: Int) {
            level = 0f
            when (error) {
                // A pause, not a failure: keep whatever was heard and listen on.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    commitSegment(_state.value.partial)
                    onSegmentEnded()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    if (finishing) completeFinish() else if (wantsListening) restartLater(350)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    failFatally("Microphone permission is missing")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER ->
                    failFatally("Voice recognition needs an internet connection")
                else -> failFatally("Voice input failed ($error)")
            }
        }
    }
}
