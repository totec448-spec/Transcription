package com.example.transcription.ime

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.example.transcription.data.ApiKeyProvider
import com.example.transcription.data.ProviderModels
import com.example.transcription.data.SecretStore
import com.example.transcription.recording.MicLevelNormalizer
import com.example.transcription.recording.MicPeakSource
import com.example.transcription.recording.MicProfileStore
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * IME-only low-latency PCM transport. It intentionally does not post-process text and keeps
 * provider-specific WebSocket framing behind one callback contract.
 *
 * Three things shape the audio path:
 *
 * - Capture starts with the session, not with the socket. Opening a WebSocket
 *   costs a DNS lookup, a TCP connect and a TLS handshake, and everything the
 *   user said during it used to be lost; now the microphone runs from the first
 *   moment and the backlog is delivered the instant the socket reports open.
 * - Audio leaves in 200 ms batches. Providers accept far larger frames than the
 *   50 ms the meter wants, and a quarter of the messages means a quarter of the
 *   per-frame overhead — base64 and a JSON envelope, in ElevenLabs' case.
 * - A live stream cannot be trimmed the way a finished recording can, so the
 *   equivalent saving comes from pausing instead: the session stops sending
 *   after 700 ms of silence and only resumes once somebody is actually
 *   talking. What makes a cut that aggressive safe is that neither edge is
 *   left to chance — the gate commits the turn itself on the way down, and
 *   replays a held-back pre-roll on the way up.
 */
class StreamingVoiceSession(
    private val context: Context,
    private val modelId: String,
    private val secrets: SecretStore,
    private val profiles: MicProfileStore,
    private val archiveFile: File?,
    private val gateSilence: Boolean,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onAmplitude: (Float) -> Unit,
    private val onSilenceGate: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var socket: WebSocket? = null
    private var audioThread: Thread? = null
    private val archiveWriter = archiveFile?.let(::Pcm16WaveWriter)
    private val isElevenLabs = ProviderModels.provider(modelId) ==
        com.example.transcription.data.TranscriptionProvider.ELEVENLABS

    // Silence gate and metering state, touched only from the PCM thread.
    private var silentChunks = 0
    private var speechChunks = 0
    private var chunksSinceKeepalive = 0
    private var chunksSinceLevel = 0
    private var gated = false
    private val micLevel = MicLevelNormalizer()
    private val preRoll = ArrayDeque<ByteArray>()

    // Outgoing 200 ms batch, filled by the PCM thread and drained by it.
    private val batch = ByteArray(BATCH_BYTES)
    private var batchFill = 0

    // Audio captured before the socket opened, guarded by [sendLock].
    private val sendLock = Any()
    private val pending = ArrayDeque<Frame>()
    private var pendingBytes = 0
    private var socketOpen = false

    fun start() {
        val keyProvider = if (isElevenLabs) ApiKeyProvider.ELEVENLABS else ApiKeyProvider.ASSEMBLYAI
        val key = secrets.readApiKey(keyProvider)
        require(key.isNotBlank()) { "Add your ${keyProvider.label} API key in the app Settings." }
        val url = if (isElevenLabs) {
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime" +
                "?model_id=${ProviderModels.elevenLabsId(modelId)}&audio_format=pcm_16000&commit_strategy=vad"
        } else {
            "wss://streaming.assemblyai.com/v3/ws?sample_rate=16000" +
                "&speech_model=${ProviderModels.assemblyAiId(modelId)}"
        }
        val request = Request.Builder()
            .url(url)
            .header(if (isElevenLabs) "xi-api-key" else "Authorization", key)
            .build()
        running.set(true)
        // Before the socket, deliberately: the handshake takes long enough to
        // swallow the start of a sentence, and audio recorded meanwhile simply
        // waits in [pending].
        try {
            startAudio()
        } catch (error: Throwable) {
            running.set(false)
            throw error
        }
        socket = client.newWebSocket(request, listener)
    }

    fun pause() {
        stopCapture()
    }

    fun resume() {
        if (running.get()) return
        running.set(true)
        startAudio()
    }

    fun accept() {
        stopCapture()
        archiveWriter?.finish()
        commitTurn()
        if (isElevenLabs) {
            main.postDelayed({ closeSocket() }, 900L)
        } else {
            main.postDelayed({
                control(TERMINATE_FRAME)
                closeSocket()
            }, 700L)
        }
    }

    fun abandon() {
        stopCapture()
        archiveWriter?.finish()
        archiveFile?.delete()
        if (!isElevenLabs) control(TERMINATE_FRAME)
        closeSocket()
    }

    fun archivedAudio(): File? = archiveFile?.takeIf { it.isFile && it.length() > WAVE_HEADER_BYTES }

    private fun startAudio() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required."
        }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minimum, CHUNK_BYTES * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized." }
        // Start from what earlier recordings measured on this microphone, so
        // the first sentence is metered correctly instead of paying for the
        // calibration itself. The batch recorder shares this profile whenever
        // it, too, ends up reading PCM.
        micLevel.reset(profiles.load(MicPeakSource.KEY_PCM))
        audioRecord = record
        record.startRecording()
        audioThread = Thread({
            val bytes = ByteArray(CHUNK_BYTES)
            while (running.get()) {
                val count = record.read(bytes, 0, bytes.size)
                if (count <= 0) continue
                val speaking = dispatchAmplitude(bytes, count)
                if (!gateSilence) {
                    stage(bytes, count)
                    continue
                }
                if (updateGate(speaking)) {
                    // The turn was committed as the gate closed, so nothing is
                    // waiting on this audio. Hold it back, and keep the socket
                    // alive with one chunk every few seconds. A chunk is either
                    // held or sent, never both, or the pre-roll would replay
                    // audio the provider already has.
                    if (++chunksSinceKeepalive >= KEEPALIVE_CHUNKS) {
                        chunksSinceKeepalive = 0
                        stage(bytes, count)
                        flushBatch()
                    } else {
                        remember(bytes, count)
                    }
                } else {
                    chunksSinceKeepalive = 0
                    while (preRoll.isNotEmpty()) preRoll.removeFirst().let { stage(it, it.size) }
                    stage(bytes, count)
                }
            }
            flushBatch()
        }, "voice-ime-pcm").apply { start() }
    }

    /**
     * Appends one captured chunk to the outgoing batch, sending whenever a full
     * [BATCH_BYTES] has accumulated. Called only from the PCM thread.
     */
    private fun stage(bytes: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val take = min(BATCH_BYTES - batchFill, count - offset)
            System.arraycopy(bytes, offset, batch, batchFill, take)
            batchFill += take
            offset += take
            if (batchFill == BATCH_BYTES) flushBatch()
        }
    }

    /** Archives and sends whatever is staged. The archive is what went on the wire. */
    private fun flushBatch() {
        if (batchFill == 0) return
        val payload = batch.copyOf(batchFill)
        batchFill = 0
        archiveWriter?.write(payload, payload.size)
        deliver(payload)
    }

    private fun deliver(payload: ByteArray) = enqueueOrSend(Frame.Audio(payload), payload.size)

    /**
     * Queues a provider command behind the audio it refers to. A commit that
     * overtook its own sentence would end an empty turn, which is exactly what
     * happens if these are handed straight to the socket while a backlog waits.
     */
    private fun control(text: String) = enqueueOrSend(Frame.Control(text), text.length)

    /**
     * Sends one frame, or holds it until the socket opens. The lock keeps a
     * batch staged on the PCM thread from overtaking the backlog that [onOpen]
     * is draining on the WebSocket thread.
     */
    private fun enqueueOrSend(frame: Frame, cost: Int) {
        synchronized(sendLock) {
            val live = socket?.takeIf { socketOpen }
            if (live == null) {
                // Bounded, and the earliest audio wins: if the handshake is
                // still unfinished fifteen seconds in, the session is failing
                // anyway and the opening words are what a retry needs.
                if (pendingBytes + cost <= PENDING_MAX_BYTES) {
                    pending.addLast(frame)
                    pendingBytes += cost
                }
                return
            }
            send(live, frame)
        }
    }

    private fun send(webSocket: WebSocket, frame: Frame) {
        when (frame) {
            is Frame.Control -> webSocket.send(frame.text)
            is Frame.Audio -> if (isElevenLabs) {
                webSocket.send(
                    JSONObject()
                        .put("message_type", "input_audio_chunk")
                        .put("audio_base_64", Base64.encodeToString(frame.payload, Base64.NO_WRAP))
                        .put("sample_rate", SAMPLE_RATE)
                        .toString()
                )
            } else {
                webSocket.send(frame.payload.toByteString())
            }
        }
    }

    /** Hands the provider everything recorded during the handshake, in order. */
    private fun releasePending(webSocket: WebSocket) {
        synchronized(sendLock) {
            socketOpen = true
            while (pending.isNotEmpty()) send(webSocket, pending.removeFirst())
            pendingBytes = 0
        }
    }

    /** What the session owes the socket, in capture order. */
    private sealed class Frame {
        class Audio(val payload: ByteArray) : Frame()
        class Control(val text: String) : Frame()
    }

    /**
     * Stops the microphone and waits for the PCM thread to hand over its last
     * batch, so a commit that follows cannot race the tail of the sentence it
     * is committing.
     */
    private fun stopCapture() {
        val wasCapturing = audioRecord != null
        running.set(false)
        // Stopping first unblocks the pending read, so the join below is a
        // formality rather than a wait for one more frame of audio.
        runCatching { audioRecord?.stop() }
        val thread = audioThread
        audioThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(CAPTURE_DRAIN_MS) }
        }
        audioRecord?.release()
        audioRecord = null
        // After the join, so the trackers the PCM thread owned are settled.
        if (wasCapturing) {
            micLevel.observed()?.let { profiles.record(MicPeakSource.KEY_PCM, it) }
        }
    }

    /**
     * A short tail of held-back audio so the provider sees a clean silence to
     * speech edge when the gate opens again, rather than a clipped word onset.
     */
    private fun remember(bytes: ByteArray, count: Int) {
        preRoll.addLast(bytes.copyOf(count))
        while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
    }

    /**
     * Returns true while the stream should stay paused.
     *
     * Silence has to be uninterrupted to close the gate, so a single loud frame
     * would keep re-arming the countdown forever — a door slam, a keyboard tap,
     * any of it. Only a run of speech frames counts as somebody talking again;
     * anything shorter is an event, not a sentence.
     */
    private fun updateGate(speaking: Boolean): Boolean {
        if (speaking) speechChunks++ else speechChunks = 0
        if (speechChunks >= SPEECH_ONSET_CHUNKS) silentChunks = 0 else silentChunks++
        val shouldGate = silentChunks >= GATE_AFTER_CHUNKS
        if (shouldGate != gated) {
            gated = shouldGate
            if (shouldGate) {
                // Let the closing words go, then end the turn on our terms.
                flushBatch()
                commitTurn()
            }
            main.post { onSilenceGate(shouldGate) }
        }
        return gated
    }

    /**
     * Ends the current turn explicitly instead of waiting for the provider to
     * notice the silence itself.
     *
     * Both providers decide a turn is over by hearing enough quiet audio, which
     * is exactly the audio this gate refuses to pay for. Committing here is what
     * makes a 700 ms cut safe: the last words are transcribed immediately rather
     * than hanging as a partial until the next sentence arrives.
     */
    private fun commitTurn() {
        control(if (isElevenLabs) COMMIT_FRAME else FORCE_ENDPOINT_FRAME)
    }

    /**
     * Publishes the normalized level and returns whether this chunk was speech,
     * so the gate reuses the single pass the normalizer already made instead of
     * walking the buffer a second time with a threshold of its own.
     *
     * The normalizer sees every 50 ms frame because its trackers want them; the
     * UI only hears about every other one. Twenty state updates a second, each
     * one a recorder-state copy and a widget check, is more work than a circle
     * that redraws at display rate can show.
     */
    private fun dispatchAmplitude(bytes: ByteArray, count: Int): Boolean {
        val level = micLevel.accept(MicLevelNormalizer.peakOfPcm16(bytes, count))
        if (++chunksSinceLevel >= LEVEL_EVERY_CHUNKS) {
            chunksSinceLevel = 0
            main.post { onAmplitude(level) }
        }
        return micLevel.speaking
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            releasePending(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val json = JSONObject(text)
                if (isElevenLabs) {
                    when (json.optString("message_type")) {
                        "partial_transcript" -> main.post { onPartial(json.optString("text")) }
                        "committed_transcript", "final_transcript" ->
                            main.post { onFinal(json.optString("text")) }
                        "auth_error", "quota_exceeded", "transcriber_error", "input_error", "error" ->
                            main.post { onError(json.optString("error", "ElevenLabs streaming failed.")) }
                    }
                } else if (json.optString("type") == "Turn") {
                    val transcript = json.optString("transcript")
                    if (json.optBoolean("end_of_turn")) main.post { onFinal(transcript) }
                    else main.post { onPartial(transcript) }
                }
            }.onFailure { main.post { onError(it.message ?: "Streaming response was invalid.") } }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (running.get()) main.post { onError(t.message ?: "Streaming connection failed.") }
        }
    }

    private fun closeSocket() {
        stopCapture()
        archiveWriter?.finish()
        synchronized(sendLock) {
            socketOpen = false
            pending.clear()
            pendingBytes = 0
        }
        socket?.close(1000, "done")
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 1_600 // 50 ms of mono PCM16: one meter frame.
        const val BATCH_BYTES = CHUNK_BYTES * 4 // 200 ms: one message on the wire.
        const val WAVE_HEADER_BYTES = 44L

        /**
         * 700 ms — about what a provider's own end-of-turn detector waits for,
         * and short enough that ordinary pauses between sentences stop costing
         * anything. Safe only because [commitTurn] closes the turn at the same
         * moment and [PRE_ROLL_CHUNKS] covers the next one opening.
         */
        const val GATE_AFTER_CHUNKS = 14
        const val SPEECH_ONSET_CHUNKS = 3 // 150 ms of speech re-opens the gate.
        const val PRE_ROLL_CHUNKS = 8 // 400 ms of lead-in when speech returns.
        const val KEEPALIVE_CHUNKS = 100 // One chunk every five seconds while paused.
        const val LEVEL_EVERY_CHUNKS = 2 // Publish the mic level at 10 Hz.

        /** Fifteen seconds of PCM16: far more handshake than can ever succeed. */
        const val PENDING_MAX_BYTES = SAMPLE_RATE * 2 * 15

        /** Long enough for one blocking read to return and hand over its batch. */
        const val CAPTURE_DRAIN_MS = 200L

        const val COMMIT_FRAME = """{"message_type":"input_audio_chunk","audio_base_64":"","commit":true}"""
        const val FORCE_ENDPOINT_FRAME = """{"type":"ForceEndpoint"}"""
        const val TERMINATE_FRAME = """{"type":"Terminate"}"""
    }
}

/**
 * Zero-conversion archive of the exact PCM16 stream already sent to the live
 * provider. Only a standard 44-byte WAV header is added.
 */
private class Pcm16WaveWriter(private val file: File) {
    private val output: RandomAccessFile
    private var dataBytes = 0L
    private var finished = false

    init {
        file.parentFile?.mkdirs()
        output = RandomAccessFile(file, "rw").apply {
            setLength(0L)
        }
        writeHeader(0L)
    }

    @Synchronized
    fun write(bytes: ByteArray, count: Int) {
        if (finished || count <= 0) return
        output.seek(WAVE_HEADER_SIZE + dataBytes)
        output.write(bytes, 0, count)
        dataBytes += count
    }

    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        writeHeader(dataBytes)
        output.close()
    }

    private fun writeHeader(payloadBytes: Long) {
        output.seek(0L)
        output.writeBytes("RIFF")
        writeIntLe((36L + payloadBytes).coerceAtMost(0xffff_ffffL).toInt())
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        writeIntLe(16)
        writeShortLe(1)
        writeShortLe(1)
        writeIntLe(SAMPLE_RATE)
        writeIntLe(SAMPLE_RATE * BYTES_PER_SAMPLE)
        writeShortLe(BYTES_PER_SAMPLE)
        writeShortLe(BITS_PER_SAMPLE)
        output.writeBytes("data")
        writeIntLe(payloadBytes.coerceAtMost(0xffff_ffffL).toInt())
    }

    private fun writeIntLe(value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
        output.write(value ushr 16 and 0xff)
        output.write(value ushr 24 and 0xff)
    }

    private fun writeShortLe(value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val WAVE_HEADER_SIZE = 44L
    }
}
