package com.example.transcription.data

/** Units verified against OpenRouter model/provider pages, 2026-09-05.
 * The Models API's `prompt` field does not identify its billing unit.
 */
object PriceNormalizer {
    val mixedUnitIds = setOf("openai/whisper-large-v3", "openai/whisper-large-v3-turbo")
    private val perHourIds = setOf(
        "x-ai/grok-stt-1.0", "microsoft/mai-transcribe-1.5", "microsoft/mai-transcribe-2"
    )
    private val perSecondIds = setOf(
        "qwen/qwen3-asr-flash-2026-02-10", "qwen/qwen3-asr-1.7b", "qwen/qwen3-asr-0.6b",
        "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b",
        "mistralai/voxtral-small-24b-2507-stt", "mistralai/voxtral-mini-3b-2507",
        "fish-audio/transcribe-1"
    )
    private val perMinuteIds = setOf(
        "openai/gpt-transcribe", "deepgram/nova-3", "nvidia/parakeet-tdt-0.6b-v3",
        "mistralai/voxtral-mini-transcribe", "google/chirp-3", "openai/whisper-1"
    )
    private val estimatedTokenPrices = mapOf(
        "openai/gpt-4o-mini-transcribe" to 0.18, "openai/gpt-4o-transcribe" to 0.36
    )

    fun pricePerHour(modelId: String, promptPrice: Double, provider: String? = null): Double? {
        if (!promptPrice.isFinite() || promptPrice < 0.0) return null
        return when {
            modelId in estimatedTokenPrices -> estimatedTokenPrices.getValue(modelId)
            modelId in mixedUnitIds -> when (provider?.lowercase()) {
                "deepinfra" -> promptPrice * 3600.0
                "groq" -> promptPrice
                else -> null // Never guess the unit from the magnitude of a price.
            }
            modelId in perHourIds -> promptPrice
            modelId in perSecondIds -> promptPrice * 3600.0
            modelId in perMinuteIds -> promptPrice * 60.0
            else -> null
        }
    }

    fun note(modelId: String): String = when {
        modelId in estimatedTokenPrices -> "OpenRouter token pricing; hourly value is an estimate"
        modelId in mixedUnitIds -> "Lowest known provider hourly rate; actual routing may cost more"
        modelId in perHourIds -> "Billed per audio hour"
        modelId in perSecondIds -> "Converted from per-second billing"
        modelId in perMinuteIds -> "Converted from per-minute billing"
        else -> "Hourly price unavailable: billing unit not verified"
    }
}
