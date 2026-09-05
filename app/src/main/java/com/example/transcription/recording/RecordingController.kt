package com.example.transcription.recording

import com.example.transcription.data.RecordingState
import com.example.transcription.data.RecordingPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object RecordingController {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state

    /** A manual edit never changes the recording lifecycle or a newer note. */
    fun updateEditedText(text: String, historyId: String?) = update { current ->
        if (current.phase in setOf(RecordingPhase.IDLE, RecordingPhase.SUCCESS, RecordingPhase.ERROR) &&
            current.historyId == historyId) current.copy(resultText = text) else current
    }

    fun set(value: RecordingState) { _state.value = value }
    fun update(transform: (RecordingState) -> RecordingState) { _state.update(transform) }
}
