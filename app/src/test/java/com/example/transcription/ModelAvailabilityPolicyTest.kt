package com.example.transcription

import com.example.transcription.data.BaseCleanupMode
import com.example.transcription.data.ModelAvailabilityPolicy
import com.example.transcription.data.ProviderModels
import com.example.transcription.data.TranscriptionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelAvailabilityPolicyTest {
    private val openRouter = setOf(TranscriptionProvider.OPENROUTER_STT)
    private val elevenLabs = setOf(TranscriptionProvider.ELEVENLABS)
    private val assemblyAi = setOf(TranscriptionProvider.ASSEMBLYAI)
    private val all = openRouter + elevenLabs + assemblyAi

    @Test
    fun preferredModelSurvivesWhenItsKeyExists() {
        assertEquals(
            ProviderModels.ELEVENLABS_SCRIBE_V2,
            ModelAvailabilityPolicy.resolvePrimary(ProviderModels.ELEVENLABS_SCRIBE_V2, all)
        )
        assertEquals(
            ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO,
            ModelAvailabilityPolicy.resolvePrimary(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO, all)
        )
    }

    @Test
    fun singleKeyInstallsSwitchToThatProvidersModel() {
        assertEquals(
            ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO,
            ModelAvailabilityPolicy.resolvePrimary(ProviderModels.ELEVENLABS_SCRIBE_V2, assemblyAi)
        )
        assertEquals(
            ProviderModels.ELEVENLABS_SCRIBE_V2,
            ModelAvailabilityPolicy.resolvePrimary(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO, elevenLabs)
        )
        assertEquals(
            ProviderModels.OPENROUTER_GPT_TRANSCRIBE,
            ModelAvailabilityPolicy.resolvePrimary(ProviderModels.ELEVENLABS_SCRIBE_V2, openRouter)
        )
    }

    /** Every automatic pick must be a batch model, in the app and the keyboard. */
    @Test
    fun noPreferredModelIsAStreamingModel() {
        ModelAvailabilityPolicy.preferenceOrder.forEach { (_, id) ->
            assert(!ProviderModels.isStreaming(id)) { "$id is a streaming model" }
        }
    }

    @Test
    fun cleanupIsOffWithoutAnOpenRouterKey() {
        assertEquals(
            BaseCleanupMode.OFF,
            ModelAvailabilityPolicy.effectiveBaseCleanupMode(BaseCleanupMode.CLEAN, elevenLabs)
        )
        assertEquals(
            BaseCleanupMode.OFF,
            ModelAvailabilityPolicy.effectiveBaseCleanupMode(BaseCleanupMode.POLISH, assemblyAi)
        )
        assertEquals(
            BaseCleanupMode.OFF,
            ModelAvailabilityPolicy.effectiveBaseCleanupMode(BaseCleanupMode.CLEAN, emptySet())
        )
    }

    @Test
    fun theStoredCleanupChoiceReturnsWithAnOpenRouterKey() {
        assertEquals(
            BaseCleanupMode.POLISH,
            ModelAvailabilityPolicy.effectiveBaseCleanupMode(BaseCleanupMode.POLISH, openRouter)
        )
        assertEquals(
            BaseCleanupMode.OFF,
            ModelAvailabilityPolicy.effectiveBaseCleanupMode(BaseCleanupMode.OFF, all)
        )
    }

    @Test
    fun noKeysKeepsTheStoredChoiceUntouched() {
        assertEquals(
            ProviderModels.ELEVENLABS_SCRIBE_V2,
            ModelAvailabilityPolicy.resolvePrimary(ProviderModels.ELEVENLABS_SCRIBE_V2, emptySet())
        )
    }

    @Test
    fun defaultFallbackUsesWhisperTurboWhileOpenRouterIsReachable() {
        assertEquals(
            ProviderModels.OPENROUTER_WHISPER_V3_TURBO,
            ModelAvailabilityPolicy.resolveFallback(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO, all)
        )
        assertEquals(
            ProviderModels.OPENROUTER_WHISPER_V3_TURBO,
            ModelAvailabilityPolicy.resolveFallback(ProviderModels.ELEVENLABS_SCRIBE_V2, openRouter + elevenLabs)
        )
    }

    @Test
    fun providerOnlyInstallsGetNoFallbackAtAll() {
        assertEquals("", ModelAvailabilityPolicy.resolveFallback(ProviderModels.ELEVENLABS_SCRIBE_V2, elevenLabs))
        assertEquals(
            "",
            ModelAvailabilityPolicy.resolveFallback(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO, assemblyAi)
        )
    }

    @Test
    fun theFallbackNeverDuplicatesThePrimary() {
        assertEquals(
            "",
            ModelAvailabilityPolicy.resolveFallback(ProviderModels.OPENROUTER_WHISPER_V3_TURBO, all)
        )
    }
    @Test
    fun explicitlySelectedFallbackWinsOverWhisperTurbo() {
        assertEquals(ProviderModels.ELEVENLABS_SCRIBE_V2,
            ModelAvailabilityPolicy.resolveFallback(ProviderModels.OPENROUTER_GPT_TRANSCRIBE, all,
                ProviderModels.ELEVENLABS_SCRIBE_V2))
        assertEquals(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO,
            ModelAvailabilityPolicy.resolveFallback(ProviderModels.ELEVENLABS_SCRIBE_V2, all,
                ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO))
        assertEquals(ProviderModels.OPENROUTER_GPT_TRANSCRIBE,
            ModelAvailabilityPolicy.resolveFallback(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO, all,
                ProviderModels.OPENROUTER_GPT_TRANSCRIBE))
    }

    @Test
    fun unavailableOrDuplicateSavedFallbackIsDisabled() {
        assertEquals("", ModelAvailabilityPolicy.resolveFallback(ProviderModels.OPENROUTER_GPT_TRANSCRIBE,
            openRouter, ProviderModels.ELEVENLABS_SCRIBE_V2))
        assertEquals("", ModelAvailabilityPolicy.resolveFallback(ProviderModels.ELEVENLABS_SCRIBE_V2,
            all, ProviderModels.ELEVENLABS_SCRIBE_V2))
    }

    @Test
    fun selectedFallbackWorksWithoutAnOpenRouterKey() {
        assertEquals(ProviderModels.ELEVENLABS_SCRIBE_V2,
            ModelAvailabilityPolicy.resolveFallback(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO,
                assemblyAi + elevenLabs, ProviderModels.ELEVENLABS_SCRIBE_V2))
    }

}
