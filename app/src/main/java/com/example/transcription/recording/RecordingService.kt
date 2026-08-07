package com.example.transcription.recording

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.transcription.AppContainer
import com.example.transcription.data.RecordingPhase
import com.example.transcription.data.ClipboardFeedback
import com.example.transcription.data.RecordingState
import com.example.transcription.data.TranscriptionEntry
import com.example.transcription.network.TranscriptionProviderRegistry
import com.example.transcription.widget.TranscriptionWidgetProvider
import java.io.File
import java.util.UUID

class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var elapsedBeforePause = 0L
    private var lastWidgetUpdateMs = 0L
    private val micLevel = MicLevelNormalizer(AUDIO_SAMPLE_MS.toInt())
    private val micPeaks = MicPeakSource(AUDIO_SAMPLE_MS.toInt())
    /** Which stored profile the current recording is measured against. */
    private var micProfileKey = MicPeakSource.KEY_RECORDER
    private var meteringActive = false
    @Volatile private var activeEngine: BatchTranscriptionEngine? = null
    private var modelOverride: String? = null
    private var suppressAutomaticCopy = false

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCARD && RecordingController.state.value.phase == RecordingPhase.ERROR) {
            startForeground(NotificationHelper.ACTIVE_ID, NotificationHelper.active(this, RecordingController.state.value.copy(phase = RecordingPhase.PROCESSING)))
        }
        when (intent?.action) {
            ACTION_START -> startRecording(
                requestedModel = intent.getStringExtra(EXTRA_MODEL_OVERRIDE),
                requestId = intent.getStringExtra(EXTRA_REQUEST_ID),
                suppressCopy = intent.getBooleanExtra(EXTRA_SUPPRESS_AUTO_COPY, false)
            )
            ACTION_TOGGLE_PAUSE -> if (!RecordingController.state.value.isLive) togglePause()
            ACTION_FINISH -> {
                if (!LiveRecordingCoordinator.requestFinish() &&
                    !RecordingController.state.value.isLive
                ) {
                    finishRecording()
                }
            }
            ACTION_DISCARD -> {
                if (!LiveRecordingCoordinator.requestDiscard()) discardRecording()
            }
            ACTION_RETRY -> retryTranscription()
            ACTION_RETRANSCRIBE -> retranscribeHistory(intent.getStringExtra(EXTRA_HISTORY_ID))
            ACTION_USE_FALLBACK -> useFallback()
            ACTION_ABANDON -> abandonProcessing()
            ACTION_CANCEL_REQUEST -> cancelRequest(intent.getStringExtra(EXTRA_REQUEST_ID))
            ACTION_LIVE_START -> startLiveForeground()
            ACTION_LIVE_REFRESH -> refreshLiveForeground()
            ACTION_LIVE_STOP -> stopLiveForeground()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(
        requestedModel: String? = null,
        requestId: String? = null,
        suppressCopy: Boolean = false
    ) {
        if (CleanupCoordinator.state.value.busy) return
        val current = RecordingController.state.value.phase
        if (current == RecordingPhase.RECORDING || current == RecordingPhase.PAUSED || current == RecordingPhase.PROCESSING) return
        // Replace any terminal state before touching the recorder. An IME may
        // otherwise poll the previous SUCCESS while this command is still being
        // dispatched and insert an old transcript into a new editor.
        RecordingController.set(RecordingState(requestId = requestId))
        suppressAutomaticCopy = suppressCopy
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required. Open the app once to grant it.")
            return
        }
        val output = latestAudioFile().apply { parentFile?.mkdirs(); if (exists()) delete() }
        val settings = AppContainer.settings.settings.value
        modelOverride = requestedModel?.takeIf(String::isNotBlank)
        runCatching {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(settings.audioChannels)
                setAudioEncodingBitRate(settings.audioBitRateBps)
                setAudioSamplingRate(settings.audioSampleRateHz)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
        }.onFailure {
            recorder?.release()
            recorder = null
            fail("Recording could not start: ${it.message ?: "unknown error"}")
            return
        }
        startedAt = android.os.SystemClock.elapsedRealtime()
        elapsedBeforePause = 0L
        micPeaks.start(this, AppContainer.micProfiles)
        micProfileKey = micPeaks.profileKey()
        micLevel.reset(AppContainer.micProfiles.load(micProfileKey))
        meteringActive = true
        val state = RecordingState(
            phase = RecordingPhase.RECORDING,
            audioPath = output.absolutePath,
            requestId = requestId
        )
        RecordingController.set(state)
        NotificationHelper.cancelReady(this)
        startForeground(NotificationHelper.ACTIVE_ID, NotificationHelper.active(this, state))
        tick()
        TranscriptionWidgetProvider.updateAll(this)
    }

    private fun startLiveForeground() {
        val state = RecordingController.state.value
        if (!state.isLive || state.phase != RecordingPhase.RECORDING) return
        NotificationHelper.cancelReady(this)
        startForeground(NotificationHelper.ACTIVE_ID, NotificationHelper.active(this, state))
        TranscriptionWidgetProvider.updateAll(this)
    }

    private fun refreshLiveForeground() {
        val state = RecordingController.state.value
        if (!state.isLive) return
        refreshActiveNotification()
        TranscriptionWidgetProvider.updateAll(this)
    }

    private fun stopLiveForeground() {
        val state = RecordingController.state.value
        stopForeground(STOP_FOREGROUND_REMOVE)
        val settings = AppContainer.settings.settings.value
        if (settings.persistentReadyNotification) {
            NotificationHelper.showReady(this, state.resultText)
        } else {
            NotificationHelper.cancelReady(this)
        }
        TranscriptionWidgetProvider.updateAll(this)
        stopSelf()
    }

    private fun togglePause() {
        val state = RecordingController.state.value
        when (state.phase) {
            RecordingPhase.RECORDING -> runCatching {
                recorder?.pause()
                elapsedBeforePause += android.os.SystemClock.elapsedRealtime() - startedAt
                RecordingController.update { it.copy(phase = RecordingPhase.PAUSED, elapsedMs = elapsedBeforePause, amplitude = 0f) }
                refreshActiveNotification()
            }.onFailure { fail("Pause failed: ${it.message}") }
            RecordingPhase.PAUSED -> runCatching {
                recorder?.resume()
                startedAt = android.os.SystemClock.elapsedRealtime()
                RecordingController.update { it.copy(phase = RecordingPhase.RECORDING) }
                refreshActiveNotification()
            }.onFailure { fail("Resume failed: ${it.message}") }
            else -> Unit
        }
        TranscriptionWidgetProvider.updateAll(this)
    }

    private fun finishRecording() {
        val state = RecordingController.state.value
        if (state.phase != RecordingPhase.RECORDING && state.phase != RecordingPhase.PAUSED) return
        handler.removeCallbacksAndMessages(null)
        val duration = if (state.phase == RecordingPhase.RECORDING) {
            elapsedBeforePause + android.os.SystemClock.elapsedRealtime() - startedAt
        } else elapsedBeforePause
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        releaseMeter()
        RecordingController.update { it.copy(phase = RecordingPhase.PROCESSING, elapsedMs = duration, amplitude = 0f, errorMessage = null) }
        refreshActiveNotification()
        TranscriptionWidgetProvider.updateAll(this)
        transcribe(duration)
    }

    private fun retryTranscription() {
        val state = RecordingController.state.value
        if (state.phase != RecordingPhase.ERROR || state.audioPath.isNullOrBlank() || !File(state.audioPath).exists()) return
        RecordingController.update { it.copy(phase = RecordingPhase.PROCESSING, errorMessage = null) }
        startForeground(NotificationHelper.ACTIVE_ID, NotificationHelper.active(this, RecordingController.state.value))
        TranscriptionWidgetProvider.updateAll(this)
        val audioFormat = state.historyId
            ?.let { id -> AppContainer.history.entries.value.firstOrNull { it.id == id }?.audioFormat }
            ?: "m4a"
        transcribe(state.elapsedMs, state.historyId, audioFormat)
    }

    private fun retranscribeHistory(historyId: String?) {
        val phase = RecordingController.state.value.phase
        if (phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED || phase == RecordingPhase.PROCESSING) return
        val entry = AppContainer.history.entries.value.firstOrNull { it.id == historyId }
            ?: return fail("The saved voice note could not be found.")
        val path = entry.audioPath
        if (path.isNullOrBlank() || !File(path).exists()) return fail("The saved audio file is missing.")
        val state = RecordingState(
            phase = RecordingPhase.PROCESSING,
            elapsedMs = entry.durationMs,
            audioPath = path,
            resultText = entry.text,
            historyId = entry.id
        )
        RecordingController.set(state)
        AppContainer.history.updateProgress(entry.id, "Preparing audio", 0, 1)
        NotificationHelper.cancelReady(this)
        startForeground(NotificationHelper.ACTIVE_ID, NotificationHelper.active(this, state))
        TranscriptionWidgetProvider.updateAll(this)
        transcribe(entry.durationMs, entry.id, entry.audioFormat)
    }

    private fun transcribe(duration: Long, targetHistoryId: String? = null, audioFormat: String = "m4a") {
        val settings = AppContainer.settings.settings.value
        if (settings.wifiOnly && !isOnWifi()) {
            failTranscription("Wi-Fi only is enabled. Connect to Wi-Fi or disable it, then retry.", duration, targetHistoryId)
            return
        }
        val path = RecordingController.state.value.audioPath ?: return fail("The audio file is missing.")
        if (targetHistoryId == null && duration > settings.maxAutomaticUploadMinutes * 60_000L) {
            failTranscription(
                "Not uploaded because this recording is longer than ${settings.maxAutomaticUploadMinutes} minutes. " +
                    "The audio is safe in Notes; re-transcribe it there after confirming.",
                duration,
                null
            )
            return
        }
        val engine = BatchTranscriptionEngine(
            this,
            TranscriptionProviderRegistry(AppContainer.secrets)
        )
        activeEngine = engine
        val (primaryModel, fallbackModel) =
            AppContainer.resolvedTranscriptionModels(modelOverride ?: settings.selectedModel)
        Thread {
            runCatching {
                engine.transcribe(
                    file = File(path),
                    audioFormat = audioFormat,
                    durationMs = duration,
                    primaryModel = primaryModel,
                    fallbackModel = fallbackModel,
                    language = settings.language,
                    sendAudioFormat = settings.sendAudioFormat,
                    multimodalPrompt = settings.multimodalPrompt,
                    multimodalReasoningEffort = settings.multimodalReasoningEffort,
                    providerTimeoutSeconds = settings.providerTimeoutSeconds,
                    keepDebugSendCopies = settings.keepDebugSendCopies,
                    catalogModels = AppContainer.models.models.value,
                    trimSilence = settings.trimSilenceBeforeUpload
                ) { progress ->
                    targetHistoryId?.let {
                        AppContainer.history.updateProgress(it, progress.label, progress.completed, progress.total)
                    }
                    handler.post {
                        RecordingController.update { it.copy(statusLabel = progress.label) }
                        getSystemService(android.app.NotificationManager::class.java).notify(
                            NotificationHelper.ACTIVE_ID,
                            NotificationHelper.active(this, RecordingController.state.value)
                        )
                        TranscriptionWidgetProvider.updateAll(this)
                    }
                }
            }.onSuccess { result -> handler.post {
                if (engine.isAbandoned()) return@post
                if (targetHistoryId == null) {
                    complete(
                        result.text,
                        duration,
                        result.model,
                        path,
                        result.fallbackMessage,
                        result.costUsd,
                        result.costEstimated,
                        result.chunkCount,
                        result.cleanupCostUsd,
                        result.silenceRemovedMs
                    )
                } else {
                    completeRetranscription(targetHistoryId, result)
                }
                activeEngine = null
            } }
                .onFailure { error -> handler.post {
                    if (engine.isAbandoned() || error is TranscriptionAbandonedException) return@post
                    failTranscription(
                        error.message ?: "Transcription failed",
                        duration,
                        targetHistoryId,
                        (error as? BatchTranscriptionException)?.costUsd
                    )
                    activeEngine = null
                } }
        }.start()
    }

    private fun failTranscription(message: String, duration: Long, targetHistoryId: String?, costUsd: Double? = null) {
        if (targetHistoryId != null) {
            AppContainer.history.updateFailure(targetHistoryId, message, costUsd)
            fail(message)
            return
        }

        val settings = AppContainer.settings.settings.value
        val source = RecordingController.state.value.audioPath?.let(::File)
        if (!settings.saveFailedAudio || source == null || !source.exists()) {
            fail(message)
            return
        }

        val id = UUID.randomUUID().toString()
        val destination = File(filesDir, "audio_history/$id.m4a").apply { parentFile?.mkdirs() }
        val archived = runCatching {
            source.copyTo(destination, overwrite = true)
            destination
        }.getOrNull()
        if (archived != null) {
            AppContainer.history.add(
                TranscriptionEntry(
                    id = id,
                    text = "",
                    createdAt = System.currentTimeMillis(),
                    durationMs = duration,
                    modelId = modelOverride ?: settings.selectedModel,
                    audioPath = archived.absolutePath,
                    failureMessage = message
                )
            )
            RecordingController.update {
                it.copy(audioPath = archived.absolutePath, historyId = id)
            }
        }
        fail(message)
    }

    private fun completeRetranscription(historyId: String, result: BatchTranscription) {
        AppContainer.history.updateTranscription(
            historyId,
            result.text,
            result.model,
            result.fallbackMessage,
            result.costUsd,
            result.costEstimated,
            result.chunkCount,
            result.cleanupCostUsd,
            result.silenceRemovedMs
        )
        RecordingController.update {
            it.copy(
                phase = RecordingPhase.SUCCESS,
                resultText = result.text,
                statusLabel = null,
                errorMessage = null,
                fallbackMessage = result.fallbackMessage,
                historyId = historyId
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val settings = AppContainer.settings.settings.value
        if (settings.autoCopy) ClipboardFeedback.copy(this, result.text)
        if (settings.persistentReadyNotification) NotificationHelper.showReady(this, result.text) else NotificationHelper.cancelReady(this)
        TranscriptionWidgetProvider.updateAll(this)
        stopSelf()
    }

    private fun complete(
        text: String,
        duration: Long,
        model: String,
        sourcePath: String,
        fallbackMessage: String?,
        costUsd: Double?,
        costEstimated: Boolean,
        chunkCount: Int,
        cleanupCostUsd: Double?,
        silenceRemovedMs: Long?
    ) {
        val id = UUID.randomUUID().toString()
        val settings = AppContainer.settings.settings.value
        val destination = File(filesDir, "audio_history/$id.m4a").apply { parentFile?.mkdirs() }
        val archivedPath = runCatching {
            File(sourcePath).copyTo(destination, overwrite = true)
            destination.absolutePath
        }.getOrNull()
        AppContainer.history.add(
            TranscriptionEntry(
                id = id,
                text = text,
                createdAt = System.currentTimeMillis(),
                durationMs = duration,
                modelId = model,
                audioPath = archivedPath,
                fallbackMessage = fallbackMessage,
                costUsd = costUsd,
                costEstimated = costEstimated,
                cleanupCostUsd = cleanupCostUsd,
                silenceRemovedMs = silenceRemovedMs,
                chunkCount = chunkCount,
                completedChunks = chunkCount,
                inputBytes = File(sourcePath).length()
            )
        )
        if (RecordingResultPolicy.shouldCopyAutomatically(
                autoCopyEnabled = settings.autoCopy,
                suppressForRequest = suppressAutomaticCopy
            )
        ) {
            ClipboardFeedback.copy(this, text)
        }
        RecordingController.update {
            it.copy(
                phase = RecordingPhase.SUCCESS,
                resultText = text,
                statusLabel = null,
                errorMessage = null,
                fallbackMessage = fallbackMessage,
                historyId = id
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (settings.persistentReadyNotification) NotificationHelper.showReady(this, text) else NotificationHelper.cancelReady(this)
        TranscriptionWidgetProvider.updateAll(this)
        stopSelf()
    }

    private fun discardRecording() {
        handler.removeCallbacksAndMessages(null)
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        releaseMeter()
        val state = RecordingController.state.value
        if (state.historyId == null) state.audioPath?.let { File(it).delete() }
        RecordingController.set(RecordingState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (AppContainer.settings.settings.value.persistentReadyNotification) NotificationHelper.showReady(this)
        TranscriptionWidgetProvider.updateAll(this)
        stopSelf()
    }

    private fun useFallback() {
        if (RecordingController.state.value.phase != RecordingPhase.PROCESSING) return
        activeEngine?.useFallback()
        refreshActiveNotification()
        TranscriptionWidgetProvider.updateAll(this)
    }

    private fun abandonProcessing() {
        val state = RecordingController.state.value
        if (state.phase != RecordingPhase.PROCESSING) return
        val settings = AppContainer.settings.settings.value
        activeEngine?.abandon(settings.cancelRequestOnAbandon)
        activeEngine = null
        if (state.historyId != null) {
            AppContainer.history.updateFailure(state.historyId, "Transcription abandoned. Audio kept for retry.")
        } else {
            failTranscription("Transcription abandoned. Audio kept for retry.", state.elapsedMs, null)
        }
        RecordingController.set(RecordingState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (settings.persistentReadyNotification) NotificationHelper.showReady(this) else NotificationHelper.cancelReady(this)
        TranscriptionWidgetProvider.updateAll(this)
        stopSelf()
    }

    /**
     * Cancels only the recording started by the matching transient UI request.
     *
     * Intents are handled on the service main thread in dispatch order, so this
     * also closes the narrow race where an IME view disappears immediately
     * after queueing ACTION_START but before the recorder publishes RECORDING.
     */
    private fun cancelRequest(requestId: String?) {
        if (requestId.isNullOrBlank() ||
            RecordingController.state.value.requestId != requestId
        ) {
            return
        }
        when (RecordingController.state.value.phase) {
            RecordingPhase.RECORDING, RecordingPhase.PAUSED -> discardRecording()
            RecordingPhase.PROCESSING -> abandonProcessing()
            else -> Unit
        }
    }

    private fun fail(message: String) {
        releaseMeter()
        RecordingController.update {
            it.copy(
                phase = RecordingPhase.ERROR,
                errorMessage = message,
                fallbackMessage = null,
                amplitude = 0f
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationHelper.cancelReady(this)
        TranscriptionWidgetProvider.updateAll(this)
        stopSelf()
    }

    private fun tick() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val state = RecordingController.state.value
                if (state.phase == RecordingPhase.RECORDING) {
                    val elapsed = elapsedBeforePause + android.os.SystemClock.elapsedRealtime() - startedAt
                    val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val raw = micPeaks.read(amplitude)
                    // A device whose recorder meter is dead switches to PCM a
                    // second into the recording, and that is a different scale
                    // with a different stored profile — so the trackers start
                    // over against the right one rather than carrying a floor
                    // measured on constant zeroes.
                    if (micPeaks.profileKey() != micProfileKey) {
                        micProfileKey = micPeaks.profileKey()
                        micLevel.reset(AppContainer.micProfiles.load(micProfileKey))
                    }
                    // Normalized against this microphone's own observed range.
                    // The previous fixed 48 dB window left quiet hardware — which
                    // most phones are — permanently pinned near the baseline.
                    val level = micLevel.accept(raw)
                    RecordingController.update {
                        it.copy(elapsedMs = elapsed, amplitude = level, waveform = (it.waveform + level).takeLast(48))
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastWidgetUpdateMs >= WIDGET_UPDATE_MS) {
                        lastWidgetUpdateMs = now
                        TranscriptionWidgetProvider.updateWaveforms(this@RecordingService)
                    }
                }
                if (state.phase == RecordingPhase.RECORDING || state.phase == RecordingPhase.PAUSED) handler.postDelayed(this, AUDIO_SAMPLE_MS)
            }
        }, AUDIO_SAMPLE_MS)
    }

    /**
     * Stops metering and folds what this recording learned about the microphone
     * into the stored profile, so the next one opens already calibrated. Safe to
     * call from every path that releases the recorder, including twice.
     */
    private fun releaseMeter() {
        if (!meteringActive) return
        meteringActive = false
        micPeaks.stop()
        micLevel.observed()?.let { AppContainer.micProfiles.record(micProfileKey, it) }
    }

    private fun refreshActiveNotification() {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NotificationHelper.ACTIVE_ID, NotificationHelper.active(this, RecordingController.state.value))
    }

    private fun latestAudioFile() = File(filesDir, "latest/recording.m4a")

    private fun isOnWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseMeter()
        recorder?.release()
        recorder = null
        super.onDestroy()
    }

    companion object {
        private const val AUDIO_SAMPLE_MS = 200L
        private const val WIDGET_UPDATE_MS = 600L
        const val ACTION_START = "com.example.transcription.action.START"
        const val ACTION_TOGGLE_PAUSE = "com.example.transcription.action.TOGGLE_PAUSE"
        const val ACTION_FINISH = "com.example.transcription.action.FINISH"
        const val ACTION_DISCARD = "com.example.transcription.action.DISCARD"
        const val ACTION_RETRY = "com.example.transcription.action.RETRY"
        const val ACTION_RETRANSCRIBE = "com.example.transcription.action.RETRANSCRIBE"
        const val ACTION_USE_FALLBACK = "com.example.transcription.action.USE_FALLBACK"
        const val ACTION_ABANDON = "com.example.transcription.action.ABANDON"
        const val ACTION_CANCEL_REQUEST = "com.example.transcription.action.CANCEL_REQUEST"
        const val ACTION_LIVE_START = "com.example.transcription.action.LIVE_START"
        const val ACTION_LIVE_REFRESH = "com.example.transcription.action.LIVE_REFRESH"
        const val ACTION_LIVE_STOP = "com.example.transcription.action.LIVE_STOP"
        const val EXTRA_HISTORY_ID = "history_id"
        const val EXTRA_MODEL_OVERRIDE = "model_override"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_SUPPRESS_AUTO_COPY = "suppress_auto_copy"
    }
}
