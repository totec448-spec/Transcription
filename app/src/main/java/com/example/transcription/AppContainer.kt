package com.example.transcription

import android.content.Context
import com.example.transcription.data.ApiKeyProvider
import com.example.transcription.data.ClipboardHistory
import com.example.transcription.data.HistoryRepository
import com.example.transcription.data.ModelAvailabilityPolicy
import com.example.transcription.data.TranscriptionProvider
import com.example.transcription.data.ModelCatalogRepository
import com.example.transcription.data.ModelCatalogCache
import com.example.transcription.data.PortableBackupManager
import com.example.transcription.data.SecretStore
import com.example.transcription.data.SettingsStore
import com.example.transcription.data.UsageStore
import com.example.transcription.network.OpenRouterClient
import com.example.transcription.recording.MicProfileStore

object AppContainer {
    @Volatile private var initialized = false
    lateinit var settings: SettingsStore
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var history: HistoryRepository
        private set
    lateinit var models: ModelCatalogRepository
        private set
    lateinit var openRouter: OpenRouterClient
        private set
    lateinit var backup: PortableBackupManager
        private set
    lateinit var usage: UsageStore
        private set
    lateinit var clipboard: ClipboardHistory
        private set
    /** What every recording has learned about the microphone it used. */
    lateinit var micProfiles: MicProfileStore
        private set
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        settings = SettingsStore(context.applicationContext)
        secrets = SecretStore(context.applicationContext)
        history = HistoryRepository(context.applicationContext)
        backup = PortableBackupManager(context.applicationContext, history, settings)
        usage = UsageStore(context.applicationContext)
        clipboard = ClipboardHistory(context.applicationContext)
        micProfiles = MicProfileStore(context.applicationContext)
        openRouter = OpenRouterClient()
        models = ModelCatalogRepository(openRouter, settings, secrets, ModelCatalogCache(context.applicationContext))
        initialized = true
    }

    /** Providers whose API key is currently stored. */
    fun availableProviders(): Set<TranscriptionProvider> = buildSet {
        if (secrets.readApiKey(ApiKeyProvider.OPENROUTER).isNotBlank()) add(TranscriptionProvider.OPENROUTER_STT)
        if (secrets.readApiKey(ApiKeyProvider.ELEVENLABS).isNotBlank()) add(TranscriptionProvider.ELEVENLABS)
        if (secrets.readApiKey(ApiKeyProvider.ASSEMBLYAI).isNotBlank()) add(TranscriptionProvider.ASSEMBLYAI)
    }

    /**
     * The primary/fallback pair every transcription path should actually send,
     * rather than the raw stored preference. Resolved per request so adding or
     * removing a key takes effect immediately, with no settings round trip.
     */
    fun resolvedTranscriptionModels(preferredPrimary: String): Pair<String, String> {
        val available = availableProviders()
        val primary = ModelAvailabilityPolicy.resolvePrimary(preferredPrimary, available)
        return primary to ModelAvailabilityPolicy.resolveFallback(primary, available)
    }

    /**
     * Writes the resolved choice back into settings, and must be called whenever
     * the set of stored keys changes.
     *
     * [resolvedTranscriptionModels] already corrects every request, but it does
     * so invisibly: the settings screen reads the *stored* model, so an install
     * holding only an OpenRouter key kept showing the shipped AssemblyAI default
     * while quietly transcribing on something else. Saving the resolution makes
     * the two agree, which is the whole point of one key being enough.
     *
     * The fallback follows only when it resolves to something reachable. A blank
     * one means "no usable second attempt", which SettingsStore deliberately
     * rewrites to a default, so storing it would trade this mismatch for another
     * one — requests already blank it themselves at the moment they are sent.
     */
    fun persistResolvedTranscriptionModel() {
        val current = settings.settings.value
        val (primary, fallback) = resolvedTranscriptionModels(current.selectedModel)
        val nextFallback = fallback.takeIf { it.isNotBlank() } ?: current.fallbackModel
        if (primary == current.selectedModel && nextFallback == current.fallbackModel) return
        settings.update { it.copy(selectedModel = primary, fallbackModel = nextFallback) }
    }
}
