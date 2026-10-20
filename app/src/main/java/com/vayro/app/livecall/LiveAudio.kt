package com.vayro.app.livecall

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * v3.2.40: the Live call's ears — mic capture in exactly the format the
 * Live API wants (16-bit PCM, 16 kHz, mono), so no resampling ever
 * happens. The VOICE_COMMUNICATION source plus the communication audio
 * mode (set by the controller) is what engages the device's hardware
 * echo cancellation; software AEC/NS are attached when the device
 * offers them. Without echo control the model would hear its own voice
 * on loudspeaker and keep interrupting itself.
 */
class LiveMicCapture(
    private val scope: CoroutineScope,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var job: Job? = null

    /** Latest mic level, 0..1 — polled by the call screen for the orb. */
    @Volatile
    var level: Float = 0f
        private set


    @SuppressLint("MissingPermission") // the host checks RECORD_AUDIO before the call screen opens
    fun start() {
        if (record != null) return
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onError("Microphone capture is not available on this device.")
            return
        }
        val created = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_BYTES * 4),
            )
        }.getOrNull()
        if (created == null || created.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { created?.release() }
            onError("Microphone capture could not start on this device.")
            return
        }
        record = created
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(created.audioSessionId)?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(created.audioSessionId)?.enabled = true
            }
        }
        created.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(FRAME_BYTES)
            while (record === created) {
                val read = runCatching { created.read(buffer, 0, buffer.size) }.getOrDefault(-1)
                if (read <= 0) break
                level = rms(buffer, read)
                onFrame(buffer.copyOf(read))
            }
        }
    }

    fun stop() {
        val current = record
        record = null
        job?.cancel()
        job = null
        level = 0f
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    companion object {
        /** 100 ms of 16 kHz PCM16 mono per frame. */
        const val FRAME_BYTES = 3_200

        /** Root-mean-square level of a PCM16 chunk, normalised to 0..1. */
        fun rms(pcm: ByteArray, length: Int = pcm.size): Float {
            val count = length / 2
            if (count == 0) return 0f
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                val signed = if (sample >= 0x8000) sample - 0x10000 else sample
                sum += signed.toDouble() * signed
                i += 2
            }
            return (sqrt(sum / count) / 32_768.0).toFloat().coerceIn(0f, 1f)
        }
    }
}

/**
 * v3.2.40: the Live call's voice — a streaming [AudioTrack] at the
 * model's output rate (24 kHz PCM16 mono). Chunks queue through a
 * channel; a single writer coroutine owns the track. Barge-in is a
 * first-class operation: [stopAndClear] drops every queued chunk and
 * flushes the track, so the model goes silent the moment the user
 * speaks over it.
 */
class LiveAudioPlayer(private val scope: CoroutineScope) {

    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private var track: AudioTrack? = null
    private var writerJob: Job? = null

    @Volatile
    private var pendingBytes = 0

    @Volatile
    private var lastWriteDoneAt = 0L

    @Volatile
    private var lastChunkLevel = 0f

    /** True while model audio is queued or still sounding — drives the orb's Speaking state. */
    val isSounding: Boolean
        get() = pendingBytes > 0 || System.currentTimeMillis() - lastWriteDoneAt < 280L

    /** Output level 0..1 while sounding, for the orb's speaking energy. */
    val level: Float get() = if (isSounding) lastChunkLevel else 0f

    fun start() {
        if (track != null) return
        val sampleRate = 24_000
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, 9_600))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        track = created
        created.play()
        writerJob = scope.launch(Dispatchers.IO) {
            for (chunk in queue) {
                val current = track ?: break
                var offset = 0
                while (offset < chunk.size) {
                    val written = runCatching {
                        current.write(chunk, offset, chunk.size - offset)
                    }.getOrDefault(-1)
                    if (written <= 0) break
                    offset += written
                }
                pendingBytes = (pendingBytes - chunk.size).coerceAtLeast(0)
                lastWriteDoneAt = System.currentTimeMillis()
            }
        }
    }

    fun enqueue(pcm: ByteArray) {
        if (track == null) start()
        if (track == null) return // no output device path — drop rather than queue forever
        lastChunkLevel = LiveMicCapture.rms(pcm)
        pendingBytes += pcm.size
        queue.trySend(pcm)
    }

    /** Barge-in: silence, now. Queued audio is dropped and the track buffer is flushed. */
    fun stopAndClear() {
        while (queue.tryReceive().isSuccess) {
            // drain
        }
        pendingBytes = 0
        lastChunkLevel = 0f
        val current = track ?: return
        runCatching { current.pause() }
        runCatching { current.flush() }
        runCatching { current.play() }
        lastWriteDoneAt = 0L
    }

    fun stop() {
        stopAndClear()
        writerJob?.cancel()
        writerJob = null
        val current = track
        track = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }
}
