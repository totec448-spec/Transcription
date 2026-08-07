package com.example.transcription

import com.example.transcription.data.ModelLanguageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLanguageCatalogTest {
    @Test fun usesPublishedModelFamilyProfiles() {
        assertEquals(11, ModelLanguageCatalog.supportedCodes("qwen/qwen3-asr-flash")?.size)
        assertEquals(25, ModelLanguageCatalog.supportedCodes("nvidia/parakeet-tdt-0.6b-v3")?.size)
        assertEquals(13, ModelLanguageCatalog.supportedCodes("mistralai/voxtral-mini-transcribe")?.size)
        assertTrue(ModelLanguageCatalog.supportedCodes("openai/whisper-large-v3")!!.contains("de"))
    }

    @Test fun rejectsUnsupportedHintsAndLeavesUnknownModelsOpen() {
        assertFalse(ModelLanguageCatalog.supports("qwen/qwen3-asr-flash", "nl"))
        assertTrue(ModelLanguageCatalog.supports("qwen/qwen3-asr-flash", "de"))
        assertTrue(ModelLanguageCatalog.supports("qwen/qwen3-asr-flash", "auto"))
        assertNull(ModelLanguageCatalog.supportedCodes("future/provider-model"))
        assertTrue(ModelLanguageCatalog.supports("future/provider-model", "nl"))
    }
}
