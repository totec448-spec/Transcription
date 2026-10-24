package com.example.transcription.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.transcription.AppContainer
import com.example.transcription.data.BaseCleanupMode
import com.example.transcription.data.RecordingPhase
import com.example.transcription.ime.StreamingVoiceSession
import java.io.File

/** Capture, provider socket and finalization belong to the foreground service, never an editor. */
internal class SharedLiveRecording(
    private val context: Context,
    private val modelId: String,
    private val requestId: String,
    private val archive: File,
    private val onComplete: (String, Long, String?, Double?) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onRefresh: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var session: StreamingVoiceSession? = null
    private var cleanup: AutomaticCleanupTask? = null
    private var startedAt = 0L
    private var durationMs = 0L
    private var lastRefresh = 0L
    private var finalText = ""
    private var partialText = ""
    private var lastFinal = ""
    private var finishing = false
    private var closed = false
    private var finalize: Runnable? = null

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        val capture = StreamingVoiceSession(
            context = context.applicationContext,
            modelId = modelId,
            secrets = AppContainer.secrets,
            profiles = AppContainer.micProfiles,
            archiveFile = archive,
            gateSilence = AppContainer.settings.settings.value.trimSilenceBeforeUpload,
            onPartial = { value ->
                if (!closed) { partialText = value.trim(); publishText() }
            },
            onFinal = { value ->
                if (!closed) {
                    val text = value.trim()
                    if (text.isNotBlank() && text != lastFinal) {
                        lastFinal = text
                        finalText = listOf(finalText, text).filter(String::isNotBlank).joinToString(" ")
                        partialText = ""
                        publishText()
                    }
                    finalize?.let {
                        handler.removeCallbacks(it)
                        handler.postDelayed(it, 220L)
                    }
                }
            },
            onAmplitude = { level ->
                if (!closed && !finishing) {
                    RecordingController.update { state ->
                        if (state.requestId == requestId) state.withLevel(SystemClock.elapsedRealtime() - startedAt, level) else state
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRefresh >= 600L) { lastRefresh = now; onRefresh() }
                }
            },
            onSilenceGate = { paused ->
                if (!closed) RecordingController.update { it.copy(liveSilencePaused = paused) }
            },
            onError = { message ->
                if (!closed) {
                    // Preserve captured audio and any transcript when the provider disconnects.
                    session?.accept()
                    if (archive.isFile) complete(text(), message, null)
                    else { closed = true; onFailure(message) }
                }
            }
        )
        session = capture
        runCatching { capture.start() }.onFailure {
            capture.abandon()
            closed = true
            onFailure(it.message ?: "Live recording could not start.")
        }
    }

    private fun text() = listOf(finalText, partialText).filter(String::isNotBlank).joinToString(" ")

    private fun publishText() {
        RecordingController.update { state ->
            if (state.requestId == requestId) state.copy(resultText = text(), liveFinalText = finalText) else state
        }
    }

    fun finish() {
        if (closed || finishing) return
        finishing = true
        durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        session?.accept()
        RecordingController.update {
            it.copy(phase = RecordingPhase.PROCESSING, elapsedMs = durationMs, amplitude = 0f,
                statusLabel = "Finishing live transcript")
        }
        onRefresh()
        val task = Runnable { finalize = null; finishText() }
        finalize = task
        handler.postDelayed(task, 1_100L)
    }

    private fun finishText() {
        if (closed) return
        val raw = text()
        val settings = AppContainer.settings.settings.value
        if (raw.isBlank() || BaseTextProcessor.effectiveMode(settings) == BaseCleanupMode.OFF) {
            complete(raw, null, null)
            return
        }
        val task = BaseTextProcessor.task(raw, settings, AppContainer.models.cleanupModels.value)
        cleanup = task
        RecordingController.update { it.copy(cleanupInProgress = true, statusLabel = "Cleaning up · tap to skip") }
        onRefresh()
        task.start()
        Thread {
            val result = task.await()
            handler.post {
                if (!closed) complete(result.text, result.warning, result.costUsd)
            }
        }.start()
    }

    fun skipCleanup() { cleanup?.skip() }

    private fun complete(text: String, warning: String?, cleanupCost: Double?) {
        if (closed) return
        closed = true
        finalize?.let(handler::removeCallbacks)
        finalize = null
        cleanup?.skip()
        cleanup = null
        val duration = if (finishing) durationMs else (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        onComplete(text, duration, warning, cleanupCost)
    }

    fun discard() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        cleanup?.skip()
        session?.abandon()
        session = null
    }

    /** A system teardown keeps the audio file rather than deleting it as a user discard would. */
    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        cleanup?.skip()
        session?.accept()
        session = null
    }
}
