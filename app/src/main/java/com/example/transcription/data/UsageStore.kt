package com.example.transcription.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ProviderUsage(
    val provider: TranscriptionProvider,
    val requests: Int = 0,
    val seconds: Double = 0.0,
    val costUsd: Double = 0.0,
    /** True once any request in this total had its price inferred rather than billed. */
    val estimated: Boolean = false
) {
    val minutes: Double get() = seconds / 60.0
}

data class UsageTotals(val perProvider: List<ProviderUsage> = emptyList()) {
    /**
     * Text passes are excluded from the transcription figures on purpose: they
     * have no audio duration, so counting them would report minutes that were
     * never recorded and requests that never touched a microphone.
     */
    val transcription: List<ProviderUsage>
        get() = perProvider.filterNot { it.provider == TranscriptionProvider.OPENROUTER_TEXT }

    val cleanup: ProviderUsage?
        get() = perProvider.firstOrNull { it.provider == TranscriptionProvider.OPENROUTER_TEXT }

    val requests: Int get() = transcription.sumOf { it.requests }
    val seconds: Double get() = transcription.sumOf { it.seconds }

    /** Everything spent, transcription and cleanup together. */
    val costUsd: Double get() = perProvider.sumOf { it.costUsd }
    val estimated: Boolean get() = perProvider.any { it.estimated }
    val minutes: Double get() = seconds / 60.0
}

/**
 * Running transcription totals per API key.
 *
 * Deliberately separate from Notes: clearing history is about the notes, not
 * about forgetting what has been spent, and failed or re-transcribed audio also
 * costs money without leaving a note behind.
 *
 * Only OpenRouter reports a real price per request. ElevenLabs and AssemblyAI
 * bill per audio second with no per-response figure, so their cost is derived
 * from the catalog's per-hour rate and flagged as estimated rather than
 * presented as fact.
 */
class UsageStore(context: Context) {
    private val preferences = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val _totals = MutableStateFlow(load())
    val totals: StateFlow<UsageTotals> = _totals

    /**
     * Keep the hot counter path in memory and persist the already-computed
     * totals. The old implementation read SharedPreferences several times and
     * then rebuilt every provider total from disk after each chunk. It also made
     * the read-modify-write vulnerable to two concurrent transcription jobs
     * losing an increment. One synchronized in-memory update avoids both costs.
     */
    @Synchronized
    fun record(
        provider: TranscriptionProvider,
        seconds: Double,
        costUsd: Double?,
        estimated: Boolean
    ) {
        val previousByProvider = _totals.value.perProvider.associateBy(ProviderUsage::provider)
        val previous = previousByProvider[provider] ?: ProviderUsage(provider)
        val next = previous.copy(
            requests = previous.requests + 1,
            seconds = previous.seconds + seconds,
            costUsd = previous.costUsd + (costUsd ?: 0.0),
            estimated = previous.estimated || estimated || costUsd == null
        )
        _totals.value = UsageTotals(
            TranscriptionProvider.entries.mapNotNull { candidate ->
                if (candidate == provider) next else previousByProvider[candidate]
            }
        )

        val key = provider.name
        preferences.edit()
            .putInt("${key}_requests", next.requests)
            .putFloat("${key}_seconds", next.seconds.toFloat())
            .putFloat("${key}_cost", next.costUsd.toFloat())
            .putBoolean("${key}_estimated", next.estimated)
            .apply()
    }

    @Synchronized
    fun reset() {
        preferences.edit().clear().apply()
        _totals.value = UsageTotals()
    }

    private fun load() = UsageTotals(
        TranscriptionProvider.entries.mapNotNull { provider ->
            val key = provider.name
            val requests = preferences.getInt("${key}_requests", 0)
            if (requests <= 0) return@mapNotNull null
            ProviderUsage(
                provider = provider,
                requests = requests,
                seconds = preferences.getFloat("${key}_seconds", 0f).toDouble(),
                costUsd = preferences.getFloat("${key}_cost", 0f).toDouble(),
                estimated = preferences.getBoolean("${key}_estimated", false)
            )
        }
    )
}
