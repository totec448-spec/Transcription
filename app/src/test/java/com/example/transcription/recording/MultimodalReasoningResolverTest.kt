package com.example.transcription.recording

import com.example.transcription.data.TranscriptionModel
import com.example.transcription.data.TranscriptionProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultimodalReasoningResolverTest {
    private val mandatoryModel = TranscriptionModel(
        id = "openrouter-multimodal/google/gemini-3.5-flash",
        name = "Google: Gemini 3.5 Flash",
        description = "",
        pricePerHourUsd = null,
        provider = TranscriptionProvider.OPENROUTER_MULTIMODAL,
        reasoningEfforts = listOf("high", "medium", "low", "minimal"),
        defaultReasoningEffort = "medium",
        reasoningMandatory = true
    )

    @Test fun mandatoryModelNeverReceivesNone() {
        assertNull(
            MultimodalReasoningResolver.resolve(
                mandatoryModel.id,
                "none",
                listOf(mandatoryModel)
            ).effort
        )
    }

    @Test fun explicitSupportedEffortIsPreserved() {
        assertEquals(
            ResolvedMultimodalReasoning(include = true, effort = "minimal"),
            MultimodalReasoningResolver.resolve(
                mandatoryModel.id,
                "minimal",
                listOf(mandatoryModel)
            )
        )
    }

    @Test fun unsupportedEffortFallsBackToCatalogDefault() {
        assertEquals(
            ResolvedMultimodalReasoning(include = true, effort = "medium"),
            MultimodalReasoningResolver.resolve(
                mandatoryModel.id,
                "max",
                listOf(mandatoryModel)
            )
        )
    }

    @Test fun unsupportedReasoningParameterIsOmittedEntirely() {
        val plainAudioModel = mandatoryModel.copy(
            id = "openrouter-multimodal/mistralai/voxtral-small-24b-2507",
            reasoningEfforts = emptyList(),
            defaultReasoningEffort = null,
            reasoningMandatory = false,
            supportedParameters = emptySet()
        )
        assertEquals(
            ResolvedMultimodalReasoning(include = false, effort = null),
            MultimodalReasoningResolver.resolve(
                plainAudioModel.id,
                "auto",
                listOf(plainAudioModel)
            )
        )
    }
}
