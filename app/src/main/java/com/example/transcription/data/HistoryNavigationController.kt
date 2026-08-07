package com.example.transcription.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps a share/import deep-link alive until the Notes UI has opened that entry. */
object HistoryNavigationController {
    private val _requestedEntryId = MutableStateFlow<String?>(null)
    val requestedEntryId: StateFlow<String?> = _requestedEntryId

    fun open(entryId: String) {
        _requestedEntryId.value = entryId
    }

    fun consume(entryId: String) {
        if (_requestedEntryId.value == entryId) _requestedEntryId.value = null
    }
}
