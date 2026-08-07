package com.example.transcription.ui

import com.example.transcription.data.TranscriptionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private val notes = listOf(
        note("pinned", "Project Atlas", "Budget and launch notes"),
        note("transcript", "", "Call Alice about the launch timeline"),
        note("other", "Shopping", "Milk and bread")
    )

    @Test
    fun emptyQueryReturnsOriginalOrderedList() {
        assertSame(notes, NoteSearch.filter(notes, "   "))
    }

    @Test
    fun searchMatchesCustomTitleAndTranscriptIgnoringCase() {
        assertEquals(listOf("pinned"), NoteSearch.filter(notes, "ATLAS").map { it.id })
        assertEquals(listOf("transcript"), NoteSearch.filter(notes, "alice").map { it.id })
    }

    @Test
    fun everySearchTermMustMatchTheSameNote() {
        assertEquals(
            listOf("pinned"),
            NoteSearch.filter(notes, "project launch").map { it.id }
        )
        assertEquals(emptyList<TranscriptionEntry>(), NoteSearch.filter(notes, "atlas milk"))
    }

    @Test
    fun initialUnfocusedCallbackDoesNotCollapseNewSearchField() {
        assertFalse(
            NoteSearch.shouldCollapseAfterFocusChange(
                previouslyFocused = false,
                currentlyFocused = false,
                query = ""
            )
        )
        assertTrue(
            NoteSearch.shouldCollapseAfterFocusChange(
                previouslyFocused = true,
                currentlyFocused = false,
                query = ""
            )
        )
        assertFalse(
            NoteSearch.shouldCollapseAfterFocusChange(
                previouslyFocused = true,
                currentlyFocused = false,
                query = "atlas"
            )
        )
    }

    private fun note(id: String, title: String, text: String) = TranscriptionEntry(
        id = id,
        text = text,
        createdAt = 1L,
        durationMs = 1_000L,
        modelId = "model",
        title = title
    )
}
