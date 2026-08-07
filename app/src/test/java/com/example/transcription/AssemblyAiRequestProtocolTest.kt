package com.example.transcription

import com.example.transcription.network.AssemblyAiRequestProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssemblyAiRequestProtocolTest {
    @Test
    fun universal35SubmissionOmitsIncompatibleLegacyFormattingFields() {
        val submission = AssemblyAiRequestProtocol.submission(
            audioUrl = "https://cdn.example/audio.m4a",
            providerModelId = "universal-3-5-pro",
            language = "auto"
        )

        assertEquals("https://cdn.example/audio.m4a", submission.audioUrl)
        assertEquals("universal-3-5-pro", submission.providerModelId)
        assertNull(submission.languageCode)
    }

    @Test
    fun explicitLanguageIsStillForwarded() {
        val submission = AssemblyAiRequestProtocol.submission(
            audioUrl = "https://cdn.example/audio.m4a",
            providerModelId = "universal-3-5-pro",
            language = "de"
        )

        assertEquals("de", submission.languageCode)
    }
}
