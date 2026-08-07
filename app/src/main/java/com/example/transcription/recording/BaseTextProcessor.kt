package com.example.transcription.recording

import android.os.SystemClock
import android.util.Log
import com.example.transcription.AppContainer
import com.example.transcription.data.AppSettings
import com.example.transcription.data.BaseCleanupMode
import com.example.transcription.data.ModelAvailabilityPolicy
import com.example.transcription.data.TranscriptionModel
import com.example.transcription.data.baseCleanupPrompt
import com.example.transcription.data.TranscriptionProvider

/**
 * The automatic pass every finished transcript takes before it reaches the
 * user, in the app and in the keyboard alike.
 *
 * It is deliberately failure-transparent: a provider error, a missing key, or a
 * blank answer returns the original transcript rather than raising. Losing a
 * recording to a cleanup that could not run would be far worse than shipping
 * the raw text, and the spoken Edit key can still fix it afterwards.
 */
object BaseTextProcessor {
    private const val TAG = "TranscriptionPerf"

    fun promptFor(settings: AppSettings, mode: BaseCleanupMode = settings.baseCleanupMode) =
        settings.baseCleanupPrompt(mode)

    /**
     * The mode that will really run, as opposed to the stored preference.
     *
     * Cleanup always goes through an OpenRouter text model, so an ElevenLabs- or
     * AssemblyAI-only install has no cleanup at all whatever the setting says.
     * Callers announcing progress must ask here rather than read the setting, or
     * they promise a pass that is about to be skipped.
     *
     * Falls back to the stored value when the container is not up — unit tests
     * construct settings directly and never install a key store.
     */
    fun effectiveMode(settings: AppSettings): BaseCleanupMode {
        val available = runCatching { AppContainer.availableProviders() }.getOrNull()
            ?: return settings.baseCleanupMode
        return ModelAvailabilityPolicy.effectiveBaseCleanupMode(settings.baseCleanupMode, available)
    }

    /**
     * Convenience for callers that only want the text. The cost is still
     * recorded against the running totals either way.
     */
    fun process(
        text: String,
        settings: AppSettings,
        cleanupModels: List<TranscriptionModel>,
        isCancelled: () -> Boolean = { false }
    ): String = processDetailed(text, settings, cleanupModels, isCancelled).text

    fun processDetailed(
        text: String,
        settings: AppSettings,
        cleanupModels: List<TranscriptionModel>,
        isCancelled: () -> Boolean = { false }
    ): CleanedText {
        // Every skip is logged. A cleanup that silently does nothing is
        // indistinguishable from one that is broken, which is exactly the
        // failure mode this pass must never have.
        val mode = effectiveMode(settings)
        if (mode == BaseCleanupMode.OFF) return skip(text, "mode_off")
        if (text.isBlank()) return skip(text, "empty_transcript")
        val prompt = promptFor(settings, mode).takeIf { it.isNotBlank() }
            ?: return skip(text, "empty_prompt")
        val model = cleanupModels.firstOrNull { it.id == settings.cleanupModel }
            ?: cleanupModels.firstOrNull()
            ?: return skip(text, "no_cleanup_model")
        val apiKey = runCatching { AppContainer.secrets.readApiKey() }.getOrNull().orEmpty()
        if (apiKey.isBlank()) return skip(text, "no_openrouter_key")
        if (isCancelled()) return skip(text, "cancelled")

        val effort = CleanupReasoningResolver.resolve(model, settings.baseCleanupReasoningEffort)
        val started = SystemClock.elapsedRealtime()
        val result = runCatching {
            AppContainer.openRouter.processText(
                apiKey = apiKey,
                model = model.id,
                systemPrompt = prompt,
                text = text,
                reasoningEffort = effort.effort,
                includeReasoning = effort.include,
                includeTemperature = "temperature" in model.supportedParameters,
                timeoutSeconds = settings.providerTimeoutSeconds
            )
        }.onSuccess {
            Log.i(
                TAG,
                "base_cleanup=${settings.baseCleanupMode.stored} model=${model.id.substringAfterLast('/')} " +
                    "ms=${SystemClock.elapsedRealtime() - started} in_chars=${text.length} " +
                    "out_chars=${it.text.length} cost=${it.costUsd ?: -1.0}"
            )
        }.onFailure {
            Log.w(TAG, "base_cleanup_skipped reason=request_failed detail=${it.message}")
        }.getOrNull() ?: return CleanedText(text, null)

        // A failed pass is charged for too when it reached the model, but a
        // billed request that produced nothing usable is not worth attributing
        // to a note; it still belongs in the running total.
        recordUsage(result.costUsd)
        return CleanedText(result.text.takeIf { it.isNotBlank() } ?: text, result.costUsd)
    }

    private fun recordUsage(costUsd: Double?) {
        runCatching {
            AppContainer.usage.record(
                provider = TranscriptionProvider.OPENROUTER_TEXT,
                seconds = 0.0,
                costUsd = costUsd,
                // Text passes are billed per token and OpenRouter returns the
                // figure, so unlike the audio providers this is never a guess.
                estimated = costUsd == null
            )
        }
    }

    private fun skip(text: String, reason: String): CleanedText {
        Log.i(TAG, "base_cleanup_skipped reason=$reason")
        return CleanedText(text, null)
    }
}

/** A cleaned transcript together with what the pass cost, if anything. */
data class CleanedText(val text: String, val costUsd: Double?)

internal data class ResolvedCleanupReasoning(val include: Boolean, val effort: String?)

/**
 * Shared by the automatic pass and the spoken Edit key, which each carry their
 * own configured effort but resolve it against the same model capabilities.
 */
internal object CleanupReasoningResolver {
    fun resolve(model: TranscriptionModel, configured: String): ResolvedCleanupReasoning {
        val supportsReasoning = model.reasoningMandatory ||
            model.reasoningEfforts.isNotEmpty() ||
            "reasoning" in model.supportedParameters
        if (!supportsReasoning) return ResolvedCleanupReasoning(include = false, effort = null)
        val normalized = configured.lowercase()
        val effort = when {
            model.reasoningMandatory && normalized in setOf("none", "auto") ->
                model.defaultReasoningEffort ?: model.reasoningEfforts.firstOrNull() ?: "high"
            normalized == "auto" -> null
            else -> normalized
        }
        return ResolvedCleanupReasoning(
            include = effort != null || model.reasoningMandatory,
            effort = effort
        )
    }
}
