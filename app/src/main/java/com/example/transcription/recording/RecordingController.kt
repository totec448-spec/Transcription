package com.example.transcription.recording

import com.example.transcription.data.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object RecordingController {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state

    fun set(value: RecordingState) { _state.value = value }
    fun update(transform: (RecordingState) -> RecordingState) { _state.value = transform(_state.value) }
}
