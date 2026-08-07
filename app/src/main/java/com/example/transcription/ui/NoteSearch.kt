package com.example.transcription.ui

import com.example.transcription.data.TranscriptionEntry

/**
 * Local, allocation-bounded Notes search for the Main app. The keyboard Notes
 * browser intentionally keeps its existing unfiltered History snapshot.
 */
internal object NoteSearch {
    fun filter(entries: List<TranscriptionEntry>, query: String): List<TranscriptionEntry> {
        val terms = query
            .trim()
            .split(WHITESPACE)
            .filter(String::isNotBlank)
        if (terms.isEmpty()) return entries

        return entries.filter { entry ->
            terms.all { term ->
                entry.displayTitle.contains(term, ignoreCase = true) ||
                    entry.text.contains(term, ignoreCase = true)
            }
        }
    }

    /**
     * Compose reports an initial unfocused state while a newly inserted field
     * is still waiting for FocusRequester. Only a field that actually held
     * focus may treat a later unfocused state as a user-driven focus loss.
     */
    fun shouldCollapseAfterFocusChange(
        previouslyFocused: Boolean,
        currentlyFocused: Boolean,
        query: String
    ): Boolean = previouslyFocused && !currentlyFocused && query.isBlank()

    private val WHITESPACE = Regex("\\s+")
}
