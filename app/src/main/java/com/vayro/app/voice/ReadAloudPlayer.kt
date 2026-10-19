package com.vayro.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayro.app.data.ApiKeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Kodex read-aloud for agent replies (the Listen button under a message).
 *
 * Default engine: the device's own TextToSpeech — free, offline, no
 * limits. The best available English voice is picked (highest quality,
 * preferring US/GB and network-quality voices) at a natural rate/pitch.
 *
 * Premium option: when the user saves their own ElevenLabs API key and
 * Voice ID (Settings -> Voice, stored encrypted in [ApiKeyVault]), an
 * "ElevenLabs" card joins the voice selection on that screen. Which voice
 * actually speaks is decided by the user's explicit ACTIVE VOICE choice
 * (prefs "kodex_voice_settings" -> "active_voice_source" = "device" or
 * "elevenlabs", plus "voice_name" for the device voice) — never by the
 * mere presence of a saved key. With no explicit choice saved, a saved
 * key + Voice ID keeps the historical default (ElevenLabs). Any
 * ElevenLabs failure falls back to the on-device voice for the same
 * message — Listen never just dies silently — and a fallback never
 * rewrites the saved selection.
 *
 * Long replies are split into sentence-aware chunks so playback does
 * not cut off. [playingMessageId] is observable state so the chat UI can
 * highlight the message being read and turn its button into Stop.
 */
object ReadAloudPlayer {

    var playingMessageId by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session = 0
    private var playJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: (() -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null

    fun toggle(context: Context, messageId: String, rawText: String) {
        if (playingMessageId == messageId) stop() else speak(context, messageId, rawText)
    }

    fun stop() {
        session++
        playJob?.cancel()
        playJob = null
        runCatching { tts?.stop() }
        releasePlayer()
        playingMessageId = null
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun speak(context: Context, messageId: String, rawText: String) {
        stop()
        val my = ++session
        val plain = stripMarkdownForSpeech(rawText)
        if (plain.isBlank()) {
            playingMessageId = null
            return
        }
        playingMessageId = messageId
        val appContext = context.applicationContext
        val vault = runCatching { ApiKeyVault(appContext) }.getOrNull()
        val apiKey = vault?.get("elevenlabs")?.takeIf { it.isNotBlank() }
        val voiceId = vault?.get("elevenlabs_voice")?.takeIf { it.isNotBlank() }
        // The SELECTED voice wins: the Voice settings screen persists an
        // explicit active source. "device" always means the on-device
        // voice (with the saved voice_name applied in speakOnDevice),
        // even when an ElevenLabs key is saved; "elevenlabs" means the
        // premium voice. No saved choice -> legacy default (a saved
        // key + Voice ID implied ElevenLabs before the choice existed).
        val voicePrefs = appContext.getSharedPreferences("kodex_voice_settings", Context.MODE_PRIVATE)
        val savedSource = voicePrefs.getString("active_voice_source", null)
        val useElevenLabs = when (savedSource) {
            "device" -> false
            "elevenlabs" -> apiKey != null && voiceId != null
            else -> apiKey != null && voiceId != null
        }
        if (useElevenLabs) {
            val key = apiKey!!
            val id = voiceId!!
            playJob = scope.launch {
                val handled = runCatching {
                    speakElevenLabs(appContext, key, id, plain, my)
                }.getOrDefault(false)
                if (!handled && session == my) speakOnDevice(appContext, plain, my)
            }
        } else {
            speakOnDevice(appContext, plain, my)
        }
    }

    // ------------------------------------------------------------------
    // On-device TextToSpeech
    // ------------------------------------------------------------------

    private fun speakOnDevice(context: Context, text: String, my: Int) {
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeak = { if (session == my) speakOnDevice(context, text, my) }
            if (engine == null) {
                tts = TextToSpeech(context) { status ->
                    val pending = pendingSpeak
                    pendingSpeak = null
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        configureVoice(tts)
                        pending?.invoke()
                    }
                }
            }
            return
        }
        applyVoicePrefs(engine, context)
        val chunks = chunkText(text, 1100)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == "kodex-$my-${chunks.size - 1}" && session == my) {
                    scope.launch { if (session == my) playingMessageId = null }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (session == my) {
                    scope.launch { if (session == my) playingMessageId = null }
                }
            }
        })
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunk, mode, null, "kodex-$my-$index")
        }
    }

    private fun configureVoice(engine: TextToSpeech?) {
        engine ?: return
        runCatching {
            val english = engine.voices?.filter { it.locale?.language == "en" }.orEmpty()
            val best = english.maxWithOrNull(
                compareBy(
                    { it.quality },
                    { if (it.locale?.country == "US" || it.locale?.country == "GB") 1 else 0 },
                    { if (it.isNetworkConnectionRequired) 1 else 0 },
                ),
            )
            if (best != null) engine.voice = best
            engine.setSpeechRate(0.98f)
            engine.setPitch(1.0f)
        }
    }

    /**
     * Applies the voice + pace picked on the Voice settings screen
     * (ClaudeShell VoiceSettingsScreen, prefs "kodex_voice_settings").
     * Runs on every speak so a new choice takes effect immediately;
     * with nothing saved, the previous defaults apply.
     */
    private fun applyVoicePrefs(engine: TextToSpeech, context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences("kodex_voice_settings", Context.MODE_PRIVATE)
            val savedName = prefs.getString("voice_name", null)
            if (!savedName.isNullOrBlank()) {
                engine.voices?.firstOrNull { it.name == savedName }?.let { engine.voice = it }
            }
            val pace = prefs.getFloat("pace_rate", -1f)
            engine.setSpeechRate(if (pace > 0f) pace else 0.98f)
            engine.setPitch(1.0f)
        }
    }

    // ------------------------------------------------------------------
    // ElevenLabs (user's own key, Settings -> Voice)
    // ------------------------------------------------------------------

    private suspend fun speakElevenLabs(
        context: Context,
        apiKey: String,
        voiceId: String,
        text: String,
        my: Int,
    ): Boolean {
        val chunks = chunkText(text, 800)
        for (chunk in chunks) {
            if (session != my) return true // stopped on purpose — handled
            val file = fetchElevenLabsChunk(context, apiKey, voiceId, chunk) ?: return false
            if (session != my) {
                file.delete()
                return true
            }
            val completed = playFile(file)
            file.delete()
            if (session != my) return true
            if (!completed) return false
        }
        if (session == my) playingMessageId = null
        return true
    }

    private suspend fun fetchElevenLabsChunk(
        context: Context,
        apiKey: String,
        voiceId: String,
        text: String,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("xi-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "audio/mpeg")
            val body = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put(
                    "voice_settings",
                    JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.75)
                    },
                )
            }.toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return@runCatching null
            }
            val file = File(context.cacheDir, "kodex-readaloud-${System.nanoTime()}.mp3")
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            if (file.length() > 0L) file else null
        }.getOrNull()
    }

    private suspend fun playFile(file: File): Boolean = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        mediaPlayer = player
        cont.invokeOnCancellation {
            runCatching { player.stop() }
            runCatching { player.release() }
            if (mediaPlayer === player) mediaPlayer = null
        }
        try {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                runCatching { player.release() }
                if (mediaPlayer === player) mediaPlayer = null
                if (cont.isActive) cont.resume(true)
            }
            player.setOnErrorListener { _, _, _ ->
                runCatching { player.release() }
                if (mediaPlayer === player) mediaPlayer = null
                if (cont.isActive) cont.resume(false)
                true
            }
            player.prepare()
            player.start()
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }

    // ------------------------------------------------------------------
    // Text preparation
    // ------------------------------------------------------------------

    /** Strips markdown so the voice reads prose, not symbols. */
    internal fun stripMarkdownForSpeech(raw: String): String {
        var text = raw.replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
        text = text.replace(Regex("`([^`]*)`"), "$1")
        text = text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("https?://\\S+"), "link")
        text = text.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.contains("-") && trimmed.matches(Regex("^\\|?[\\s:|-]+\\|?$")) -> ""
                else -> trimmed.trimStart('#', '>', ' ').replace("|", ", ")
            }
        }
        text = text.replace(Regex("[*_~]"), "")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Sentence-aware chunks so long replies play fully, without cut-offs. */
    internal fun chunkText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.length > maxLen) {
            var cut = rest.lastIndexOf(". ", maxLen)
            if (cut < maxLen / 3) cut = rest.lastIndexOf(" ", maxLen)
            if (cut <= 0) cut = maxLen
            chunks += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotBlank()) chunks += rest
        return chunks.filter { it.isNotBlank() }
    }
}
