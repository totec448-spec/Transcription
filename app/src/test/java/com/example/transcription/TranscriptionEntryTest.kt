package com.example.transcription

import com.example.transcription.data.TranscriptionEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionEntryTest {
    @Test
    fun failureStatus_requiresANonBlankFailureMessage() {
        val base = TranscriptionEntry("id", "", 1L, 2L, "model", "/audio.m4a")

        assertFalse(base.transcriptionFailed)
        assertFalse(base.copy(failureMessage = "   ").transcriptionFailed)
        assertTrue(base.copy(failureMessage = "Provider timed out").transcriptionFailed)
    }
}
