package com.example.transcription.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicLevelNormalizerTest {
    private fun feed(normalizer: MicLevelNormalizer, level: Float, frames: Int): Float {
        var last = 0f
        repeat(frames) { last = normalizer.accept(level) }
        return last
    }

    @Test
    fun aVeryQuietMicrophoneStillMovesTheWaveform() {
        val normalizer = MicLevelNormalizer()
        // Roughly -46 dBFS room tone and -26 dBFS speech: a whole scale that
        // the old fixed 48 dB window rendered as a barely moving line.
        feed(normalizer, 0.005f, 200)
        val speech = feed(normalizer, 0.05f, 12)

        assertTrue("quiet speech has to be clearly visible, was $speech", speech > 0.35f)
    }

    @Test
    fun ordinarySpeechDoesNotPinTheWaveformToFullScale() {
        // The regression this whole class exists for: a reference that follows
        // speech within a syllable is always equal to it, so every spoken frame
        // renders as maximum and the waveform only knows silence and full.
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.005f, 200)
        val speech = feed(normalizer, 0.05f, 40)

        assertTrue("speaking must not sit at the ceiling, was $speech", speech < 0.75f)
        assertTrue("but it must be unmistakable, was $speech", speech > 0.35f)
    }

    @Test
    fun aRaisedVoiceIsVisiblyLouderThanAnOrdinaryOne() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.005f, 200)
        val ordinary = feed(normalizer, 0.05f, 40)
        // 14 dB over what this microphone has been hearing as speech.
        val raised = feed(normalizer, 0.25f, 12)

        assertTrue("a raised voice needs headroom left, was $raised vs $ordinary", raised > ordinary + 0.1f)
        assertTrue("and it still may not clip flat, was $raised", raised < 1f)
    }

    @Test
    fun aLoudMicrophoneIsNotClippedIntoAFlatLine() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.05f, 200)
        val speech = feed(normalizer, 0.6f, 12)
        val pause = feed(normalizer, 0.05f, 40)

        assertTrue("loud speech should read high, was $speech", speech > 0.5f)
        assertTrue("the pause is rest, was $pause", pause < 0.1f)
    }

    @Test
    fun speechIsDetectedRelativeToTheRoomRatherThanAnAbsoluteLevel() {
        val quiet = MicLevelNormalizer()
        feed(quiet, 0.004f, 200)
        assertFalse("room tone is not speech", quiet.speaking)
        feed(quiet, 0.04f, 4)
        assertTrue("speech 20 dB over a quiet room must register", quiet.speaking)
    }

    @Test
    fun digitalSilenceNeverRegistersAsSpeech() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0f, 100)
        assertFalse(normalizer.speaking)
        assertEquals(0f, normalizer.accept(0f), 0.001f)

        // Even dither far above a floor of pure zeroes stays below the absolute
        // ceiling, so a muted microphone cannot hold the gate open.
        feed(normalizer, 0.0001f, 10)
        assertFalse(normalizer.speaking)
    }

    @Test
    fun aRoomIsRestNoMatterHowLoudTheRoomIs() {
        // A steady background must render as a flat line whether it is a silent
        // bedroom or a 60 dB office, which is the whole point of measuring the
        // floor: it is the only thing present, but it is not the subject.
        val quiet = MicLevelNormalizer()
        val loud = MicLevelNormalizer()
        val quietLevel = feed(quiet, 0.002f, 300)
        val loudLevel = feed(loud, 0.08f, 300)

        assertTrue("a quiet room is rest, was $quietLevel", quietLevel < 0.05f)
        assertTrue("a loud room is rest too, was $loudLevel", loudLevel < 0.05f)
        assertFalse(quiet.speaking)
        assertFalse(loud.speaking)
    }

    @Test
    fun thePauseAfterSpeechIsRecognizedInsideTheGateWindow() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.006f, 200)
        feed(normalizer, 0.08f, 40)
        assertTrue(normalizer.speaking)

        // The live gate closes after 14 frames of 50 ms, so the drop has to be
        // recognized well inside that.
        feed(normalizer, 0.006f, 6)
        assertFalse("the floor must catch up inside the gate window", normalizer.speaking)
    }

    @Test
    fun theMicrophoneWarmingUpDoesNotDefineTheRoom() {
        val normalizer = MicLevelNormalizer()
        // Both capture paths open with empty frames. A floor primed on those
        // sits at the bottom of the scale and never climbs back, which read as
        // a permanently full waveform and as speech that never stops.
        feed(normalizer, 0f, 6)
        val room = feed(normalizer, 0.006f, 40)

        assertFalse("room tone after a silent start is not speech", normalizer.speaking)
        assertTrue("room tone should sit at rest, was $room", room < 0.1f)

        val speech = feed(normalizer, 0.06f, 12)
        assertTrue("speech still deflects properly, was $speech", speech > 0.35f)
    }

    @Test
    fun aRoomThatGetsLouderIsFollowedWithinSeconds() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.002f, 200)
        // Something switched on: 20 dB more room tone, no speech.
        val settled = feed(normalizer, 0.02f, 300)

        assertTrue("the floor has to climb to the new room, was $settled", settled < 0.1f)
        assertFalse("louder room tone is still not speech", normalizer.speaking)
    }

    @Test
    fun aLongSentenceDoesNotDragTheFloorIntoItself() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.006f, 200)
        // Fifteen seconds of talking, the worst case for a floor that rises on
        // anything above it. Syllables, not a tone: speech is loud for a few
        // frames at a time and drops between words, which is the only thing
        // that distinguishes it from a machine that switched on.
        var level = 0f
        repeat(33) {
            feed(normalizer, 0.012f, 3)
            level = feed(normalizer, 0.08f, 6)
        }

        assertTrue("speech must stay visible for the whole sentence, was $level", level > 0.3f)
        assertTrue(normalizer.speaking)
    }

    @Test
    fun aLongRecordingDoesNotFadeOnTheBatchPath() {
        // The reported regression. The batch recorder reports the *peak* of each
        // 200 ms slice, so the gaps between words never show up as quiet frames
        // — every frame of a spoken minute is a syllable. A floor that rises
        // toward what it hears therefore climbs into the speech, the knee
        // follows, and the waveform dies about fifteen seconds in.
        val normalizer = MicLevelNormalizer(200)
        // Five seconds of talking as that path sees it: four seconds of
        // syllables that never once drop to the room, because a 200 ms peak
        // spans whole words, and then a breath.
        fun aSentence(): Float {
            var loudest = 0f
            repeat(4) {
                for (level in floatArrayOf(0.05f, 0.09f, 0.06f, 0.10f, 0.07f)) {
                    loudest = maxOf(loudest, normalizer.accept(level))
                }
            }
            feed(normalizer, 0.006f, 4)
            return loudest
        }

        feed(normalizer, 0.006f, 50)
        val early = aSentence()
        var late = 0f
        repeat(11) { late = aSentence() }

        assertTrue("a minute in, speech must still deflect, was $late", late > 0.3f)
        assertTrue("and not have faded away, was $late against $early", late > early * 0.6f)

        // And the floor that survived a minute of talking still lets go of it.
        feed(normalizer, 0.10f, 4)
        assertTrue("it is still speech", normalizer.speaking)
        val pause = feed(normalizer, 0.006f, 8)
        assertFalse("the pause after a long recording still reads as one", normalizer.speaking)
        assertTrue("and renders as rest, was $pause", pause < 0.1f)
    }

    @Test
    fun theSameSignalReadsTheSameAtEitherFrameRate() {
        val live = MicLevelNormalizer(50)
        val batch = MicLevelNormalizer(200)
        // Ten seconds of room tone, then one second of speech, fed at the live
        // stream's rate and at the batch recorder's.
        feed(live, 0.005f, 200)
        feed(batch, 0.005f, 50)
        val liveLevel = feed(live, 0.05f, 20)
        val batchLevel = feed(batch, 0.05f, 5)

        assertEquals(batchLevel, liveLevel, 0.05f)
        assertEquals(live.speaking, batch.speaking)
    }

    @Test
    fun aSingleLoudFrameDoesNotSnapTheWaveformToFullScale() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.005f, 200)
        val spike = normalizer.accept(0.5f)

        assertTrue("one frame should only move part of the way, was $spike", spike < 0.7f)
        assertTrue("but it has to be visible, was $spike", spike > 0.15f)
    }

    @Test
    fun aStoredProfileMetersTheFirstWordCorrectly() {
        // What the last recording on this microphone measured: -46 dBFS of
        // room, speech 20 dB over it.
        val primed = MicLevelNormalizer(profile = MicProfile(-46f, -26f))
        val cold = MicLevelNormalizer()

        // Somebody starts talking immediately, with no room tone in front of it.
        val withProfile = feed(primed, 0.05f, 4)
        val withoutProfile = feed(cold, 0.05f, 4)

        assertTrue("a known microphone deflects at once, was $withProfile", withProfile > 0.3f)
        assertTrue(
            "an unknown one has to take the opening as its floor, was $withoutProfile",
            withoutProfile < withProfile
        )
    }

    @Test
    fun nothingIsLearnedFromARecordingWithoutSpeech() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.005f, 200)

        assertNull("a floor alone is not a calibration", normalizer.observed())

        feed(normalizer, 0.05f, 40)
        val observed = normalizer.observed()
        assertNotNull("two seconds of speech is a measurement", observed)
        assertEquals("speech sat 20 dB over the room", 20f, observed!!.marginDb, 4f)
    }

    @Test
    fun peakScanReadsTheLoudestSampleInABuffer() {
        val bytes = ByteArray(8)
        // Little-endian PCM16: 0, 16384, -32768, 100.
        bytes[2] = 0x00; bytes[3] = 0x40
        bytes[4] = 0x00; bytes[5] = 0x80.toByte()
        bytes[6] = 0x64; bytes[7] = 0x00

        assertEquals(1f, MicLevelNormalizer.peakOfPcm16(bytes, 8), 0.001f)
        assertEquals(0.5f, MicLevelNormalizer.peakOfPcm16(bytes, 4), 0.001f)
    }

    @Test
    fun resetForgetsThePreviousMicrophone() {
        val normalizer = MicLevelNormalizer()
        feed(normalizer, 0.5f, 100)
        normalizer.reset()

        // A fresh quiet source must not be measured against the loud one.
        feed(normalizer, 0.004f, 200)
        val speech = feed(normalizer, 0.04f, 12)
        assertTrue("after reset the quiet source normalizes on its own, was $speech", speech > 0.35f)
    }
}
