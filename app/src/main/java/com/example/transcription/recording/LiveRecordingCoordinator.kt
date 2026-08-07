package com.example.transcription.recording

/**
 * Process-local action bridge between the foreground recording service and the
 * active voice IME. The service owns notification/widget lifecycle while the
 * IME keeps ownership of its editor connection and provider WebSocket.
 */
object LiveRecordingCoordinator {
    private val lock = Any()
    private var finishAction: (() -> Unit)? = null
    private var discardAction: (() -> Unit)? = null

    fun attach(onFinish: () -> Unit, onDiscard: () -> Unit) {
        synchronized(lock) {
            finishAction = onFinish
            discardAction = onDiscard
        }
    }

    fun detach() {
        synchronized(lock) {
            finishAction = null
            discardAction = null
        }
    }

    fun requestFinish(): Boolean {
        val action = synchronized(lock) { finishAction } ?: return false
        action()
        return true
    }

    fun requestDiscard(): Boolean {
        val action = synchronized(lock) { discardAction } ?: return false
        action()
        return true
    }
}
