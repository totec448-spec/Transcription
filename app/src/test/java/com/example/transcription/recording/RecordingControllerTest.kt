package com.example.transcription.recording

import com.example.transcription.data.RecordingPhase
import com.example.transcription.data.RecordingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingControllerTest {
    @After fun reset() { RecordingController.set(RecordingState()) }

    @Test fun typingDoesNotStartProcessing() {
        RecordingController.set(RecordingState())
        RecordingController.updateEditedText("Hello", null)
        assertEquals(RecordingPhase.IDLE, RecordingController.state.value.phase)
        assertEquals("Hello", RecordingController.state.value.resultText)
    }

    @Test fun lateAutosaveCannotChangeAnActiveRecordingOrAnotherNote() {
        for (phase in listOf(RecordingPhase.RECORDING, RecordingPhase.PAUSED, RecordingPhase.PROCESSING)) {
            val active = RecordingState(phase = phase, historyId = "note", resultText = "Current")
            RecordingController.set(active)
            RecordingController.updateEditedText("Old draft", "note")
            assertEquals(active, RecordingController.state.value)
        }
        val newer = RecordingState(phase = RecordingPhase.SUCCESS, historyId = "new", resultText = "New")
        RecordingController.set(newer)
        RecordingController.updateEditedText("Old draft", "old")
        assertEquals(newer, RecordingController.state.value)
    }

    @Test fun concurrentMeterUpdatesAreNotLost() {
        RecordingController.set(RecordingState())
        val workers = List(4) {
            Thread { repeat(1000) { RecordingController.update { it.copy(elapsedMs = it.elapsedMs + 1) } } }
        }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)
        assertEquals(4000L, RecordingController.state.value.elapsedMs)
    }
}
