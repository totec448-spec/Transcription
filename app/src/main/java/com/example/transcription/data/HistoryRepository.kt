package com.example.transcription.data

import android.content.Context
import android.app.backup.BackupManager
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryRepository(context: Context) {
    private val backupManager = BackupManager(context)
    private val file = AtomicFile(File(context.filesDir, "transcription_history.json"))
    private val _entries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<TranscriptionEntry>> = _entries

    @Synchronized
    fun add(entry: TranscriptionEntry) {
        _entries.value = (listOf(entry) + _entries.value).sortedForHistory()
        persist()
    }

    @Synchronized
    fun updateText(id: String, text: String) {
        _entries.value = _entries.value.map { if (it.id == id) it.copy(text = text) else it }
        persist()
    }

    @Synchronized
    fun updateTranscription(
        id: String,
        text: String,
        modelId: String,
        fallbackMessage: String? = null,
        costUsd: Double? = null,
        costEstimated: Boolean = false,
        chunkCount: Int = 1,
        cleanupCostUsd: Double? = null,
        silenceRemovedMs: Long? = null
    ) {
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(
                text = text,
                modelId = modelId,
                processing = false,
                failureMessage = null,
                fallbackMessage = fallbackMessage,
                progressLabel = null,
                completedChunks = chunkCount,
                chunkCount = chunkCount,
                queuePosition = 0,
                costUsd = costUsd,
                costEstimated = costEstimated,
                cleanupCostUsd = cleanupCostUsd,
                silenceRemovedMs = silenceRemovedMs
            ) else it
        }
        persist()
    }

    @Synchronized
    fun updateFailure(id: String, message: String, costUsd: Double? = null) {
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(
                processing = false,
                failureMessage = message,
                progressLabel = null,
                queuePosition = 0,
                costUsd = costUsd ?: it.costUsd
            ) else it
        }
        persist()
    }

    @Synchronized
    fun updateImportedAudio(id: String, audioPath: String, audioFormat: String, durationMs: Long, sourceName: String?) {
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(
                audioPath = audioPath,
                audioFormat = audioFormat,
                durationMs = durationMs,
                sourceName = sourceName,
                inputBytes = File(audioPath).length()
            ) else it
        }
        persist()
    }

    @Synchronized
    fun updateMetadata(id: String, title: String, pinned: Boolean) {
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(
                title = title.trim().take(120),
                pinned = pinned
            ) else it
        }.sortedForHistory()
        persist()
    }

    @Synchronized
    fun updateProgress(id: String, label: String, completedChunks: Int, chunkCount: Int, queuePosition: Int = 0) {
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(
                processing = true,
                progressLabel = label,
                completedChunks = completedChunks,
                chunkCount = chunkCount.coerceAtLeast(1),
                queuePosition = queuePosition.coerceAtLeast(0),
                failureMessage = null
            ) else it
        }
        persist()
    }

    @Synchronized
    fun replaceAll(entries: List<TranscriptionEntry>) {
        _entries.value = entries.sortedForHistory()
        persist()
    }

    @Synchronized
    fun delete(id: String) {
        _entries.value.firstOrNull { it.id == id }?.audioPath?.let { File(it).delete() }
        _entries.value = _entries.value.filterNot { it.id == id }
        persist()
    }

    @Synchronized
    fun clear() {
        _entries.value.forEach { it.audioPath?.let { path -> File(path).delete() } }
        _entries.value = emptyList()
        persist()
    }

    private fun readEntries(): List<TranscriptionEntry> = runCatching {
        val json = JSONArray(String(file.readFully(), Charsets.UTF_8))
        buildList {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val interrupted = item.optBoolean("processing", false)
                add(
                    TranscriptionEntry(
                        id = item.getString("id"),
                        text = item.optString("text"),
                        createdAt = item.optLong("createdAt"),
                        durationMs = item.optLong("durationMs"),
                        modelId = item.optString("modelId"),
                        audioPath = item.optString("audioPath").takeIf { it.isNotBlank() },
                        audioFormat = item.optString("audioFormat", "m4a").ifBlank { "m4a" },
                        sourceName = item.optString("sourceName").takeIf { it.isNotBlank() },
                        processing = false,
                        failureMessage = if (interrupted) {
                            "The previous import was interrupted. Tap re-transcribe to try again."
                        } else item.optString("failureMessage").takeIf { it.isNotBlank() },
                        fallbackMessage = item.optString("fallbackMessage").takeIf { it.isNotBlank() },
                        progressLabel = item.optString("progressLabel").takeIf { it.isNotBlank() },
                        completedChunks = item.optInt("completedChunks", 0),
                        chunkCount = item.optInt("chunkCount", 1).coerceAtLeast(1),
                        queuePosition = 0,
                        costUsd = item.optDouble("costUsd").takeIf { !it.isNaN() },
                        costEstimated = item.optBoolean("costEstimated", false),
                        cleanupCostUsd = item.optDouble("cleanupCostUsd").takeIf { !it.isNaN() },
                        silenceRemovedMs = item.optLong("silenceRemovedMs", 0L).takeIf { it > 0L },
                        inputBytes = item.optLong("inputBytes", 0L),
                        title = item.optString("title"),
                        pinned = item.optBoolean("pinned", false)
                    )
                )
            }
        }.sortedForHistory()
    }.getOrDefault(emptyList())

    private fun persist() {
        val data = JSONArray().apply {
            _entries.value.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("text", entry.text)
                    put("createdAt", entry.createdAt)
                    put("durationMs", entry.durationMs)
                    put("modelId", entry.modelId)
                    put("audioPath", entry.audioPath ?: "")
                    put("audioFormat", entry.audioFormat)
                    put("sourceName", entry.sourceName ?: "")
                    put("processing", entry.processing)
                    put("failureMessage", entry.failureMessage ?: "")
                    put("fallbackMessage", entry.fallbackMessage ?: "")
                    put("progressLabel", entry.progressLabel ?: "")
                    put("completedChunks", entry.completedChunks)
                    put("chunkCount", entry.chunkCount)
                    put("queuePosition", entry.queuePosition)
                    entry.costUsd?.let { put("costUsd", it) }
                    put("costEstimated", entry.costEstimated)
                    entry.cleanupCostUsd?.let { put("cleanupCostUsd", it) }
                    entry.silenceRemovedMs?.let { put("silenceRemovedMs", it) }
                    put("inputBytes", entry.inputBytes)
                    put("title", entry.title)
                    put("pinned", entry.pinned)
                })
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(data)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        backupManager.dataChanged()
    }
}
