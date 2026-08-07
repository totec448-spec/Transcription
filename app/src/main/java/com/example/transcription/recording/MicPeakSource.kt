package com.example.transcription.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * The raw microphone peak for the paths that record through `MediaRecorder`.
 *
 * `MediaRecorder.getMaxAmplitude()` is the cheap way to meter a recording:
 * the encoder is already looking at every sample, so reading its peak costs
 * nothing. On a large share of MIUI/HyperOS builds it is also simply broken and
 * returns 0 for the entire recording, which is why on those phones the waveform
 * is a flat line in this app and in most others — while the live streaming path,
 * which reads PCM itself, moves normally on the very same microphone.
 *
 * So this watches the opening of a recording, and if the recorder has not
 * reported a single non-zero sample by [PROBE_MS] it opens a second, metering
 * only, `AudioRecord` on the same source and reads peaks out of that instead.
 * Two capture clients in one process is the part of this worth being careful
 * about, so the fallback is deliberately narrow: it never starts on a device
 * whose recorder meter works, it is abandoned for good if it cannot be opened,
 * and the first genuine amplitude from the recorder shuts it down again.
 *
 * @param frameMs how often [read] is called, used to time the probe window.
 */
class MicPeakSource(private val frameMs: Int) {
    private val running = AtomicBoolean(false)

    /** Peak since the last [read], written by the metering thread. */
    @Volatile private var pcmPeak = 0f

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var context: Context? = null
    private var profiles: MicProfileStore? = null
    private var elapsedMs = 0
    private var recorderAlive = false
    private var fallbackImpossible = false

    /** True while levels come from the fallback rather than from the recorder. */
    val measuringPcm: Boolean get() = thread != null

    /**
     * Which stored [MicProfile] these levels belong to. A peak scanned out of
     * PCM and one reported by the encoder are different scales and must not
     * teach each other; the PCM key is shared with the live streaming path,
     * which measures identically.
     */
    fun profileKey(): String = if (measuringPcm) KEY_PCM else KEY_RECORDER

    /**
     * Prepares metering for one recording.
     *
     * A device that has already proven its encoder meter dead skips the probe
     * entirely and opens the fallback here, because the probe is not free: it is
     * a second and a half of flat waveform at the start of every recording, and
     * it is the part of a voice note people watch.
     */
    fun start(context: Context, profiles: MicProfileStore) {
        stop()
        this.context = context.applicationContext
        this.profiles = profiles
        elapsedMs = 0
        recorderAlive = false
        fallbackImpossible = false
        pcmPeak = 0f
        if (profiles.recorderMeterDead()) startFallback(probed = false)
    }

    /**
     * Returns the peak for this frame in `0f..1f`, given whatever
     * `MediaRecorder.getMaxAmplitude()` reported.
     */
    fun read(recorderAmplitude: Int): Float {
        elapsedMs += frameMs
        if (recorderAmplitude > 0) {
            // The recorder works after all — either this device is fine, or it
            // was genuinely silent through the probe window.
            recorderAlive = true
            if (measuringPcm) {
                Log.i(TAG, "mic_meter=recorder reason=amplitude_recovered")
                stopFallback()
            }
        }
        if (recorderAlive) return (recorderAmplitude / 32767f).coerceIn(0f, 1f)
        if (!measuringPcm && !fallbackImpossible && elapsedMs >= PROBE_MS) startFallback(probed = true)
        val peak = pcmPeak
        pcmPeak = 0f
        return peak
    }

    /**
     * Ends metering and records the verdict on this device's encoder meter.
     *
     * Deliberately decided at the end rather than from the probe: a recording
     * that was simply silent for its first second says nothing about the meter,
     * while one that never reported a single sample in several seconds says
     * everything. Short recordings leave the stored verdict alone.
     */
    fun stop() {
        if (elapsedMs >= VERDICT_MS) profiles?.setRecorderMeterDead(!recorderAlive)
        stopFallback()
        profiles = null
        context = null
    }

    private fun startFallback(probed: Boolean) {
        val app = context
        if (app == null ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fallbackImpossible = true
            return
        }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) {
            fallbackImpossible = true
            return
        }
        val opened = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minimum, CHUNK_BYTES * 4)
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?.also { it.startRecording() }
        }.getOrNull()
        if (opened == null) {
            // A device that refuses a second capture client keeps the flat
            // waveform it had before this class existed. Nothing else about the
            // recording depends on it, so the user is told nothing.
            fallbackImpossible = true
            Log.w(TAG, "mic_meter_fallback=unavailable reason=capture_refused")
            return
        }
        // Worth a line: which of the two paths a phone ends up on is otherwise
        // invisible, and it is the difference between a waveform and a flat one.
        Log.i(
            TAG,
            "mic_meter=pcm reason=" +
                (if (probed) "recorder_amplitude_silent probe_ms=$elapsedMs" else "known_dead_meter")
        )
        record = opened
        running.set(true)
        thread = Thread({
            val bytes = ByteArray(CHUNK_BYTES)
            while (running.get()) {
                val count = opened.read(bytes, 0, bytes.size)
                if (count <= 0) continue
                // Peak, not average: the caller wants the same measure the
                // recorder would have reported, held until it asks.
                pcmPeak = max(pcmPeak, MicLevelNormalizer.peakOfPcm16(bytes, count))
            }
        }, "mic-peak-fallback").apply { start() }
    }

    private fun stopFallback() {
        running.set(false)
        // Stopping first unblocks the pending read, so the join is a formality.
        runCatching { record?.stop() }
        val worker = thread
        thread = null
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(DRAIN_MS) }
        }
        runCatching { record?.release() }
        record = null
        pcmPeak = 0f
    }

    companion object {
        private const val TAG = "MicPeakSource"

        const val KEY_RECORDER = "recorder"
        const val KEY_PCM = "pcm"

        /**
         * How long an unproven recorder is given to report its first non-zero
         * peak. Only ever paid once per device: [stop] stores the verdict, and
         * a device known to be silent opens the fallback immediately. Being
         * wrong here is cheap in both directions — a working meter shuts the
         * fallback down again on its first real amplitude.
         */
        private const val PROBE_MS = 700

        /** A recording has to last this long before it may judge the meter. */
        private const val VERDICT_MS = 3_000

        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 1_600 // 50 ms of mono PCM16.
        private const val DRAIN_MS = 200L
    }
}
