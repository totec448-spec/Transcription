package com.example.transcription.recording

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a raw microphone peak into a usable level, and decides whether that
 * level is speech.
 *
 * A fixed dB window cannot do this. Phone microphones differ by more than 30 dB
 * of sensitivity, and on a quiet one every recording sits so far down the scale
 * that a fixed mapping produces a flat waveform and a speech threshold that
 * never trips. So nothing here is absolute: the class tracks the two levels the
 * signal itself keeps showing — the floor it falls back to between words and the
 * level ordinary speech reaches — and expresses everything relative to those.
 *
 * Four properties matter more than the exact constants:
 *
 * 1. The reference for full deflection is a *slow* estimate of speech, not the
 *    recent peak. A peak that follows the signal within a syllable is always
 *    equal to it, which renders every spoken frame as full scale: the waveform
 *    then only knows silence and maximum, no matter how the person is talking.
 *    A reference that takes seconds to move leaves the individual syllables
 *    swinging around [SPEECH_TARGET], which is what reads as speaking rather
 *    than as an alarm.
 * 2. Silence is silence. Anything within [KNEE_DB] of the floor renders as
 *    zero, so a loud room settles at rest instead of sitting permanently at
 *    half deflection because it is the only thing being measured.
 * 3. The floor is the quietest level heard in the last few seconds, not a filter
 *    that tracks the signal. A floor that rises toward whatever it hears will,
 *    given a long enough sentence, climb into the speech it exists to measure,
 *    dragging the knee up behind it until the waveform flattens mid-recording.
 *    A running minimum cannot do that, and it still rises when the room really
 *    does get louder — a fan switched on becomes the new minimum within one
 *    window. It ignores digital silence: both capture paths open with a handful
 *    of empty frames while the microphone spins up, and a floor taken from those
 *    would render every later frame as full deflection and as speech.
 * 4. Timing is wall-clock, not per-frame. The batch recorder samples every
 *    200 ms and the live stream every 50 ms; identical per-frame coefficients
 *    made the live trackers converge four times faster, which is why the same
 *    voice looked calm in one path and strobed between empty and full in the
 *    other.
 *
 * What one recording learns is worth keeping, because learning it takes a few
 * seconds of speech and those seconds are visible. A [MicProfile] handed to the
 * constructor or to [reset] starts the trackers where the last recording on
 * this microphone left them; [observed] hands the measurement back once enough
 * speech has actually been seen to have measured anything.
 *
 * Everything is one-pole filters over the sample the caller already computed:
 * roughly two dozen float operations and a single `log10` per audio frame, no
 * buffers and no allocation, so it can sit in the recording loop.
 *
 * @param frameMs how much audio each [accept] call summarizes.
 * @param profile what the last recording on this microphone measured, if any.
 */
class MicLevelNormalizer(
    private val frameMs: Int = DEFAULT_FRAME_MS,
    profile: MicProfile? = null
) {
    private val floorFall = coefficient(FLOOR_FALL_MS)
    private val floorRise = coefficient(FLOOR_RISE_MS)
    private val speechRise = coefficient(SPEECH_RISE_MS)
    private val speechFall = coefficient(SPEECH_FALL_MS)
    private val speechRelax = coefficient(SPEECH_RELAX_MS)
    private val displayRise = coefficient(DISPLAY_RISE_MS)
    private val displayFall = coefficient(DISPLAY_FALL_MS)

    /** The level ordinary speech reaches on this microphone, in dBFS. Slow. */
    private var speechDb = QUIET_DB + MicProfile.DEFAULT_MARGIN_DB

    /** Background level between words, in dBFS. Falls fast, rises slowly. */
    private var floorDb = QUIET_DB

    /** Smoothed output, so a single loud frame cannot strobe the waveform. */
    private var displayed = 0f

    /**
     * The quietest frame in each slice of the floor window. A plain running
     * minimum would never recover from one quiet moment; rotating slices let
     * old minima expire. See [rememberQuiet].
     */
    private val buckets = FloatArray(FLOOR_BUCKETS) { EMPTY }
    private var bucket = 0
    private var bucketMs = 0

    /** Total speech seen, which is what makes [observed] worth storing. */
    private var speechSeenMs = 0

    private var primed = false

    /** True while the most recent sample was above the speech threshold. */
    var speaking = false
        private set

    init {
        reset(profile)
    }

    /**
     * Forgets the current microphone and optionally adopts a known one.
     *
     * Priming from a stored profile is not merely a head start: an unprimed
     * tracker takes its floor from the first audible frame, and if that frame
     * is a word rather than the room, the recording opens with several seconds
     * of wrong deflection.
     */
    fun reset(profile: MicProfile? = null) {
        val known = profile?.sane()
        floorDb = known?.floorDb ?: QUIET_DB
        speechDb = known?.speechDb ?: (floorDb + MicProfile.DEFAULT_MARGIN_DB)
        displayed = 0f
        // Seeding the window with the remembered floor keeps the first sentence
        // from being the only thing in it, which would read as a loud room. A
        // genuinely quieter room still undercuts the seed on its first frame.
        buckets.fill(known?.floorDb ?: EMPTY)
        bucket = 0
        bucketMs = 0
        speechSeenMs = 0
        primed = known != null
        speaking = false
    }

    /**
     * Feeds one raw linear peak in `0f..1f` and returns the display level in
     * `0f..1f`, normalized against the range this microphone actually produces.
     */
    fun accept(rawPeak: Float): Float {
        val db = toDecibels(rawPeak)

        // Digital silence carries no information about the room. Priming on it
        // pins the floor to the bottom of the scale, and since the floor only
        // creeps upward it would stay there: every later frame would read as
        // full deflection and as speech.
        if (db <= SILENCE_CEILING_DB && !primed) {
            speaking = false
            return smooth(0f)
        }

        if (!primed) {
            primed = true
            floorDb = db
            speechDb = db + MicProfile.DEFAULT_MARGIN_DB
            buckets.fill(db)
        }

        // The floor is the quietest thing heard recently — never a level the
        // signal walks the floor up to. See [rememberQuiet].
        val quietest = rememberQuiet(db)
        if (quietest < EMPTY) {
            floorDb += (quietest - floorDb) * (if (quietest < floorDb) floorFall else floorRise)
        }
        floorDb = floorDb.coerceIn(MicProfile.MIN_FLOOR_DB, MicProfile.MAX_FLOOR_DB)

        // Note the dependency runs one way only, and has to. The floor is a
        // function of the signal alone; the speech reference is a function of
        // the floor. Any rule that also lets the speech reference push the
        // floor closes the loop, and the two then walk each other up the scale
        // until the knee passes the voice and the waveform flatlines.

        // Hysteresis: it takes a clear margin to call a frame speech, but much
        // less to keep the call. Without it every dip between two syllables
        // reads as a pause and restarts the live gate's silence countdown.
        val required = if (speaking) HOLD_MARGIN_DB else OPEN_MARGIN_DB
        speaking = db - floorDb > required && db > SILENCE_CEILING_DB

        if (speaking) {
            speechSeenMs = min(speechSeenMs + frameMs, CALIBRATED_MS)
            // Slow in both directions, and slower coming down: this is meant to
            // describe how loud this person talks, not what the last syllable
            // did. Every constant here is in seconds, not milliseconds.
            speechDb += (db - speechDb) * (if (db > speechDb) speechRise else speechFall)
        } else {
            // Nothing to measure. Drift back toward the assumed margin over the
            // current room, so a reference learned while shouting in a car does
            // not flatten the next recording made at a desk.
            val assumed = floorDb + MicProfile.DEFAULT_MARGIN_DB
            speechDb += (assumed - speechDb) * speechRelax
        }
        speechDb = speechDb.coerceIn(
            floorDb + MicProfile.MIN_MARGIN_DB,
            floorDb + MicProfile.MAX_MARGIN_DB
        )

        return smooth(deflection(db))
    }

    /**
     * Files one frame into the rotating window and returns the quietest level
     * still in it, or [EMPTY] if the window holds nothing usable yet.
     *
     * This is why a long recording no longer fades. Between syllables — and
     * certainly between sentences — some frame in the last few seconds is the
     * room, so the minimum stays at the room however long somebody talks. The
     * slices are what let it recover: without them one quiet moment would pin
     * the floor for the rest of the recording. The slice being filled counts
     * too, so a drop registers on the frame it happens rather than one slice
     * later. Four floats and a rotating index, no allocation per frame.
     */
    private fun rememberQuiet(db: Float): Float {
        if (db > SILENCE_CEILING_DB) buckets[bucket] = min(buckets[bucket], db)
        bucketMs += frameMs
        if (bucketMs >= FLOOR_WINDOW_MS / FLOOR_BUCKETS) {
            bucketMs = 0
            bucket = (bucket + 1) % FLOOR_BUCKETS
            buckets[bucket] = EMPTY
        }
        var quietest = EMPTY
        for (value in buckets) quietest = min(quietest, value)
        return quietest
    }

    /**
     * Maps one frame's level onto the waveform.
     *
     * Ordinary speech lands at [SPEECH_TARGET] rather than at the top, which is
     * what leaves room for a raised voice to be visibly louder instead of
     * hitting the same ceiling as everything else. Below the knee is rest: the
     * room the floor has been measuring renders as a flat line, however loud
     * that room happens to be in absolute terms.
     */
    private fun deflection(db: Float): Float {
        val knee = floorDb + KNEE_DB
        val span = max(speechDb - knee, MIN_SPAN_DB)
        val unit = (db - knee) / span
        return when {
            unit <= 0f -> 0f
            unit <= 1f -> unit * SPEECH_TARGET
            // Everything above speaking level shares the remaining headroom on
            // a curve that approaches full scale without ever pinning to it.
            else -> SPEECH_TARGET + (1f - SPEECH_TARGET) * (1f - exp(-(unit - 1f) * HEADROOM_SLOPE))
        }
    }

    /** Fast to open, slower to close, so the waveform reads as motion. */
    private fun smooth(target: Float): Float {
        displayed += (target - displayed) * (if (target > displayed) displayRise else displayFall)
        return displayed.coerceIn(0f, 1f)
    }

    /**
     * What this recording measured, or null if it never heard enough speech to
     * have measured anything. Storing a floor-only guess as a profile would
     * teach the next recording a margin nobody spoke.
     */
    fun observed(): MicProfile? =
        if (primed && speechSeenMs >= CALIBRATED_MS) MicProfile(floorDb, speechDb).sane() else null

    private fun toDecibels(rawPeak: Float): Float {
        val magnitude = abs(rawPeak).coerceIn(0f, 1f)
        if (magnitude <= MIN_MAGNITUDE) return SILENT_DB
        return max(SILENT_DB, 20f * log10(magnitude))
    }

    /**
     * The share of the remaining distance one frame covers, for a filter that
     * should close the gap in `timeConstantMs` regardless of frame length.
     */
    private fun coefficient(timeConstantMs: Float): Float =
        (1f - exp(-frameMs.toFloat() / timeConstantMs)).coerceIn(0f, 1f)

    companion object {
        /**
         * Scans a PCM16 buffer for its peak, normalized to `0f..1f`. Kept here
         * so every caller that has PCM measures identically.
         */
        fun peakOfPcm16(bytes: ByteArray, count: Int): Float {
            var peak = 0
            var index = 0
            while (index + 1 < count) {
                val sample = ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
                peak = max(peak, min(abs(sample), Short.MAX_VALUE.toInt()))
                index += 2
            }
            return peak / 32767f
        }

        /** The live stream's frame; the batch recorder passes its own. */
        const val DEFAULT_FRAME_MS = 50

        private const val MIN_MAGNITUDE = 1e-5f
        private const val SILENT_DB = -100f

        /** Where an unprimed tracker starts: quiet, but not at the hard floor. */
        private const val QUIET_DB = -60f

        /**
         * Digital silence and dither only. Anything below this is never speech
         * no matter how far above the floor it sits, which keeps a muted
         * microphone from gating itself open on rounding noise, and it never
         * defines the floor either — see [accept].
         */
        private const val SILENCE_CEILING_DB = -72f

        /** Speech has to clear the background by this much to be called speech. */
        private const val OPEN_MARGIN_DB = 12f

        /** Once speaking, this much keeps the call through inter-word dips. */
        private const val HOLD_MARGIN_DB = 6f

        /**
         * How far over the floor the waveform starts moving at all. Room tone
         * wanders by a few dB around the level the floor has settled on, and
         * every one of those dB would otherwise be drawn.
         */
        private const val KNEE_DB = 6f

        /** Where ordinary speech lands, leaving the rest as headroom. */
        private const val SPEECH_TARGET = 0.55f

        /** How quickly the headroom above speaking level is spent. */
        private const val HEADROOM_SLOPE = 1.2f

        /** Below this the room is too uniform to normalize meaningfully. */
        private const val MIN_SPAN_DB = 8f

        /** Speech that has been present this long has been measured. */
        private const val CALIBRATED_MS = 1_500

        /**
         * How far back the floor looks for a quiet frame, and with it the
         * definition of a room: a level that has held for this long without a
         * single breath, stop or gap in it is not a person. Generous, because
         * the batch path reports the peak of each 200 ms slice and so hides
         * everything shorter than a breath — but still short enough that a
         * fan switched on is remeasured while the recording is running.
         */
        private const val FLOOR_WINDOW_MS = 12_000

        /** Slices of that window, which is what lets old minima expire. */
        private const val FLOOR_BUCKETS = 6

        /** A slice nothing audible has landed in yet. */
        private const val EMPTY = Float.MAX_VALUE

        // Wall-clock time constants, converted to per-frame coefficients above.
        // The floor follows the window minimum down within a syllable, and up
        // over a couple of seconds so a slice expiring cannot step the knee.
        private const val FLOOR_FALL_MS = 350f
        private const val FLOOR_RISE_MS = 1_500f

        // The speech reference is the slowest thing here by design; see the
        // class comment. Falling slower than it rises keeps a quiet aside from
        // rescaling the whole waveform around it.
        private const val SPEECH_RISE_MS = 1_200f
        private const val SPEECH_FALL_MS = 5_000f

        /** How fast an unused reference drifts back to the assumed margin. */
        private const val SPEECH_RELAX_MS = 20_000f

        // The display is deliberately lazier than the trackers behind it.
        private const val DISPLAY_RISE_MS = 90f
        private const val DISPLAY_FALL_MS = 300f
    }
}
