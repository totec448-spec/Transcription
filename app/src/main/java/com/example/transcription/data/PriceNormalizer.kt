package com.example.transcription.data

/** Converts OpenRouter's model-specific billing unit to one hour of source audio. */
object PriceNormalizer {
    private val perHourIds = setOf(
        "x-ai/grok-stt-1.0",
        "microsoft/mai-transcribe-1.5",
        "openai/whisper-large-v3-turbo"
    )

    private val perSecondIds = setOf(
        "qwen/qwen3-asr-flash-2026-02-10"
    )

    private val estimatedTokenPrices = mapOf(
        "openai/gpt-4o-mini-transcribe" to 0.18,
        "openai/gpt-4o-transcribe" to 0.36
    )

    fun pricePerHour(modelId: String, promptPrice: Double): Double? = when {
        modelId in estimatedTokenPrices -> estimatedTokenPrices.getValue(modelId)
        modelId in perHourIds -> promptPrice
        modelId in perSecondIds -> promptPrice * 3600.0
        promptPrice > 0.0 -> promptPrice * 60.0
        else -> null
    }

    fun note(modelId: String): String = when {
        modelId in estimatedTokenPrices -> "OpenRouter token pricing; hourly value is an estimate"
        modelId in perHourIds -> "Billed per audio hour"
        modelId in perSecondIds -> "Converted from per-second billing"
        else -> "Converted from per-minute billing"
    }
}
