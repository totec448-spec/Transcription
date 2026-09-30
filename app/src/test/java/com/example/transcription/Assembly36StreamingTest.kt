package com.example.transcription

import com.example.transcription.data.*
import org.junit.Assert.*
import org.junit.Test

class Assembly36StreamingTest {
    @Test fun newModelResolvesToStreamingApiIdAndOnlyAppearsInKeyboardCatalog() {
        val id = ProviderModels.ASSEMBLYAI_UNIVERSAL_3_6_PRO_STREAMING
        assertTrue(ProviderModels.isStreaming(id))
        assertEquals("universal-3-6-pro", ProviderModels.assemblyAiId(id))
        assertEquals(TranscriptionProvider.ASSEMBLYAI, ProviderModels.provider(id))
        val model = ModelCatalogRepository.fallbackModels.first { it.id == id }
        assertTrue(model.streaming)
        assertEquals(0.45, model.pricePerHourUsd!!, 0.0001)
        assertFalse(BrowserRenderingPolicy.appModels(listOf(model)).contains(model))
        assertEquals(32, ModelLanguageCatalog.supportedCodes(id)!!.size)
        assertTrue(ModelLanguageCatalog.supports(id, "de"))
    }
}
