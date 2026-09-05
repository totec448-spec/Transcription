package com.example.transcription

import com.example.transcription.data.PriceNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceNormalizerTest {
    @Test fun grokUsesPublishedHourlyUnit() {
        assertEquals(0.10, PriceNormalizer.pricePerHour("x-ai/grok-stt-1.0", 0.10)!!, 0.000001)
    }

    @Test fun convertsPerMinutePrice() {
        assertEquals(0.18, PriceNormalizer.pricePerHour("mistralai/voxtral-mini-transcribe", 0.003)!!, 0.000001)
    }

    @Test fun convertsPerSecondPrice() {
        assertEquals(0.126, PriceNormalizer.pricePerHour("qwen/qwen3-asr-flash-2026-02-10", 0.000035)!!, 0.000001)
    }

    @Test fun keepsPerHourPrice() {
        assertEquals(0.04, PriceNormalizer.pricePerHour("openai/whisper-large-v3-turbo", 0.04, "Groq")!!, 0.000001)
    }

    @Test fun usesDocumentedEstimateForTokenPricedModel() {
        assertEquals(0.18, PriceNormalizer.pricePerHour("openai/gpt-4o-mini-transcribe", 0.00000125)!!, 0.000001)
    }
    @Test fun mai2UsesHourAndDeepInfraUsesSeconds() {
        assertEquals(0.10, PriceNormalizer.pricePerHour("microsoft/mai-transcribe-2", 0.10)!!, 0.000001)
        assertEquals(0.011988, PriceNormalizer.pricePerHour("openai/whisper-large-v3-turbo", 0.00000333, "DeepInfra")!!, 0.0000001)
        assertEquals(0.027, PriceNormalizer.pricePerHour("openai/whisper-large-v3", 0.0000075, "DeepInfra")!!, 0.0000001)
    }

    @Test fun unknownUnitsAndInvalidPricesAreNotGuessed() {
        org.junit.Assert.assertNull(PriceNormalizer.pricePerHour("new/model", 0.1))
        org.junit.Assert.assertNull(PriceNormalizer.pricePerHour("openai/whisper-large-v3-turbo", 0.04))
        org.junit.Assert.assertNull(PriceNormalizer.pricePerHour("microsoft/mai-transcribe-2", Double.NaN))
        org.junit.Assert.assertNull(PriceNormalizer.pricePerHour("microsoft/mai-transcribe-2", -1.0))
    }

    @Test fun newerModelsUseVerifiedSecondUnits() {
        assertEquals(0.027, PriceNormalizer.pricePerHour("qwen/qwen3-asr-1.7b", 0.0000075)!!, 0.000001)
        assertEquals(0.36, PriceNormalizer.pricePerHour("fish-audio/transcribe-1", 0.0001)!!, 0.000001)
        assertEquals(0.18, PriceNormalizer.pricePerHour("mistralai/voxtral-small-24b-2507-stt", 0.00005)!!, 0.000001)
    }
}
