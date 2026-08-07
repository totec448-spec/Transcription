package com.example.transcription

import com.example.transcription.data.TranscriptionEntry
import com.example.transcription.data.sortedForHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionEntryMetadataTest {
    private val entry = TranscriptionEntry(
        id = "note",
        text = "Original transcript stays unchanged.",
        createdAt = 1L,
        durationMs = 1_000L,
        modelId = "model"
    )

    @Test fun customTitleDoesNotChangeTranscript() {
        val edited = entry.copy(title = "Custom title")
        assertEquals("Custom title", edited.displayTitle)
        assertEquals("Original transcript stays unchanged.", edited.text)
    }

    @Test fun customTitleWinsOverTranscriptBeginning() {
        assertEquals("My title", entry.copy(title = "My title").displayTitle)
    }

    @Test fun historyOrderingKeepsPinsFirstAndEachSectionNewestFirst() {
        val ordered = listOf(
            entry.copy(id = "old-unpinned", createdAt = 10L),
            entry.copy(id = "new-unpinned", createdAt = 30L),
            entry.copy(id = "old-pinned", createdAt = 5L, pinned = true),
            entry.copy(id = "new-pinned", createdAt = 20L, pinned = true)
        ).sortedForHistory()

        assertEquals(
            listOf("new-pinned", "old-pinned", "new-unpinned", "old-unpinned"),
            ordered.map(TranscriptionEntry::id)
        )
    }
}
