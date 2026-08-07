package com.example.transcription

import com.example.transcription.data.CatalogRetention
import com.example.transcription.data.TranscriptionModel
import com.example.transcription.data.TranscriptionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogRetentionTest {
    @Test
    fun failedPartialRefreshRetainsOnlyThatProvidersLastKnownGoodModels() {
        val previousOpenRouter = model("openai/whisper", TranscriptionProvider.OPENROUTER_STT)
        val previousEleven = model("elevenlabs/scribe", TranscriptionProvider.ELEVENLABS)

        val retained = CatalogRetention.freshOrPrevious(
            fresh = emptyList(),
            previous = listOf(previousOpenRouter, previousEleven),
            provider = TranscriptionProvider.OPENROUTER_STT
        )

        assertEquals(listOf(previousOpenRouter), retained)
    }

    @Test
    fun successfulProviderRefreshReplacesItsPreviousSlice() {
        val fresh = model("openai/new", TranscriptionProvider.OPENROUTER_STT)
        val retained = CatalogRetention.freshOrPrevious(
            fresh = listOf(fresh),
            previous = listOf(model("openai/old", TranscriptionProvider.OPENROUTER_STT)),
            provider = TranscriptionProvider.OPENROUTER_STT
        )

        assertEquals(listOf(fresh), retained)
    }

    private fun model(id: String, provider: TranscriptionProvider) =
        TranscriptionModel(id, id, "", null, provider = provider)
}
