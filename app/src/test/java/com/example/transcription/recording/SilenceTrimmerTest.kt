package com.example.transcription.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceTrimmerTest {
    @Test
    fun `threshold sits under the measured level`() {
        // The recording that exposed the bug: a running fan put the floor near
        // -43 dB, and the old fixed -50 dB threshold cut 0.4% of six minutes
        // that were half pauses.
        assertEquals(-41, SilenceTrimmer.thresholdFor(-31.14))
    }

    @Test
    fun `a quiet capture moves the threshold down with it`() {
        assertEquals(-48, SilenceTrimmer.thresholdFor(-44.0))
        assertTrue(SilenceTrimmer.thresholdFor(-44.0) < SilenceTrimmer.thresholdFor(-31.0))
    }

    @Test
    fun `an unusable measurement falls back instead of cutting blind`() {
        assertEquals(-40, SilenceTrimmer.thresholdFor(null))
        assertEquals(-40, SilenceTrimmer.thresholdFor(Double.NEGATIVE_INFINITY))
        assertEquals(-40, SilenceTrimmer.thresholdFor(Double.NaN))
    }

    @Test
    fun `a loud recording never lifts the threshold into speech`() {
        assertEquals(-34, SilenceTrimmer.thresholdFor(-6.0))
    }

    @Test
    fun `the threshold reaches the filter chain`() {
        val chain = SilenceTrimmer.filterChain(-41)
        assertTrue(chain.contains("start_threshold=-41dB"))
        assertTrue(chain.contains("stop_threshold=-41dB"))
        // Silence comes out before the level is touched: normalizing first
        // lifts the room tone toward speech and leaves nothing to remove.
        assertTrue(chain.indexOf("silenceremove") < chain.indexOf("speechnorm"))
    }

    @Test
    fun `too short to be worth an encode pass`() {
        assertFalse(SilenceTrimmer.shouldTrim(true, 3_999L))
        assertTrue(SilenceTrimmer.shouldTrim(true, 4_000L))
        assertFalse(SilenceTrimmer.shouldTrim(false, 600_000L))
    }
}
