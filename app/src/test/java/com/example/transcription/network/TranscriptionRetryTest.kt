package com.example.transcription.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TranscriptionRetryTest {
    @Test fun recoversFromRateLimitBeforeFallback() {
        var calls = 0
        var clock = 0L
        val result = TranscriptionRetry.run(30_000, { false }, { clock }, { clock += it }) {
            if (++calls == 1) throw ProviderHttpException(429, "2", "limited")
            "transcript"
        }
        assertEquals("transcript", result)
        assertEquals(2, calls)
        assertEquals(2_000L, clock)
    }

    @Test fun persistentRateLimitStopsAfterThreeAttempts() {
        var calls = 0
        var clock = 0L
        try {
            TranscriptionRetry.run(30_000, { false }, { clock }, { clock += it }) {
                calls++
                throw ProviderHttpException(429, null, "limited")
            }
            fail()
        } catch (_: ProviderHttpException) { }
        assertEquals(3, calls)
        assertEquals(3_000L, clock)
    }

    @Test fun longCooldownAndPermanentErrorsGoStraightToFallback() {
        assertNull(TranscriptionRetry.delayMs(429, "60", 0, 0))
        assertNull(TranscriptionRetry.delayMs(402, null, 0, 0))
        assertNull(TranscriptionRetry.delayMs(400, null, 0, 0))
        assertEquals(2_000L, TranscriptionRetry.delayMs(503, "Thu, 1 Jan 1970 00:00:02 GMT", 0, 0))
    }

    @Test fun cancellationDuringBackoffPreventsAnotherUpload() {
        var calls = 0
        var cancelled = false
        try {
            TranscriptionRetry.run(30_000, { cancelled }, { 0L }, { cancelled = true }) {
                calls++
                throw ProviderHttpException(429, null, "limited")
            }
            fail()
        } catch (_: IOException) { }
        assertEquals(1, calls)
    }

    @Test fun ambiguousNetworkFailuresAreNeverRetried() {
        var calls = 0
        try {
            TranscriptionRetry.run(30_000, { false }) {
                calls++
                throw IOException("connection lost")
            }
            fail()
        } catch (_: IOException) { }
        assertEquals(1, calls)
    }

    @Test fun retryWaitCannotExceedRequestBudget() {
        var calls = 0
        try {
            TranscriptionRetry.run(500, { false }, { 0L }, { fail("must not wait") }) {
                calls++
                throw ProviderHttpException(429, "1", "limited")
            }
            fail()
        } catch (_: ProviderHttpException) { }
        assertEquals(1, calls)
    }
}
