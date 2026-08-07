package com.example.transcription.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscriptionFallbackTest {
    @Test
    fun primarySuccessDoesNotCallFallback() {
        val calls = mutableListOf<String>()

        val result = TranscriptionFallback.run("primary", "fallback") { model ->
            calls += model
            "transcript"
        }

        assertEquals(listOf("primary"), calls)
        assertEquals("primary", result.model)
        assertFalse(result.usedFallback)
        assertEquals("transcript", result.value)
    }

    @Test
    fun primaryErrorCallsFallbackAndReportsModelUsed() {
        val calls = mutableListOf<String>()

        val result = TranscriptionFallback.run("qwen/asr", "openai/transcribe") { model ->
            calls += model
            if (model == "qwen/asr") throw IllegalStateException("audio too long")
            "fallback transcript"
        }

        assertEquals(listOf("qwen/asr", "openai/transcribe"), calls)
        assertEquals("openai/transcribe", result.model)
        assertTrue(result.usedFallback)
        assertEquals("audio too long", result.primaryErrorMessage)
    }

    @Test
    fun bothErrorsAreCondensedIntoOneUsefulMessage() {
        try {
            TranscriptionFallback.run("qwen/asr", "openai/transcribe") { model ->
                if (model == "qwen/asr") throw IllegalStateException("audio   too\nlong")
                throw IllegalArgumentException("provider unavailable")
            }
            fail("Expected both attempts to fail")
        } catch (error: TranscriptionFallbackException) {
            assertEquals(
                "Both transcription models failed. asr: audio too long; transcribe: provider unavailable",
                error.message
            )
        }
    }

    @Test
    fun sameModelIsNotCalledTwice() {
        var calls = 0

        try {
            TranscriptionFallback.run("same", "same") {
                calls += 1
                throw IllegalStateException("failed")
            }
            fail("Expected primary failure")
        } catch (_: IllegalStateException) {
            assertEquals(1, calls)
        }
    }
}
