package com.example.transcription.recording

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** First completion wins. Skip releases the raw transcript immediately even
 * when the remote request cannot be interrupted promptly. Late results have no
 * route back to the editor, and cancellation belongs only to this request. */
internal class AutomaticCleanupTask(
    private val original: String,
    private val request: (() -> Boolean) -> CleanedText,
    private val cancelRequest: () -> Unit
) {
    private val skipped = AtomicBoolean(false)
    private val result = CompletableFuture<CleanedText>()

    fun start(): AutomaticCleanupTask = apply {
        Thread({
            if (!skipped.get()) {
                val response = runCatching { request { skipped.get() } }.getOrElse {
                    CleanedText(original, null, CleanupFeedback.failure("Selected model", it))
                }
                result.complete(response)
            }
        }, "automatic-cleanup").start()
    }

    fun skip(): Boolean {
        skipped.set(true)
        val won = result.complete(CleanedText(original, null))
        if (won) Thread(cancelRequest, "cancel-cleanup-request").start()
        return won
    }

    fun await(): CleanedText = result.get()
}
