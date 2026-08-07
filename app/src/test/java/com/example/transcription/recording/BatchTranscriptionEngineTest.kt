package com.example.transcription.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTranscriptionEngineTest {
    @Test
    fun shortAudioStaysSingleRequest() {
        assertEquals(listOf(ChunkRange(0, 42_000)), AudioChunkPlan.ranges(42_000))
    }

    @Test
    fun longAudioUsesBoundedOverlappingParts() {
        val ranges = AudioChunkPlan.ranges(400_000)

        assertEquals(3, ranges.size)
        assertEquals(0, ranges.first().startMs)
        assertEquals(400_000, ranges.last().endMs)
        assertTrue(ranges.all { it.endMs - it.startMs <= AudioChunkPlan.MAX_CHUNK_MS })
        assertEquals(AudioChunkPlan.OVERLAP_MS, ranges[0].endMs - ranges[1].startMs)
    }

    @Test
    fun partsComeOutEvenRatherThanLeavingARemainder() {
        // The trimmed six-minute recording: greedily filled it gave a part of
        // three minutes and a tail of under a minute, and the tail alone was
        // too thin for the provider's language detection.
        val ranges = AudioChunkPlan.ranges(237_200)

        assertEquals(2, ranges.size)
        val lengths = ranges.map { it.endMs - it.startMs }
        assertTrue("uneven parts: $lengths", lengths.max() - lengths.min() <= AudioChunkPlan.OVERLAP_MS)
    }

    @Test
    fun evenPartsStayUnderTheProviderCeiling() {
        // Nine minutes is where evening out the lengths is most tempted to
        // overshoot: three parts would not fit once the overlaps are repaid.
        listOf(180_001L, 237_200L, 400_000L, 540_000L, 1_000_000L).forEach { duration ->
            val ranges = AudioChunkPlan.ranges(duration)

            assertEquals(0, ranges.first().startMs)
            assertEquals(duration, ranges.last().endMs)
            assertTrue(
                "part too long for $duration: $ranges",
                ranges.all { it.endMs - it.startMs <= AudioChunkPlan.MAX_CHUNK_MS }
            )
            ranges.zipWithNext().forEach { (left, right) ->
                assertEquals(AudioChunkPlan.OVERLAP_MS, left.endMs - right.startMs)
            }
        }
    }

    @Test
    fun transcriptJoinerRemovesOverlapWithoutLosingPunctuation() {
        val joined = TranscriptJoiner.join(
            listOf(
                "This is the end of part one.",
                "the end of part one. And this is part two."
            )
        )

        assertEquals("This is the end of part one. And this is part two.", joined)
    }

    @Test
    fun transcriptJoinerKeepsPartsWithoutOverlap() {
        assertEquals("First sentence. Second sentence.", TranscriptJoiner.join(listOf("First sentence.", "Second sentence.")))
    }
}
