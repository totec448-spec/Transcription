package com.example.transcription.recording

internal object RecordingResultPolicy {
    fun shouldCopyAutomatically(autoCopyEnabled: Boolean, suppressForRequest: Boolean): Boolean =
        autoCopyEnabled && !suppressForRequest
}
