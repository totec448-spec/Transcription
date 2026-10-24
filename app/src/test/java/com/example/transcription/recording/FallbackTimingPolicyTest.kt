package com.example.transcription.recording

import org.junit.Assert.*
import org.junit.Test

class FallbackTimingPolicyTest {
    @Test fun zeroSkipsPrimaryOnlyWhenFallbackIsUsable() {
        assertFalse(FallbackTimingPolicy.attemptPrimary(true, false, 0))
        assertTrue(FallbackTimingPolicy.attemptPrimary(false, false, 0))
        assertTrue(FallbackTimingPolicy.attemptPrimary(false, true, 0))
    }
    @Test fun shortPrimaryBudgetNeverShortensFallback() {
        assertEquals(1, FallbackTimingPolicy.requestSeconds(true, true, 1, 60))
        assertEquals(60, FallbackTimingPolicy.requestSeconds(false, true, 1, 60))
        assertEquals(60, FallbackTimingPolicy.requestSeconds(true, false, 0, 60))
        assertEquals(20, FallbackTimingPolicy.requestSeconds(true, true, 20, 60))
    }
    @Test fun explicitFallbackRequestSkipsPrimary() {
        assertFalse(FallbackTimingPolicy.attemptPrimary(true, true, 20))
        assertTrue(FallbackTimingPolicy.attemptPrimary(true, false, 20))
    }
}
