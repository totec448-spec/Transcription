package com.example.transcription.recording

import com.example.transcription.data.ProviderModels
import com.example.transcription.data.SendAudioFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wire format is decided in one place now that trimming re-encodes ahead of
 * chunking. If these two disagreed the file would be converted twice, and the
 * MAI rule in particular would be silently lost on any trimmed recording.
 */
class AudioSendFormatTest {
    @Test
    fun maiAlwaysReceivesMp3EvenWhenTheRecordingIsM4a() {
        assertEquals(
            "mp3",
            AudioSendPreparer.wireFormat(ProviderModels.OPENROUTER_MAI_1_5, "m4a", SendAudioFormat.AUTO)
        )
    }

    @Test
    fun maiStillReceivesMp3ThroughTheOpenRouterPrefix() {
        assertEquals(
            "mp3",
            AudioSendPreparer.wireFormat(
                "openrouter-multimodal/${ProviderModels.OPENROUTER_MAI_1_5}",
                "m4a",
                SendAudioFormat.AUTO
            )
        )
    }

    @Test
    fun otherModelsKeepACompatibleRecordingAsItIs() {
        assertEquals(
            "m4a",
            AudioSendPreparer.wireFormat(ProviderModels.OPENROUTER_WHISPER_LARGE_V3, "m4a", SendAudioFormat.AUTO)
        )
        assertEquals(
            "mp3",
            AudioSendPreparer.wireFormat(ProviderModels.OPENROUTER_WHISPER_LARGE_V3, "mp3", SendAudioFormat.AUTO)
        )
    }

    @Test
    fun anUnknownSourceFormatFallsBackToTheModelDefault() {
        assertEquals(
            "m4a",
            AudioSendPreparer.wireFormat(ProviderModels.ELEVENLABS_SCRIBE_V2, "wav", SendAudioFormat.AUTO)
        )
    }

    @Test
    fun anExplicitChoiceOverridesEverythingIncludingMai() {
        assertEquals(
            "m4a",
            AudioSendPreparer.wireFormat(ProviderModels.OPENROUTER_MAI_1_5, "mp3", SendAudioFormat.M4A)
        )
        assertEquals(
            "mp3",
            AudioSendPreparer.wireFormat(ProviderModels.ELEVENLABS_SCRIBE_V2, "m4a", SendAudioFormat.MP3)
        )
    }
}
