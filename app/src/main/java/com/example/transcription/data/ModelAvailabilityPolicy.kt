package com.example.transcription.data

/**
 * Picks the models a given set of API keys can actually reach.
 *
 * A stored model whose provider has no key would otherwise fail on every
 * request until the user noticed and changed it by hand. This keeps the choice
 * honest instead: the preferred model wins whenever its key exists, and
 * otherwise the best model of a provider that is actually usable takes over.
 *
 * The intended result is that one key is enough to be finished setting up.
 */
object ModelAvailabilityPolicy {
    /**
     * Best batch model per provider, in the order they are preferred.
     *
     * Every entry is the model that provider should be represented by when it
     * is the only one with a key, so a single-key install lands on that
     * provider's strongest transcription model rather than on whatever the
     * shipped default happened to be. None of these may be a streaming model —
     * the app and the keyboard both resolve batch recordings through here.
     */
    val preferenceOrder = listOf(
        TranscriptionProvider.ASSEMBLYAI to ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO,
        TranscriptionProvider.ELEVENLABS to ProviderModels.ELEVENLABS_SCRIBE_V2,
        TranscriptionProvider.OPENROUTER_STT to ProviderModels.OPENROUTER_GPT_TRANSCRIBE
    )

    /** No key means no models at all; the caller keeps the stored choice. */
    fun resolvePrimary(preferred: String, available: Set<TranscriptionProvider>): String {
        if (available.isEmpty()) return preferred
        if (providerOf(preferred) in available) return preferred
        return preferenceOrder.firstOrNull { it.first in available }?.second ?: preferred
    }

    /**
     * The fallback is always the cheap OpenRouter Whisper Turbo, so a key-only
     * ElevenLabs or AssemblyAI install has no reachable fallback and must not
     * pretend otherwise — a second failing request only doubles the wait.
     */
    fun resolveFallback(primary: String, available: Set<TranscriptionProvider>): String {
        if (TranscriptionProvider.OPENROUTER_STT !in available) return ""
        if (primary == ProviderModels.OPENROUTER_WHISPER_V3_TURBO) return ""
        return ProviderModels.OPENROUTER_WHISPER_V3_TURBO
    }

    /**
     * Cleanup and the spoken Edit key both run on OpenRouter text models, so an
     * install without that key cannot clean up at all — regardless of which
     * provider transcribes.
     */
    fun cleanupAvailable(available: Set<TranscriptionProvider>) =
        TranscriptionProvider.OPENROUTER_STT in available

    /**
     * The cleanup mode that will actually run, as opposed to the stored
     * preference. Without an OpenRouter key this is always [BaseCleanupMode.OFF]
     * so the UI can state that plainly instead of advertising a Clean pass that
     * silently returns the raw transcript.
     *
     * The stored preference is deliberately left untouched: adding the key later
     * must restore the user's choice rather than leave them switched off.
     */
    fun effectiveBaseCleanupMode(
        preferred: BaseCleanupMode,
        available: Set<TranscriptionProvider>
    ): BaseCleanupMode = if (cleanupAvailable(available)) preferred else BaseCleanupMode.OFF

    private fun providerOf(modelId: String) = when (val provider = ProviderModels.provider(modelId)) {
        // Both OpenRouter model families ride on the same key.
        TranscriptionProvider.OPENROUTER_MULTIMODAL,
        TranscriptionProvider.OPENROUTER_TEXT -> TranscriptionProvider.OPENROUTER_STT
        else -> provider
    }
}
