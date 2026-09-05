package com.example.transcription.data

import com.example.transcription.network.OpenRouterClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ModelCatalogRepository(
    private val client: OpenRouterClient,
    private val settingsStore: SettingsStore,
    private val secretStore: SecretStore,
    private val cache: ModelCatalogCache
) {
    private val cached = cache.read()
    private val _models = MutableStateFlow(
        (cached.transcription + fallbackModels).distinctBy { it.id }
    )
    val models: StateFlow<List<TranscriptionModel>> = _models
    private val _cleanupModels = MutableStateFlow(
        (cached.cleanup + cleanupFallbackModels).distinctBy { it.id }
    )
    val cleanupModels: StateFlow<List<TranscriptionModel>> = _cleanupModels
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    @Volatile private var lastAttemptAt = 0L

    fun refresh(force: Boolean = false) {
        if (_loading.value) return
        val now = System.currentTimeMillis()
        val successfulAge = now - settingsStore.settings.value.modelCatalogUpdatedAt
        val attemptAge = now - lastAttemptAt
        if (!force && (
                (successfulAge in 0 until CATALOG_TTL_MS && _models.value.none { it.priceNote == "Refresh catalog for corrected pricing" }) ||
                    attemptAge in 0 until FAILED_REFRESH_BACKOFF_MS
                )
        ) return
        lastAttemptAt = now
        _loading.value = true
        Thread {
            try {
                val openRouterKey = secretStore.readApiKey()
                val sttResult = runCatching { client.fetchTranscriptionModels(openRouterKey) }
                val textResult = runCatching { client.fetchTextCatalog(openRouterKey) }

                val previous = _models.value
                val stt = CatalogRetention.freshOrPrevious(
                    sttResult.getOrNull().orEmpty(),
                    previous,
                    TranscriptionProvider.OPENROUTER_STT
                )
                val multimodal = CatalogRetention.freshOrPrevious(
                    textResult.getOrNull()?.multimodalAudio.orEmpty(),
                    previous,
                    TranscriptionProvider.OPENROUTER_MULTIMODAL
                )
                val eleven = fixedProviderModels.filter { it.provider == TranscriptionProvider.ELEVENLABS }
                val assembly = fixedProviderModels.filter { it.provider == TranscriptionProvider.ASSEMBLYAI }
                val transcription = (eleven + assembly + stt + multimodal)
                    .distinctBy { it.id }
                val cleanup = textResult.getOrNull()?.text.orEmpty().ifEmpty {
                    _cleanupModels.value
                }.let { (it + cleanupFallbackModels).distinctBy(TranscriptionModel::id) }

                _models.value = transcription.ifEmpty { fallbackModels }
                _cleanupModels.value = cleanup
                cache.write(CachedModelCatalog(_models.value, _cleanupModels.value))
                val completeRefresh = sttResult.isSuccess && textResult.isSuccess
                if (completeRefresh) {
                    settingsStore.update { it.copy(modelCatalogUpdatedAt = System.currentTimeMillis()) }
                }
                _error.value = listOfNotNull(
                    sttResult.exceptionOrNull()?.let { "OpenRouter STT: ${it.message}" },
                    textResult.exceptionOrNull()?.let { "OpenRouter text: ${it.message}" }
                ).joinToString("\n").takeIf(String::isNotBlank)
            } catch (error: Throwable) {
                _error.value = error.message ?: "Model catalog unavailable"
            } finally {
                _loading.value = false
            }
        }.start()
    }

    companion object {
        val fallbackModels = listOf(
            TranscriptionModel(ProviderModels.ELEVENLABS_SCRIBE_V2, "ElevenLabs: Scribe v2", "High-accuracy batch speech-to-text in 90+ languages.", 0.22, provider = TranscriptionProvider.ELEVENLABS),
            TranscriptionModel(ProviderModels.ELEVENLABS_SCRIBE_V2_REALTIME, "ElevenLabs: Scribe v2 live", "Low-latency live transcription.", null, provider = TranscriptionProvider.ELEVENLABS, streaming = true),
            TranscriptionModel(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO, "AssemblyAI: Universal-3.5 Pro", "Fast batch transcription with clean plain-text output.", 0.21, provider = TranscriptionProvider.ASSEMBLYAI),
            TranscriptionModel("assemblyai/batch/universal-3-pro", "AssemblyAI: Universal-3 Pro", "Previous high-accuracy batch model.", null, provider = TranscriptionProvider.ASSEMBLYAI),
            TranscriptionModel("assemblyai/batch/universal-2", "AssemblyAI: Universal-2", "Broad-language batch transcription.", null, provider = TranscriptionProvider.ASSEMBLYAI),
            TranscriptionModel("assemblyai/batch/universal", "AssemblyAI: Universal", "Provider-routed universal batch transcription.", null, provider = TranscriptionProvider.ASSEMBLYAI),
            TranscriptionModel("assemblyai/batch/slam-1", "AssemblyAI: Slam-1", "Customizable English batch transcription.", null, provider = TranscriptionProvider.ASSEMBLYAI),
            TranscriptionModel(ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO_STREAMING, "AssemblyAI: Universal-3.5 Pro live", "Current flagship realtime transcription.", 0.45, provider = TranscriptionProvider.ASSEMBLYAI, streaming = true),
            TranscriptionModel("assemblyai/live/u3-rt-pro", "AssemblyAI: Universal-3 Pro live", "Previous high-accuracy realtime model.", 0.45, provider = TranscriptionProvider.ASSEMBLYAI, streaming = true),
            TranscriptionModel("assemblyai/live/universal-streaming-english", "AssemblyAI: Universal Streaming English", "Fast English realtime transcription.", 0.15, provider = TranscriptionProvider.ASSEMBLYAI, streaming = true),
            TranscriptionModel("assemblyai/live/universal-streaming-multilingual", "AssemblyAI: Universal Streaming Multilingual", "Fast multilingual realtime transcription.", 0.15, provider = TranscriptionProvider.ASSEMBLYAI, streaming = true),
            TranscriptionModel("assemblyai/live/whisper-rt", "AssemblyAI: Whisper live", "Broad-language realtime transcription.", 0.30, provider = TranscriptionProvider.ASSEMBLYAI, streaming = true),
            TranscriptionModel(ProviderModels.OPENROUTER_MAI_2, "Microsoft: MAI-Transcribe 2", "Multilingual transcription. Automatic send format: MP3.", 0.10, provider = TranscriptionProvider.OPENROUTER_STT),
            TranscriptionModel(ProviderModels.OPENROUTER_MAI_1_5, "Microsoft: MAI-Transcribe 1.5", "Fast multilingual transcription. Automatic send format: MP3.", 0.36, provider = TranscriptionProvider.OPENROUTER_STT),
            TranscriptionModel(ProviderModels.OPENROUTER_WHISPER_LARGE_V3, "OpenAI: Whisper Large V3", "Reliable multilingual fallback transcription.", 0.027, provider = TranscriptionProvider.OPENROUTER_STT),
            TranscriptionModel("openai/gpt-4o-mini-transcribe", "OpenAI: GPT-4o Mini Transcribe", "Fast, accurate and cost-efficient.", 0.18, "Estimated from token pricing"),
            TranscriptionModel("openai/gpt-4o-transcribe", "OpenAI: GPT-4o Transcribe", "High-quality multilingual transcription.", 0.36, "Estimated from token pricing"),
            TranscriptionModel(ProviderModels.OPENROUTER_GPT_TRANSCRIBE, "OpenAI: GPT Transcribe", "High-accuracy speech-to-text for recorded audio.", 0.27, provider = TranscriptionProvider.OPENROUTER_STT),
            TranscriptionModel("mistralai/voxtral-mini-transcribe", "Mistral: Voxtral Mini Transcribe", "Fast transcription for voice notes and meetings.", 0.18),
            TranscriptionModel("openai/whisper-1", "OpenAI: Whisper 1", "Legacy multilingual Whisper endpoint.", 0.36),
            TranscriptionModel("openai/whisper-large-v3-turbo", "OpenAI: Whisper Large V3 Turbo", "Very fast open-source Whisper variant.", 0.011988)
        )
        private val fixedProviderModels = fallbackModels.filter {
            it.provider == TranscriptionProvider.ELEVENLABS || it.provider == TranscriptionProvider.ASSEMBLYAI
        }
        val cleanupFallbackModels = listOf(
            TranscriptionModel(
                id = ProviderModels.OPENROUTER_DEEPSEEK_V4_FLASH,
                name = "DeepSeek: DeepSeek V4 Flash 0731",
                description = "Fast, cheap text model for automatic and spoken cleanup.",
                pricePerHourUsd = null,
                priceNote = "US$ 0.14 / M input · US$ 0.28 / M output",
                provider = TranscriptionProvider.OPENROUTER_TEXT,
                inputPricePerMillionUsd = 0.14,
                outputPricePerMillionUsd = 0.28,
                supportedParameters = setOf("reasoning", "temperature"),
                reasoningEfforts = listOf("low", "high", "max"),
                defaultReasoningEffort = "high"
            ),
            TranscriptionModel(
                id = ProviderModels.OPENROUTER_DEEPSEEK_V4_PRO,
                name = "DeepSeek: DeepSeek V4 Pro",
                description = "Large text model for precise transcript cleanup.",
                pricePerHourUsd = null,
                priceNote = "US$ 0.44 / M input · US$ 0.87 / M output",
                provider = TranscriptionProvider.OPENROUTER_TEXT,
                inputPricePerMillionUsd = 0.435,
                outputPricePerMillionUsd = 0.87,
                supportedParameters = setOf("reasoning", "temperature"),
                reasoningEfforts = listOf("high", "xhigh")
            )
        )
        private const val CATALOG_TTL_MS = 6 * 60 * 60 * 1_000L
        private const val FAILED_REFRESH_BACKOFF_MS = 10 * 60 * 1_000L
    }
}

internal object CatalogRetention {
    fun freshOrPrevious(
        fresh: List<TranscriptionModel>,
        previous: List<TranscriptionModel>,
        provider: TranscriptionProvider
    ) = fresh.ifEmpty { previous.filter { it.provider == provider } }
}
