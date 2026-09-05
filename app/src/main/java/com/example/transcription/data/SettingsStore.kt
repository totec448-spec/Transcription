package com.example.transcription.data

import android.content.Context
import android.app.backup.BackupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsStore(context: Context) {
    private val backupManager = BackupManager(context)
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    fun update(transform: (AppSettings) -> AppSettings) {
        val proposed = transform(_settings.value).let {
            it.copy(cleanupMinimumInstructionWords = it.cleanupMinimumInstructionWords.coerceIn(0, 50))
        }
        val value = if (proposed.fallbackModel.isBlank() || proposed.fallbackModel == proposed.selectedModel) {
            proposed.copy(fallbackModel = defaultFallbackFor(proposed.selectedModel))
        } else proposed
        _settings.value = value
        val editor = preferences.edit()
        // One key per mode, present only while an edit exists: a mode put back
        // to its default must lose its stored prompt, or it would keep the old
        // text forever and never see an improved default.
        BaseCleanupMode.entries.forEach { mode ->
            val key = baseCleanupPromptKey(mode)
            val edited = value.baseCleanupPrompts[mode.stored]?.takeIf(String::isNotBlank)
            if (edited == null) editor.remove(key) else editor.putString(key, edited)
        }
        editor
            .putString("model", value.selectedModel)
            .putString("fallback_model", value.fallbackModel)
            .putString("language", value.language)
            .putString("prompt", value.prompt)
            .putString("multimodal_prompt", value.multimodalPrompt)
            .putString("multimodal_reasoning_effort", value.multimodalReasoningEffort)
            .putString("cleanup_model", value.cleanupModel)
            .putString("cleanup_prompt", value.cleanupPrompt)
            .putString("cleanup_reasoning_effort", value.cleanupReasoningEffort)
            .putInt("cleanup_minimum_instruction_words", value.cleanupMinimumInstructionWords)
            .putString("base_cleanup_mode", value.baseCleanupMode.stored)
            .putString("base_cleanup_reasoning_effort", value.baseCleanupReasoningEffort)
            .putBoolean("trim_silence_before_upload", value.trimSilenceBeforeUpload)
            .putString("theme", value.themeMode.name)
            .putBoolean("auto_copy", value.autoCopy)
            .putBoolean("haptics", value.haptics)
            .putBoolean("persistent_notification", value.persistentReadyNotification)
            .putBoolean("save_failed_audio", value.saveFailedAudio)
            .putBoolean("wifi_only", value.wifiOnly)
            .putString("send_audio_format", value.sendAudioFormat.wireName)
            .putInt("max_automatic_upload_minutes", value.maxAutomaticUploadMinutes)
            .putInt("provider_timeout_seconds", value.providerTimeoutSeconds)
            .putBoolean("cancel_request_on_abandon", value.cancelRequestOnAbandon)
            .putBoolean("keep_debug_send_copies", value.keepDebugSendCopies)
            .putInt("audio_sample_rate", value.audioSampleRateHz)
            .putInt("audio_bit_rate", value.audioBitRateBps)
            .putInt("audio_channels", value.audioChannels)
            .putInt("audio_quality_version", AUDIO_QUALITY_VERSION)
            .putInt("defaults_version", SETTINGS_DEFAULTS_VERSION)
            .putLong("catalog_updated_at", value.modelCatalogUpdatedAt)
            .putBoolean("onboarding_completed", value.onboardingCompleted)
            .apply()
        backupManager.dataChanged()
    }

    fun resetToReasonableDefaults() {
        val retainedTheme = _settings.value.themeMode
        update {
            AppSettings(
                themeMode = retainedTheme,
                modelCatalogUpdatedAt = it.modelCatalogUpdatedAt,
                // Resetting settings is not a fresh install; sending a
                // configured user back through onboarding would be a bug.
                onboardingCompleted = it.onboardingCompleted
            )
        }
    }

    private fun load(): AppSettings {
        // Version 1.0 persisted the eight-word default along with every settings edit.
        // Migrate that legacy default once; retain other explicit thresholds.
        if (!preferences.getBoolean("short_cleanup_instructions_v2", false)) {
            val migration = preferences.edit().putBoolean("short_cleanup_instructions_v2", true)
            if (preferences.getInt("cleanup_minimum_instruction_words", 8) == 8) {
                migration.putInt("cleanup_minimum_instruction_words", DEFAULT_CLEANUP_MINIMUM_INSTRUCTION_WORDS)
            }
            migration.apply()
        }
        // Decided before anything else writes to the file: an install that has
        // already stored settings predates onboarding and must never see it.
        // The answer is pinned immediately, because the very next lines create
        // the keys that this check reads, which would otherwise make a fresh
        // install look like an existing one from its second launch onwards.
        if (!preferences.contains("onboarding_completed")) {
            val existingInstall = preferences.contains("defaults_version") ||
                preferences.contains("model") ||
                preferences.contains("theme")
            preferences.edit().putBoolean("onboarding_completed", existingInstall).apply()
        }
        // A stored value from an older defaults generation would otherwise beat
        // every new shipped default forever, so re-point the model choices once.
        val staleDefaults = preferences.getInt("defaults_version", 0) < SETTINGS_DEFAULTS_VERSION
        if (staleDefaults) {
            preferences.edit()
                .putInt("defaults_version", SETTINGS_DEFAULTS_VERSION)
                .remove("model")
                .remove("fallback_model")
                .remove("cleanup_model")
                .apply()
        }
        migrateBaseCleanupPrompts()
        val selectedModel = preferences.getString("model", null) ?: PRIMARY_DEFAULT
        val storedFallback = preferences.getString("fallback_model", null)
        val fallbackModel = storedFallback?.takeIf { it.isNotBlank() && it != selectedModel }
            ?: defaultFallbackFor(selectedModel)
        val hasCurrentAudioQuality = preferences.getInt("audio_quality_version", 0) >= AUDIO_QUALITY_VERSION
        val defaultAudio = AudioCaptureOptions.highestCompressed
        return AppSettings(
            selectedModel = selectedModel,
            fallbackModel = fallbackModel,
            language = LanguageCode.normalize(preferences.getString("language", null) ?: "auto"),
            prompt = preferences.getString("prompt", null) ?: "",
            multimodalPrompt = preferences.getString("multimodal_prompt", null) ?: DEFAULT_MULTIMODAL_PROMPT,
            multimodalReasoningEffort = preferences.getString("multimodal_reasoning_effort", "auto")
                ?.lowercase()
                ?.takeIf { it in REASONING_EFFORTS }
                ?: "auto",
            cleanupModel = preferences.getString("cleanup_model", ProviderModels.OPENROUTER_DEEPSEEK_V4_FLASH)
                ?.takeIf(String::isNotBlank)
                ?: ProviderModels.OPENROUTER_DEEPSEEK_V4_FLASH,
            cleanupPrompt = preferences.getString("cleanup_prompt", DEFAULT_CLEANUP_PROMPT)
                ?.takeIf(String::isNotBlank)
                ?.takeIf { it.trim() !in setOf(SUPERSEDED_CLEANUP_PROMPT_V1, DEFAULT_CLEANUP_PROMPT_V2) }
                ?: DEFAULT_CLEANUP_PROMPT,
            cleanupReasoningEffort = preferences.getString("cleanup_reasoning_effort", "none")
                ?.lowercase()
                ?.takeIf { it in REASONING_EFFORTS }
                ?: "none",
            cleanupMinimumInstructionWords = preferences.getInt(
                "cleanup_minimum_instruction_words",
                DEFAULT_CLEANUP_MINIMUM_INSTRUCTION_WORDS
            ).coerceIn(0, 50),
            baseCleanupMode = BaseCleanupMode.fromStored(preferences.getString("base_cleanup_mode", null)),
            baseCleanupPrompts = BaseCleanupMode.entries.mapNotNull { mode ->
                preferences.getString(baseCleanupPromptKey(mode), null)
                    ?.takeIf(String::isNotBlank)
                    ?.let { mode.stored to it }
            }.toMap(),
            baseCleanupReasoningEffort = preferences.getString("base_cleanup_reasoning_effort", "none")
                ?.lowercase()
                ?.takeIf { it in REASONING_EFFORTS }
                ?: "none",
            trimSilenceBeforeUpload = preferences.getBoolean("trim_silence_before_upload", true),
            themeMode = runCatching {
                ThemeMode.valueOf(preferences.getString("theme", ThemeMode.SYSTEM.name)!!)
            }.getOrDefault(ThemeMode.SYSTEM),
            autoCopy = preferences.getBoolean("auto_copy", true),
            haptics = preferences.getBoolean("haptics", false),
            persistentReadyNotification = preferences.getBoolean("persistent_notification", true),
            saveFailedAudio = preferences.getBoolean("save_failed_audio", true),
            wifiOnly = preferences.getBoolean("wifi_only", false),
            sendAudioFormat = SendAudioFormat.fromStored(preferences.getString("send_audio_format", "auto")),
            maxAutomaticUploadMinutes = preferences.getInt("max_automatic_upload_minutes", 20).coerceIn(1, 240),
            providerTimeoutSeconds = preferences.getInt("provider_timeout_seconds", 20).coerceIn(5, 180),
            cancelRequestOnAbandon = preferences.getBoolean("cancel_request_on_abandon", true),
            keepDebugSendCopies = preferences.getBoolean("keep_debug_send_copies", false),
            audioSampleRateHz = if (hasCurrentAudioQuality) {
                AudioCaptureOptions.sampleRate(preferences.getInt("audio_sample_rate", defaultAudio.sampleRateHz))
            } else defaultAudio.sampleRateHz,
            audioBitRateBps = if (hasCurrentAudioQuality) {
                AudioCaptureOptions.bitRate(preferences.getInt("audio_bit_rate", defaultAudio.bitRateBps))
            } else defaultAudio.bitRateBps,
            audioChannels = if (hasCurrentAudioQuality) {
                AudioCaptureOptions.channelCount(preferences.getInt("audio_channels", defaultAudio.channels))
            } else defaultAudio.channels,
            modelCatalogUpdatedAt = preferences.getLong("catalog_updated_at", 0L),
            onboardingCompleted = preferences.getBoolean("onboarding_completed", false)
        )
    }

    /**
     * Clean and Polish each had a dedicated key that was rewritten with the
     * shipped default on every save. Only a genuinely edited prompt survives
     * into the per-mode map; a stored copy of a shipped default is dropped so
     * the install follows the current one.
     */
    private fun migrateBaseCleanupPrompts() {
        val legacy = mapOf(
            BaseCleanupMode.CLEAN to "base_cleanup_clean_prompt",
            BaseCleanupMode.POLISH to "base_cleanup_polish_prompt"
        ).filterValues { preferences.contains(it) }
        if (legacy.isEmpty()) return
        val editor = preferences.edit()
        legacy.forEach { (mode, key) ->
            migratedBaseCleanupPrompt(mode, preferences.getString(key, null))
                ?.let { editor.putString(baseCleanupPromptKey(mode), it) }
            editor.remove(key)
        }
        editor.apply()
    }

    private fun baseCleanupPromptKey(mode: BaseCleanupMode) = "base_cleanup_prompt_${mode.stored}"

    private fun defaultFallbackFor(selectedModel: String) =
        if (selectedModel == PRIMARY_DEFAULT) FALLBACK_DEFAULT else PRIMARY_DEFAULT

    private companion object {
        const val PRIMARY_DEFAULT = ProviderModels.ASSEMBLYAI_UNIVERSAL_3_5_PRO
        const val FALLBACK_DEFAULT = ProviderModels.OPENROUTER_WHISPER_LARGE_V3
        const val AUDIO_QUALITY_VERSION = 1
        val REASONING_EFFORTS = setOf("auto", "none", "minimal", "low", "medium", "high", "xhigh", "max")
    }
}
