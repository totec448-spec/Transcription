package com.example.transcription.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicProfileTest {
    @Test
    fun theFirstMeasurementIsAdoptedAsIs() {
        val observed = MicProfile(-46f, -26f)
        val merged = observed.mergedInto(null)

        assertEquals(-46f, merged.floorDb, 0.01f)
        assertEquals(20f, merged.marginDb, 0.01f)
    }

    @Test
    fun oneOddRecordingOnlyNudgesTheLearnedMargin() {
        val learned = MicProfile(-46f, -26f)
        // One note shouted into the phone: speech 34 dB over the room.
        val shouted = MicProfile(-46f, -12f)
        val merged = shouted.mergedInto(learned)

        assertTrue("the margin has to move toward it, was ${merged.marginDb}", merged.marginDb > 20f)
        assertTrue("but nowhere near all the way, was ${merged.marginDb}", merged.marginDb < 25f)
    }

    @Test
    fun aChangedRoomIsFollowedQuickly() {
        val learned = MicProfile(-60f, -40f)
        // Same phone, same voice, a much louder room.
        val merged = MicProfile(-40f, -20f).mergedInto(learned)

        assertTrue("the floor is where the phone is, was ${merged.floorDb}", merged.floorDb > -52f)
        assertEquals("the microphone did not change", 20f, merged.marginDb, 0.5f)
    }

    @Test
    fun nonsenseCannotBeStored() {
        // A margin measured on room tone alone, and one measured on clipping.
        assertEquals(
            MicProfile.MIN_MARGIN_DB,
            MicProfile(-50f, -48f).sane().marginDb,
            0.01f
        )
        assertEquals(
            MicProfile.MAX_MARGIN_DB,
            MicProfile(-90f, 0f).sane().marginDb,
            0.01f
        )
        assertTrue(MicProfile(-200f, -100f).sane().floorDb >= MicProfile.MIN_FLOOR_DB)
    }
}
