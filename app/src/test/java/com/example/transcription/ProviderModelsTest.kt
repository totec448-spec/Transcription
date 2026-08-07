package com.example.transcription

import com.example.transcription.data.ProviderModels
import com.example.transcription.data.TranscriptionProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsTest {
    @Test fun maiAutomaticallyUsesMp3() {
        assertEquals("mp3", ProviderModels.automaticWireFormat(ProviderModels.OPENROUTER_MAI_1_5))
    }

    @Test fun otherOpenRouterModelsDefaultToM4a() {
        assertEquals("m4a", ProviderModels.automaticWireFormat("openai/whisper-large-v3"))
    }

    @Test fun providerRoutesAreStable() {
        assertEquals(TranscriptionProvider.ELEVENLABS, ProviderModels.provider(ProviderModels.ELEVENLABS_SCRIBE_V2))
        assertEquals(
            TranscriptionProvider.ASSEMBLYAI,
            ProviderModels.provider(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO)
        )
        assertEquals(
            TranscriptionProvider.OPENROUTER_MULTIMODAL,
            ProviderModels.provider("openrouter-multimodal/google/gemini-flash-latest")
        )
        assertEquals(
            TranscriptionProvider.OPENROUTER_TEXT,
            ProviderModels.provider(ProviderModels.OPENROUTER_DEEPSEEK_V4_PRO)
        )
    }

    @Test fun providerCatalogIdsMapToWireIds() {
        assertEquals("scribe_v2", ProviderModels.elevenLabsId(ProviderModels.ELEVENLABS_SCRIBE_V2))
        assertEquals(
            "scribe_v3_realtime",
            ProviderModels.elevenLabsId("elevenlabs/scribe_v3_realtime")
        )
        assertEquals(
            "universal-streaming-multilingual",
            ProviderModels.assemblyAiId("assemblyai/live/universal-streaming-multilingual")
        )
    }

    @Test fun streamingModelsAreSeparatedFromBatchModels() {
        assertTrue(ProviderModels.isStreaming(ProviderModels.ELEVENLABS_SCRIBE_V2_REALTIME))
        assertTrue(ProviderModels.isStreaming("assemblyai/live/whisper-rt"))
        assertFalse(ProviderModels.isStreaming(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO))
    }
}
