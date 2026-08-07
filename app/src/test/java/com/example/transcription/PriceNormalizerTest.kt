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
        assertEquals(0.04, PriceNormalizer.pricePerHour("openai/whisper-large-v3-turbo", 0.04)!!, 0.000001)
    }

    @Test fun usesDocumentedEstimateForTokenPricedModel() {
        assertEquals(0.18, PriceNormalizer.pricePerHour("openai/gpt-4o-mini-transcribe", 0.00000125)!!, 0.000001)
    }
}
