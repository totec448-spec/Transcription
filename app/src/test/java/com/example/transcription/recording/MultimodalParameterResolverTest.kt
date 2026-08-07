package com.example.transcription.recording

import com.example.transcription.data.TranscriptionModel
import com.example.transcription.data.TranscriptionProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalParameterResolverTest {
    @Test
    fun sendsTemperatureOnlyWhenLiveCatalogAdvertisesIt() {
        val id = "openrouter-multimodal/example/audio-model"
        val model = TranscriptionModel(
            id = id,
            name = "Audio model",
            description = "",
            pricePerHourUsd = null,
            provider = TranscriptionProvider.OPENROUTER_MULTIMODAL,
            supportedParameters = setOf("temperature", "reasoning")
        )

        assertTrue(MultimodalParameterResolver.supportsTemperature(id, listOf(model)))
        assertFalse(
            MultimodalParameterResolver.supportsTemperature(
                id,
                listOf(model.copy(supportedParameters = setOf("reasoning")))
            )
        )
        assertFalse(MultimodalParameterResolver.supportsTemperature("elevenlabs/scribe-v2", listOf(model)))
    }
}
