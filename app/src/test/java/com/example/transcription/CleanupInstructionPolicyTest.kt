package com.example.transcription

import com.example.transcription.recording.CleanupInstructionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupInstructionPolicyTest {
    @Test
    fun punctuationAndSilenceArtifactsDoNotCountAsWords() {
        assertEquals(0, CleanupInstructionPolicy.meaningfulWordCount(" . … !!! -- "))
        assertFalse(CleanupInstructionPolicy.shouldRequestRewrite("...", minimumWords = 8))
    }

    @Test
    fun unicodeWordsNumbersAndCompoundsAreCounted() {
        assertEquals(
            6,
            CleanupInstructionPolicy.meaningfulWordCount(
                "Ändere Nutzer-Text auf Version 2 bitte"
            )
        )
    }

    @Test
    fun instructionBelowConfiguredMinimumIsDiscarded() {
        assertFalse(
            CleanupInstructionPolicy.shouldRequestRewrite(
                "Bitte formuliere diesen Absatz freundlicher und klarer",
                minimumWords = 8
            )
        )
    }

    @Test
    fun instructionAtConfiguredMinimumRequestsRewrite() {
        assertTrue(
            CleanupInstructionPolicy.shouldRequestRewrite(
                "Bitte formuliere diesen Absatz freundlicher und deutlich klarer",
                minimumWords = 8
            )
        )
    }

    @Test
    fun zeroDisablesTheMinimumFilter() {
        assertTrue(CleanupInstructionPolicy.shouldRequestRewrite(".", minimumWords = 0))
    }
}
