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

    /**
     * v3.2.38 (M1): the last voice notice — set when the SELECTED voice
     * could not be used (ElevenLabs request failed, or its key / Voice ID
     * is missing), so surfaces can SAY so instead of the old behavior:
     * a silent, permanent-feeling fallback to the device voice.
     * [noticeSeq] increments per notice so observers can react per event.
     * A notice never rewrites the saved selection.
     */
    var lastNotice by mutableStateOf<String?>(null)
        private set
    var noticeSeq by mutableStateOf(0)
        private set

    private fun postNotice(text: String) {
        lastNotice = text
        noticeSeq++
    }

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
        lastNotice = null
        val useElevenLabs = when (savedSource) {
            "device" -> false
            "elevenlabs" -> apiKey != null && voiceId != null
            else -> apiKey != null && voiceId != null
        }
        if (savedSource == "elevenlabs" && !useElevenLabs) {
            // v3.2.38 (M1): ElevenLabs is selected but unusable — say so
            // instead of silently speaking with the device voice.
            val notice = "ElevenLabs is your selected voice, but its key or Voice ID is missing — using the device voice."
            postNotice(notice)
            android.widget.Toast.makeText(appContext, notice, android.widget.Toast.LENGTH_LONG).show()
        }
        if (useElevenLabs) {
            val key = apiKey!!
            val id = voiceId!!
            playJob = scope.launch {
                val failure = arrayOfNulls<String>(1)
                val handled = runCatching {
                    speakElevenLabs(appContext, key, id, plain, my) { reason -> failure[0] = reason }
                }.getOrDefault(false)
                if (!handled && session == my) {
                    // v3.2.38 (M1): a failed ElevenLabs attempt is VISIBLE —
                    // the fallback speaks, and the user is told why.
                    // v3.2.39 (Scope 3): a known failure class also gets a
                    // plain sentence ABOVE the technical line — the raw
                    // detail always stays visible underneath.
                    val technical = "ElevenLabs voice failed (${failure[0] ?: "request failed"}) — using the device voice this time."
                    val hint = elevenLabsPlainHint(failure[0])
                    val notice = if (hint != null) "$hint\n$technical" else technical
                    postNotice(notice)
                    android.widget.Toast.makeText(appContext, notice, android.widget.Toast.LENGTH_LONG).show()
                    speakOnDevice(appContext, plain, my)
                }
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
     * (KodexShell VoiceSettingsScreen, prefs "kodex_voice_settings").
     * Runs on every speak so a new choice takes effect immediately;
     * with nothing saved, the previous defaults apply.
     */
    private fun applyVoicePrefs(engine: TextToSpeech, context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences("kodex_voice_settings", Context.MODE_PRIVATE)
            val savedName = prefs.getString("voice_name", null)
            if (!savedName.isNullOrBlank()) {
                val match = engine.voices?.firstOrNull { it.name == savedName }
                if (match != null) {
                    // The stored identity, resolved at speak time — exactly
                    // the voice the selected card promised.
                    engine.voice = match
                } else {
                    // v3.2.39 (Scope 4): the saved voice has vanished from
                    // this device (its TTS data changed or was removed).
                    // Never silently speak with a different voice: use the
                    // engine's current voice, SAY so, and move the saved
                    // selection to the voice actually in use.
                    val fallback = engine.voice
                    if (fallback != null) {
                        prefs.edit().putString("voice_name", fallback.name).apply()
                        val notice = "Your saved voice is no longer on this device — using '${fallback.name}' instead. You can pick another in Voice settings."
                        postNotice(notice)
                        android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
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
        onFailure: (String) -> Unit,
    ): Boolean {
        val chunks = chunkText(text, 800)
        for (chunk in chunks) {
            if (session != my) return true // stopped on purpose — handled
            val fetched = fetchElevenLabsChunk(context, apiKey, voiceId, chunk)
            val file = fetched.file
            if (file == null) {
                onFailure(fetched.error ?: "request failed")
                return false
            }
            if (session != my) {
                file.delete()
                return true
            }
            val completed = playFile(file)
            file.delete()
            if (session != my) return true
            if (!completed) {
                onFailure("audio playback failed")
                return false
            }
        }
        if (session == my) playingMessageId = null
        return true
    }

    private class ElevenLabsFetch(val file: File?, val error: String?)

    private suspend fun fetchElevenLabsChunk(
        context: Context,
        apiKey: String,
        voiceId: String,
        text: String,
    ): ElevenLabsFetch = withContext(Dispatchers.IO) {
        try {
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
            val code = connection.responseCode
            if (code !in 200..299) {
                // v3.2.38 (M1): surface ElevenLabs' OWN reason (their JSON
                // detail) — a rejected key/voice must be diagnosable.
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                connection.disconnect()
                val detail = elevenLabsErrorDetail(errorBody)
                return@withContext ElevenLabsFetch(
                    null,
                    "HTTP $code" + (detail?.let { " — $it" } ?: ""),
                )
            }
            val file = File(context.cacheDir, "kodex-readaloud-${System.nanoTime()}.mp3")
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            if (file.length() > 0L) ElevenLabsFetch(file, null)
            else ElevenLabsFetch(null, "empty audio reply")
        } catch (e: Exception) {
            ElevenLabsFetch(null, "network problem (${e.message ?: "no connection"})")
        }
    }

    /** Extracts ElevenLabs' human error detail ({"detail":{"message":…}} or {"detail":"…"}). */
    private fun elevenLabsErrorDetail(body: String): String? = runCatching {
        if (body.isBlank()) return null
        val root = JSONObject(body)
        val detail = root.opt("detail")
        val message = when (detail) {
            is JSONObject -> detail.optString("message").ifBlank { detail.optString("status") }
            is String -> detail
            else -> root.optString("message")
        }
        message.ifBlank { null }?.take(140)
    }.getOrNull()

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

/**
 * v3.2.39 (Scope 3): a plain-language explanation for a known ElevenLabs
 * failure class, or null when the failure has no known plain mapping (the
 * raw technical line then stands on its own). Deliberately TOP-LEVEL and
 * pure: the player object initializes against the main dispatcher, which
 * a plain JVM unit test cannot touch — this function can be tested.
 */
internal fun elevenLabsPlainHint(reason: String?): String? {
    val text = reason ?: return null
    return if (text.contains("402") && text.contains("library", ignoreCase = true)) {
        "This is a library voice. ElevenLabs' free plan can't use library voices through the API — choose one of your own voices above, or a standard voice."
    } else {
        null
    }
}
