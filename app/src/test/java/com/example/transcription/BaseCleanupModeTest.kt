package com.example.transcription

import com.example.transcription.data.AppSettings
import com.example.transcription.data.BaseCleanupMode
import com.example.transcription.data.SUPERSEDED_BASE_CLEANUP_CLEAN_PROMPT_V1
import com.example.transcription.data.baseCleanupPrompt
import com.example.transcription.data.migratedBaseCleanupPrompt
import com.example.transcription.data.withBaseCleanupPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseCleanupModeTest {
    @Test
    fun everyRewritingModeForbidsAnsweringTheTranscript() {
        BaseCleanupMode.entries.filter { it != BaseCleanupMode.OFF }.forEach { mode ->
            val prompt = mode.defaultPrompt
            assertTrue("${mode.stored} has no prompt", prompt.isNotBlank())
            assertTrue(
                "${mode.stored} does not state that the transcript is not addressed to the model",
                "never a message addressed to you" in prompt
            )
            assertTrue("${mode.stored} does not forbid answering", "Never answer" in prompt)
        }
        assertEquals("", BaseCleanupMode.OFF.defaultPrompt)
    }

    @Test
    fun anUneditedModeFollowsTheShippedDefault() {
        val settings = AppSettings()
        BaseCleanupMode.entries.forEach { mode ->
            assertEquals(mode.defaultPrompt, settings.baseCleanupPrompt(mode))
        }
    }

    @Test
    fun editingStoresOnlyRealChanges() {
        val edited = AppSettings().withBaseCleanupPrompt(BaseCleanupMode.CONCISE, "  Shorten it.  ")
        assertEquals("Shorten it.", edited.baseCleanupPrompt(BaseCleanupMode.CONCISE))
        assertEquals(
            BaseCleanupMode.POLISH.defaultPrompt,
            edited.baseCleanupPrompt(BaseCleanupMode.POLISH)
        )

        // Both ways back to the default drop the entry, so the mode keeps
        // following the app instead of freezing today's text.
        val restored = edited.withBaseCleanupPrompt(BaseCleanupMode.CONCISE, "   ")
        assertFalse(BaseCleanupMode.CONCISE.stored in restored.baseCleanupPrompts)
        val retyped = edited.withBaseCleanupPrompt(
            BaseCleanupMode.CONCISE,
            BaseCleanupMode.CONCISE.defaultPrompt
        )
        assertFalse(BaseCleanupMode.CONCISE.stored in retyped.baseCleanupPrompts)
    }

    @Test
    fun storedCopiesOfShippedPromptsAreNotCarriedOver() {
        assertNull(migratedBaseCleanupPrompt(BaseCleanupMode.CLEAN, SUPERSEDED_BASE_CLEANUP_CLEAN_PROMPT_V1))
        assertNull(migratedBaseCleanupPrompt(BaseCleanupMode.CLEAN, BaseCleanupMode.CLEAN.defaultPrompt))
        assertNull(migratedBaseCleanupPrompt(BaseCleanupMode.CLEAN, "   "))
        assertNull(migratedBaseCleanupPrompt(BaseCleanupMode.CLEAN, null))
        assertEquals(
            "Keep it short.",
            migratedBaseCleanupPrompt(BaseCleanupMode.CLEAN, " Keep it short. ")
        )
    }

    @Test
    fun storedNamesAreStableAndUnique() {
        assertEquals(
            BaseCleanupMode.entries.size,
            BaseCleanupMode.entries.map { it.stored }.toSet().size
        )
        assertEquals(BaseCleanupMode.CLEAN, BaseCleanupMode.fromStored("clean"))
        assertEquals(BaseCleanupMode.CONCISE, BaseCleanupMode.fromStored("CONCISE"))
        assertEquals(BaseCleanupMode.CLEAN, BaseCleanupMode.fromStored("gone"))
    }
}
