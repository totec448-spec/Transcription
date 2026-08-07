package com.example.transcription.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingResultPolicyTest {
    @Test
    fun ordinaryRecordingStillHonorsAutomaticCopySetting() {
        assertTrue(RecordingResultPolicy.shouldCopyAutomatically(true, false))
        assertFalse(RecordingResultPolicy.shouldCopyAutomatically(false, false))
    }

    @Test
    fun imeRequestNeverCopiesEvenWhenAutomaticCopyIsEnabled() {
        assertFalse(RecordingResultPolicy.shouldCopyAutomatically(true, true))
    }
}
