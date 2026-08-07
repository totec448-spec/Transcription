package com.example.transcription.recording

import android.content.Context

/**
 * What one microphone sounds like: the level it rests at with nobody talking,
 * and the level it reaches when somebody does.
 *
 * A single recording can measure this, but only after a few seconds of speech —
 * which is exactly the part of a voice note the user is watching. Carrying the
 * measurement across recordings is what makes the first word of the next one
 * deflect correctly instead of being the price of calibrating again.
 *
 * @param floorDb background level in dBFS, room-dependent and short-lived.
 * @param speechDb the level ordinary speech reaches on this microphone, also in
 *   dBFS. Interesting mostly as its distance to [floorDb], which is a property
 *   of the hardware and survives a change of room.
 */
data class MicProfile(val floorDb: Float, val speechDb: Float) {
    /** How far speech sits over the room. The part worth remembering. */
    val marginDb: Float get() = speechDb - floorDb

    /**
     * Folds a freshly measured profile into the stored one.
     *
     * The floor follows the new observation closely because a room is where the
     * phone happens to be, and the in-session tracker re-measures it within
     * seconds anyway. The margin is a microphone property, so a single odd
     * recording — a shouted note, a phone in a pocket — may only nudge it.
     */
    fun mergedInto(previous: MicProfile?): MicProfile {
        if (previous == null) return sane()
        val floor = previous.floorDb + (floorDb - previous.floorDb) * FLOOR_WEIGHT
        val margin = previous.marginDb + (marginDb - previous.marginDb) * MARGIN_WEIGHT
        return MicProfile(floor, floor + margin).sane()
    }

    /** Clamps a profile into a range a microphone can actually produce. */
    fun sane(): MicProfile {
        val floor = floorDb.coerceIn(MIN_FLOOR_DB, MAX_FLOOR_DB)
        return MicProfile(floor, floor + marginDb.coerceIn(MIN_MARGIN_DB, MAX_MARGIN_DB))
    }

    companion object {
        private const val FLOOR_WEIGHT = 0.5f
        private const val MARGIN_WEIGHT = 0.25f

        const val MIN_FLOOR_DB = -85f
        const val MAX_FLOOR_DB = -10f

        /**
         * Speech that clears the room by less than this was not measured on
         * speech, and a margin wider than the upper bound would leave ordinary
         * talking permanently near the bottom of the scale.
         */
        const val MIN_MARGIN_DB = 12f
        const val MAX_MARGIN_DB = 34f

        /** Assumed distance of speech over the room before anything is known. */
        const val DEFAULT_MARGIN_DB = 20f
    }
}

/**
 * The learned profile per level source, kept across recordings.
 *
 * Keyed by *how* the level was measured rather than by which feature recorded
 * it: `MediaRecorder.getMaxAmplitude()` and a peak scanned out of PCM are two
 * different scales, while the app's three recording paths that share a scale
 * also share everything they have learned. See [MicPeakSource.profileKey].
 */
class MicProfileStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("mic_profiles", Context.MODE_PRIVATE)

    fun load(key: String): MicProfile? {
        val floor = prefs.getFloat("$key.floor", Float.NaN)
        val speech = prefs.getFloat("$key.speech", Float.NaN)
        if (floor.isNaN() || speech.isNaN()) return null
        return MicProfile(floor, speech).sane()
    }

    /**
     * Whether this device's `MediaRecorder` meter is known to be dead.
     *
     * Worth remembering for exactly one reason: without it every recording
     * spends its opening probing an encoder that has already been proven not to
     * answer, and that probe is visible as a waveform that starts late.
     */
    fun recorderMeterDead(): Boolean = prefs.getBoolean(KEY_METER_DEAD, false)

    fun setRecorderMeterDead(dead: Boolean) {
        if (dead == recorderMeterDead()) return
        prefs.edit().putBoolean(KEY_METER_DEAD, dead).apply()
    }

    /** Merges one recording's measurement into the stored profile. */
    fun record(key: String, observed: MicProfile): MicProfile {
        val merged = observed.mergedInto(load(key))
        prefs.edit()
            .putFloat("$key.floor", merged.floorDb)
            .putFloat("$key.speech", merged.speechDb)
            .apply()
        return merged
    }

    private companion object {
        const val KEY_METER_DEAD = "recorder_meter_dead"
    }
}
