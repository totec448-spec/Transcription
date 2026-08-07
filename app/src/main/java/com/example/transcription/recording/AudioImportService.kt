package com.example.transcription.recording

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.example.transcription.AppContainer
import com.example.transcription.data.HistoryNavigationController
import com.example.transcription.data.ClipboardFeedback
import com.example.transcription.data.TranscriptionEntry
import com.example.transcription.network.TranscriptionProviderRegistry
import com.example.transcription.widget.TranscriptionWidgetProvider
import java.util.ArrayDeque

/** Persistent foreground FIFO. Every share/picker intent becomes its own visible History job. */
class AudioImportService : Service() {
    private val queue = ArrayDeque<ImportJob>()
    private val queueLock = Any()
    @Volatile private var workerRunning = false
    @Volatile private var currentJob: ImportJob? = null
    @Volatile private var latestStartId = 0
    @Volatile private var latestPreview = ""

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_IMPORT) return START_NOT_STICKY
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return START_NOT_STICKY
        val entryId = intent.getStringExtra(EXTRA_HISTORY_ID) ?: return START_NOT_STICKY
        latestStartId = maxOf(latestStartId, startId)
        startImportForeground("Adding audio to queue")

        val settings = AppContainer.settings.settings.value
        if (AppContainer.history.entries.value.none { it.id == entryId }) {
            AppContainer.history.add(
                TranscriptionEntry(
                    id = entryId,
                    text = "",
                    createdAt = System.currentTimeMillis(),
                    durationMs = 0L,
                    modelId = settings.selectedModel,
                    sourceName = "Audio import",
                    processing = true,
                    progressLabel = "Queued"
                )
            )
        }
        HistoryNavigationController.open(entryId)
        synchronized(queueLock) {
            queue.addLast(ImportJob(uri, entryId, intent.getStringExtra(EXTRA_MIME_TYPE)))
            updateQueuedPositionsLocked()
            if (!workerRunning) {
                workerRunning = true
                Thread(::drainQueue, "audio-import-queue").start()
            }
        }
        TranscriptionWidgetProvider.updateAll(this)
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun drainQueue() {
        while (true) {
            val job = synchronized(queueLock) {
                if (queue.isEmpty()) {
                    workerRunning = false
                    currentJob = null
                    null
                } else {
                    queue.removeFirst().also {
                        currentJob = it
                        updateQueuedPositionsLocked()
                    }
                }
            } ?: break
            process(job)
            synchronized(queueLock) { currentJob = null }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val settings = AppContainer.settings.settings.value
        if (settings.persistentReadyNotification) NotificationHelper.showReady(this, latestPreview)
        else NotificationHelper.cancelReady(this)
        TranscriptionWidgetProvider.updateAll(this)
        stopSelfResult(latestStartId)
    }

    private fun process(job: ImportJob) {
        val settings = AppContainer.settings.settings.value
        try {
            updateProgress(job, "Importing audio", 0, 1)
            val imported = AudioImportManager(this).import(job.uri, job.entryId, job.mimeType)
            AppContainer.history.updateImportedAudio(
                job.entryId,
                imported.file.absolutePath,
                imported.wireFormat,
                imported.durationMs,
                imported.displayName
            )
            require(!settings.wifiOnly || isOnWifi()) {
                "Wi-Fi only is enabled. Connect to Wi-Fi or disable it, then tap re-transcribe."
            }
            val (primaryModel, fallbackModel) =
                AppContainer.resolvedTranscriptionModels(settings.selectedModel)
            val result = BatchTranscriptionEngine(
                this,
                TranscriptionProviderRegistry(AppContainer.secrets)
            ).transcribe(
                file = imported.file,
                audioFormat = imported.wireFormat,
                durationMs = imported.durationMs,
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
            ) { progress -> updateProgress(job, progress.label, progress.completed, progress.total) }

            AppContainer.history.updateTranscription(
                job.entryId,
                result.text,
                result.model,
                result.fallbackMessage,
                result.costUsd,
                result.costEstimated,
                result.chunkCount,
                result.cleanupCostUsd,
                result.silenceRemovedMs
            )
            latestPreview = result.text
            if (settings.autoCopy) {
                ClipboardFeedback.copy(this, result.text)
            }
        } catch (error: Throwable) {
            AppContainer.history.updateFailure(
                job.entryId,
                error.message ?: "The imported audio could not be transcribed.",
                (error as? BatchTranscriptionException)?.costUsd
            )
        } finally {
            HistoryNavigationController.open(job.entryId)
            TranscriptionWidgetProvider.updateAll(this)
        }
    }

    private fun updateProgress(job: ImportJob, label: String, completed: Int, total: Int) {
        AppContainer.history.updateProgress(job.entryId, label, completed, total)
        val pending = synchronized(queueLock) { queue.size }
        getSystemService(android.app.NotificationManager::class.java).notify(
            NotificationHelper.IMPORT_ID,
            NotificationHelper.importing(this, if (pending > 0) "$label · $pending queued" else label)
        )
        TranscriptionWidgetProvider.updateAll(this)
    }

    private fun updateQueuedPositionsLocked() {
        queue.forEachIndexed { index, job ->
            AppContainer.history.updateProgress(job.entryId, "Queued · position ${index + 1}", 0, 1, index + 1)
        }
    }

    private fun startImportForeground(message: String) {
        val notification = NotificationHelper.importing(this, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.IMPORT_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NotificationHelper.IMPORT_ID, notification)
    }

    private fun isOnWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private data class ImportJob(val uri: Uri, val entryId: String, val mimeType: String?)

    companion object {
        const val ACTION_IMPORT = "com.example.transcription.action.IMPORT_AUDIO"
        const val EXTRA_URI = "audio_uri"
        const val EXTRA_MIME_TYPE = "audio_mime_type"
        const val EXTRA_HISTORY_ID = "history_id"
    }
}
