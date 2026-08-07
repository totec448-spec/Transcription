package com.example.transcription.recording

import android.os.SystemClock
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Removes the dead air from a send copy before it is uploaded.
 *
 * Providers bill wall-clock audio, so the pauses between sentences are paid for
 * twice: once in money and once in upload time. This runs on the temporary send
 * file only — the archived recording is never touched, and playback in Notes
 * still contains the original pauses.
 *
 * Order matters and was originally wrong. `speechnorm` used to run first, on
 * the theory that normalizing made one threshold work across microphones — but
 * it lifts the room tone up toward speech along with everything else, so
 * `silenceremove` behind it saw no silence left to remove and a six-minute
 * recording came back 0.2% shorter after a six-second encode. Silence is cut
 * from the raw signal, where the gap between speech and room is still intact,
 * and the level is fixed afterwards.
 *
 * The threshold is measured rather than fixed. Any constant is wrong for some
 * room: this recording's fan put the floor near -43 dB, so a -50 dB threshold
 * found no silence at all in six minutes that were half pauses, while a
 * threshold high enough for that fan would eat words in a quiet room. A cheap
 * analysis pass reads the recording's own level first and the cut is placed
 * below it.
 */
object SilenceTrimmer {
    private const val TAG = "TranscriptionPerf"

    /** Below this the pauses are not worth an encode pass. */
    const val MINIMUM_DURATION_MS = 4_000L

    /** A trim that removes less than this was not worth the latency. */
    private const val MEANINGFUL_SAVING_RATIO = 0.06

    /**
     * How far under the recording's own level the cut sits. RMS averaging is
     * energy-based, so the pauses barely move the overall figure and it lands
     * close to the speech itself — ten decibels under that is comfortably
     * below the quietest word and still above a running fan.
     */
    private const val HEADROOM_DB = 10

    /** Guards against a measurement thrown off by a recording that is all noise or all silence. */
    private const val MIN_THRESHOLD_DB = -48
    private const val MAX_THRESHOLD_DB = -34
    private const val DEFAULT_THRESHOLD_DB = -40

    fun filterChain(thresholdDb: Int) =
        "silenceremove=" +
            "start_periods=1:start_duration=0.15:start_threshold=${thresholdDb}dB:" +
            "stop_periods=-1:stop_duration=0.45:stop_threshold=${thresholdDb}dB:" +
            "detection=rms," +
            "speechnorm=e=12.5:r=0.0001:l=1"

    /** Exposed for the unit test, which has no FFmpeg to measure with. */
    fun thresholdFor(rmsLevelDb: Double?): Int {
        val measured = rmsLevelDb ?: return DEFAULT_THRESHOLD_DB
        if (!measured.isFinite()) return DEFAULT_THRESHOLD_DB
        return Math.round(measured - HEADROOM_DB).toInt().coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB)
    }

    fun shouldTrim(enabled: Boolean, durationMs: Long) = enabled && durationMs >= MINIMUM_DURATION_MS

    /**
     * Returns the trimmed file, or `null` when trimming failed or saved too
     * little to justify itself. A `null` result always means "send the original"
     * and is never an error the user needs to see.
     */
    fun trim(source: File, target: File, format: String): File? {
        val started = SystemClock.elapsedRealtime()
        val thresholdDb = thresholdFor(measureLevelDb(source))
        val codecArguments = if (format == "mp3") {
            arrayOf("-codec:a", "libmp3lame", "-b:a", "192k")
        } else {
            arrayOf("-codec:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
        }
        val arguments = arrayOf(
            "-hide_banner", "-loglevel", "error", "-y",
            "-i", source.absolutePath,
            "-vn", "-af", filterChain(thresholdDb)
        ) + codecArguments + arrayOf(target.absolutePath)

        val session = runCatching { FFmpegKit.executeWithArguments(arguments) }.getOrNull()
        val elapsed = SystemClock.elapsedRealtime() - started
        if (session == null || !ReturnCode.isSuccess(session.returnCode) ||
            !target.isFile || target.length() <= 0L
        ) {
            target.delete()
            Log.w(TAG, "silence_trim_failed ms=$elapsed")
            return null
        }
        val saved = 1.0 - target.length().toDouble() / source.length().coerceAtLeast(1L)
        Log.i(
            TAG,
            "silence_trim ms=$elapsed threshold_db=$thresholdDb in_bytes=${source.length()} " +
                "out_bytes=${target.length()} saved=${"%.1f".format(saved * 100)}%"
        )
        if (saved < MEANINGFUL_SAVING_RATIO) {
            target.delete()
            return null
        }
        return target
    }

    /**
     * The recording's mean level, or `null` when it could not be read.
     *
     * `volumedetect` was picked over `astats` deliberately: both report the same
     * figure to a tenth of a decibel, but astats also computes a page of
     * statistics nobody here asked for and took twelve times as long as a bare
     * decode to do it. This pass encodes nothing and writes nothing, so it costs
     * a fraction of the trim it aims.
     */
    private fun measureLevelDb(source: File): Double? {
        val started = SystemClock.elapsedRealtime()
        val session = runCatching {
            FFmpegKit.executeWithArguments(
                arrayOf(
                    "-hide_banner", "-nostats",
                    "-i", source.absolutePath,
                    "-vn", "-af", "volumedetect",
                    "-f", "null", "-"
                )
            )
        }.getOrNull()
        val level = session?.allLogsAsString
            ?.let { LEVEL_PATTERN.find(it) }
            ?.groupValues?.get(1)?.toDoubleOrNull()
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "silence_level ms=$elapsed rms_db=${level ?: "unknown"}")
        return level
    }

    private val LEVEL_PATTERN = Regex("""mean_volume:\s*(-?\d+(?:\.\d+)?) dB""")
}
