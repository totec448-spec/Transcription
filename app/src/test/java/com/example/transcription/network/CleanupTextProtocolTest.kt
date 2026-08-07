package com.example.transcription.network

import com.example.transcription.data.ProviderModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTextProtocolTest {
    @Test
    fun userMessageSeparatesOriginalFromSpokenInstruction() {
        val value = CleanupTextProtocol.userMessage(
            originalText = "The untouched original.",
            spokenInstruction = "Change only untouched to preserved."
        )

        assertTrue(value.contains("ORIGINAL TEXT\nThe untouched original.\nEND ORIGINAL TEXT"))
        assertTrue(value.contains("SPOKEN EDIT INSTRUCTION\nChange only untouched to preserved."))
    }

    @Test
    fun sanitizesOnlyAnOuterMarkdownFence() {
        assertEquals("Replacement", CleanupTextProtocol.sanitizeReplacement("```text\nReplacement\n```"))
        assertEquals("Keep `inline` code", CleanupTextProtocol.sanitizeReplacement("Keep `inline` code"))
    }

    @Test
    fun deepSeekCleanupIsPinnedToDeepSeeksOwnProvider() {
        assertEquals(
            "deepseek",
            CleanupProviderRouting.onlyProvider(ProviderModels.OPENROUTER_DEEPSEEK_V4_FLASH)
        )
        assertEquals(
            "deepseek",
            CleanupProviderRouting.onlyProvider("openrouter-text/DeepSeek/future-model:free")
        )
    }

    @Test
    fun otherCleanupModelsKeepNormalOpenRouterRouting() {
        assertNull(CleanupProviderRouting.onlyProvider("openrouter-text/google/gemini-flash-latest"))
        assertNull(CleanupProviderRouting.onlyProvider("openrouter-text/notdeepseek/deepseek-compatible"))
    }
}
