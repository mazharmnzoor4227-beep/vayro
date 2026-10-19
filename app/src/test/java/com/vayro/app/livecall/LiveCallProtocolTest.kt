package com.vayro.app.livecall

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.40: the Gemini Live wire format, pinned to the official docs
 * (see GEMINI-LIVE-FACTS.md in the build notes). If Google's protocol
 * changes, these fail loudly instead of the call dying silently.
 */
class LiveCallProtocolTest {

    @Test
    fun `setup carries model audio modalities voice transcriptions resumption and compression`() {
        val setup = JSONObject(LiveCallProtocol.buildSetupJson(voiceName = "Puck"))
            .getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        val generation = setup.getJSONObject("generationConfig")
        val modalities = generation.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))
        // The 3.8 Live model takes no thinkingLevel — none must be sent.
        assertFalse(generation.has("thinkingConfig"))
        val voice = generation.getJSONObject("speechConfig")
            .getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig")
        assertEquals("Puck", voice.getString("voiceName"))
        // Both transcription streams on, or the thread transcript is blind.
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        // Long calls survive only with resumption + the sliding window, from the first setup.
        assertTrue(setup.has("sessionResumption"))
        assertEquals(0, setup.getJSONObject("sessionResumption").length())
        assertTrue(
            setup.getJSONObject("contextWindowCompression").has("slidingWindow"),
        )
        val instruction = setup.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(instruction.contains("voice assistant"))
    }

    @Test
    fun `setup with a handle resumes the previous session`() {
        val setup = JSONObject(
            LiveCallProtocol.buildSetupJson(voiceName = "Kore", resumptionHandle = "handle-9"),
        ).getJSONObject("setup")
        assertEquals(
            "handle-9",
            setup.getJSONObject("sessionResumption").getString("handle"),
        )
    }

    @Test
    fun `audio frame is base64 pcm at 16 khz`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5)
        val frame = JSONObject(LiveCallProtocol.buildAudioFrameJson(pcm))
            .getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", frame.getString("mimeType"))
        assertTrue(pcm.contentEquals(Base64.getDecoder().decode(frame.getString("data"))))
    }

    @Test
    fun `stream end flush is well formed`() {
        val message = JSONObject(LiveCallProtocol.buildAudioStreamEndJson())
        assertTrue(
            message.getJSONObject("realtimeInput").getBoolean("audioStreamEnd"),
        )
    }

    @Test
    fun `parser reads setup complete go away and resumption updates`() {
        assertTrue(
            LiveCallProtocol.parseServerMessage("""{"setupComplete":{}}""")
                .single() is LiveServerEvent.SetupComplete,
        )
        val goAway = LiveCallProtocol.parseServerMessage("""{"goAway":{"timeLeft":"42s"}}""")
            .single() as LiveServerEvent.GoAway
        assertEquals("42s", goAway.timeLeft)
        val update = LiveCallProtocol.parseServerMessage(
            """{"sessionResumptionUpdate":{"resumable":true,"newHandle":"h-2"}}""",
        ).single() as LiveServerEvent.ResumptionUpdate
        assertTrue(update.resumable)
        assertEquals("h-2", update.handle)
    }

    @Test
    fun `parser splits a bundled server content message`() {
        val audio = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val events = LiveCallProtocol.parseServerMessage(
            """
            {"serverContent":{
              "modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$audio"}}]},
              "inputTranscription":{"text":"hello","finished":true},
              "outputTranscription":{"text":"hi there"},
              "turnComplete":true
            }}
            """.trimIndent(),
        )
        assertEquals(4, events.size)
        val chunk = events.filterIsInstance<LiveServerEvent.AudioChunk>().single()
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(chunk.bytes))
        assertEquals(
            "hello",
            events.filterIsInstance<LiveServerEvent.InputTranscription>().single().text,
        )
        assertEquals(
            "hi there",
            events.filterIsInstance<LiveServerEvent.OutputTranscription>().single().text,
        )
        assertTrue(events.any { it is LiveServerEvent.TurnComplete })
    }

    @Test
    fun `parser reads interruption and tolerates junk`() {
        assertTrue(
            LiveCallProtocol.parseServerMessage("""{"serverContent":{"interrupted":true}}""")
                .single() is LiveServerEvent.Interrupted,
        )
        assertTrue(LiveCallProtocol.parseServerMessage("{}").isEmpty())
        assertTrue(LiveCallProtocol.parseServerMessage("not json").isEmpty())
        assertTrue(LiveCallProtocol.parseServerMessage("").isEmpty())
    }

    @Test
    fun `failure classifier separates quota rejection and ordinary errors`() {
        assertTrue(LiveFailure.isQuotaFailure("HTTP 429 — RESOURCE_EXHAUSTED"))
        assertTrue(LiveFailure.isKeyFailure("HTTP 429 — RESOURCE_EXHAUSTED"))
        assertTrue(LiveFailure.isKeyFailure("HTTP 401 — UNAUTHENTICATED"))
        assertTrue(LiveFailure.isKeyFailure("HTTP 403 — API key not valid"))
        // A malformed request is OUR bug, not the key's — never rotate on it.
        assertFalse(LiveFailure.isKeyFailure("HTTP 400 — invalid setup"))
        assertFalse(LiveFailure.isKeyFailure("Connection closed (1006)"))
    }

    @Test
    fun `the voice list is the full prebuilt set`() {
        assertEquals(30, LiveCallProtocol.VOICES.size)
        assertEquals(30, LiveCallProtocol.VOICES.map { it.first }.distinct().size)
        assertTrue(LiveCallProtocol.VOICES.any { it.first == LiveCallProtocol.DEFAULT_VOICE })
    }
}
