package com.example.transcription.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RestoreSummary(val restoredNotes: Int, val restoredAudioFiles: Int)

/** User-controlled, provider-independent backup. API keys are intentionally never included. */
class PortableBackupManager(
    private val context: Context,
    private val history: HistoryRepository,
    private val settings: SettingsStore
) {
    fun export(destination: Uri): Int {
        val entries = history.entries.value
        val manifest = JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("settings", settingsToJson(settings.settings.value))
            put("entries", JSONArray().apply { entries.forEach { put(entryToJson(it)) } })
        }
        context.contentResolver.openOutputStream(destination)?.buffered(256 * 1024)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                entries.forEach { entry ->
                    val source = entry.audioPath?.let(::File)?.takeIf(File::isFile) ?: return@forEach
                    zip.putNextEntry(ZipEntry(audioEntryName(entry)))
                    source.inputStream().buffered(256 * 1024).use { it.copyTo(zip, 256 * 1024) }
                    zip.closeEntry()
                }
            }
        } ?: error("The backup destination could not be opened.")
        return entries.size
    }

    fun restore(source: Uri): RestoreSummary {
        var restoredAudio = 0
        lateinit var restoredEntries: List<TranscriptionEntry>
        lateinit var restoredSettings: AppSettings
        context.contentResolver.openInputStream(source)?.buffered(256 * 1024)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                val first = zip.nextEntry ?: error("This backup is empty.")
                require(first.name == MANIFEST && !first.isDirectory) { "This is not a Transcription backup." }
                val manifestBytes = zip.readLimited(MAX_MANIFEST_BYTES)
                val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                require(manifest.optInt("version") == 1) { "Unsupported backup version." }
                restoredSettings = settingsFromJson(manifest.getJSONObject("settings"))
                val array = manifest.getJSONArray("entries")
                restoredEntries = List(array.length()) { entryFromJson(array.getJSONObject(it)) }
                val expected = restoredEntries.associateBy(::audioEntryName)
                val restoredPaths = mutableMapOf<String, String>()
                while (true) {
                    val item = zip.nextEntry ?: break
                    val entry = expected[item.name]
                    if (entry == null || item.isDirectory) continue
                    val destination = File(context.filesDir, "audio_history/${safeId(entry.id)}.${safeFormat(entry.audioFormat)}").apply {
                        parentFile?.mkdirs()
                    }
                    destination.outputStream().buffered(256 * 1024).use { output -> zip.copyTo(output, 256 * 1024) }
                    restoredPaths[entry.id] = destination.absolutePath
                    restoredAudio++
                }
                val adjusted = restoredEntries.map { it.copy(audioPath = restoredPaths[it.id]) }
                val restoredIds = adjusted.mapTo(mutableSetOf()) { it.id }
                history.replaceAll(adjusted + history.entries.value.filterNot { it.id in restoredIds })
                // A backup deliberately carries no API key, so restoring one on
                // a fresh device must still let onboarding ask for it. The flag
                // stays a property of this install rather than of the archive.
                settings.update { current ->
                    restoredSettings.copy(onboardingCompleted = current.onboardingCompleted)
                }
            }
        } ?: error("The backup file could not be opened.")
        return RestoreSummary(restoredEntries.size, restoredAudio)
    }

    private fun entryToJson(entry: TranscriptionEntry) = JSONObject().apply {
        put("id", entry.id)
        put("text", entry.text)
        put("createdAt", entry.createdAt)
        put("durationMs", entry.durationMs)
        put("modelId", entry.modelId)
        put("audioFormat", entry.audioFormat)
        put("sourceName", entry.sourceName ?: "")
        put("failureMessage", entry.failureMessage ?: "")
        put("fallbackMessage", entry.fallbackMessage ?: "")
        put("completedChunks", entry.completedChunks)
        put("chunkCount", entry.chunkCount)
        entry.costUsd?.let { put("costUsd", it) }
        put("costEstimated", entry.costEstimated)
        put("inputBytes", entry.inputBytes)
        put("title", entry.title)
        put("pinned", entry.pinned)
        put("hasAudio", entry.audioPath?.let(::File)?.isFile == true)
    }

    private fun entryFromJson(json: JSONObject) = TranscriptionEntry(
        id = safeId(json.getString("id")),
        text = json.optString("text"),
        createdAt = json.optLong("createdAt"),
        durationMs = json.optLong("durationMs"),
        modelId = json.optString("modelId"),
        audioFormat = safeFormat(json.optString("audioFormat", "m4a")),
        sourceName = json.optString("sourceName").takeIf(String::isNotBlank),
        failureMessage = json.optString("failureMessage").takeIf(String::isNotBlank),
        fallbackMessage = json.optString("fallbackMessage").takeIf(String::isNotBlank),
        completedChunks = json.optInt("completedChunks", 0),
        chunkCount = json.optInt("chunkCount", 1).coerceAtLeast(1),
        costUsd = json.optDouble("costUsd").takeIf { !it.isNaN() },
        costEstimated = json.optBoolean("costEstimated", false),
        inputBytes = json.optLong("inputBytes", 0L),
        title = json.optString("title"),
        pinned = json.optBoolean("pinned", false)
    )

    private fun settingsToJson(value: AppSettings) = JSONObject().apply {
        put("selectedModel", value.selectedModel)
        put("fallbackModel", value.fallbackModel)
        put("language", value.language)
        put("prompt", value.prompt)
        put("multimodalPrompt", value.multimodalPrompt)
        put("multimodalReasoningEffort", value.multimodalReasoningEffort)
        put("cleanupModel", value.cleanupModel)
        put("cleanupPrompt", value.cleanupPrompt)
        put("baseCleanupMode", value.baseCleanupMode.stored)
        put("baseCleanupPrompts", JSONObject(value.baseCleanupPrompts))
        put("baseCleanupReasoningEffort", value.baseCleanupReasoningEffort)
        put("trimSilenceBeforeUpload", value.trimSilenceBeforeUpload)
        put("cleanupReasoningEffort", value.cleanupReasoningEffort)
        put("cleanupMinimumInstructionWords", value.cleanupMinimumInstructionWords)
        put("themeMode", value.themeMode.name)
        put("autoCopy", value.autoCopy)
        put("haptics", value.haptics)
        put("persistentReadyNotification", value.persistentReadyNotification)
        put("saveFailedAudio", value.saveFailedAudio)
        put("wifiOnly", value.wifiOnly)
        put("sendAudioFormat", value.sendAudioFormat.wireName)
        put("maxAutomaticUploadMinutes", value.maxAutomaticUploadMinutes)
        put("providerTimeoutSeconds", value.providerTimeoutSeconds)
        put("cancelRequestOnAbandon", value.cancelRequestOnAbandon)
        put("keepDebugSendCopies", value.keepDebugSendCopies)
        put("audioSampleRateHz", value.audioSampleRateHz)
        put("audioBitRateBps", value.audioBitRateBps)
        put("audioChannels", value.audioChannels)
    }

    private fun settingsFromJson(json: JSONObject) = AppSettings(
        selectedModel = json.optString("selectedModel", ProviderModels.ELEVENLABS_SCRIBE_V2),
        fallbackModel = json.optString("fallbackModel", ProviderModels.OPENROUTER_WHISPER_LARGE_V3),
        language = LanguageCode.normalize(json.optString("language", "auto")),
        prompt = json.optString("prompt"),
        multimodalPrompt = json.optString("multimodalPrompt", DEFAULT_MULTIMODAL_PROMPT),
        multimodalReasoningEffort = json.optString("multimodalReasoningEffort", "auto"),
        cleanupModel = json.optString("cleanupModel", ProviderModels.OPENROUTER_DEEPSEEK_V4_PRO),
        cleanupPrompt = json.optString("cleanupPrompt", DEFAULT_CLEANUP_PROMPT)
            .takeIf { it.isNotBlank() && it.trim() !in setOf(SUPERSEDED_CLEANUP_PROMPT_V1, DEFAULT_CLEANUP_PROMPT_V2) }
            ?: DEFAULT_CLEANUP_PROMPT,
        cleanupReasoningEffort = json.optString("cleanupReasoningEffort", "none"),
        baseCleanupMode = BaseCleanupMode.fromStored(json.optString("baseCleanupMode", BaseCleanupMode.CLEAN.stored)),
        baseCleanupPrompts = baseCleanupPromptsFromJson(json),
        baseCleanupReasoningEffort = json.optString("baseCleanupReasoningEffort", "none"),
        trimSilenceBeforeUpload = json.optBoolean("trimSilenceBeforeUpload", true),
        cleanupMinimumInstructionWords = json.optInt(
            "cleanupMinimumInstructionWords",
            DEFAULT_CLEANUP_MINIMUM_INSTRUCTION_WORDS
        ).coerceIn(0, 50),
        themeMode = runCatching { ThemeMode.valueOf(json.optString("themeMode")) }.getOrDefault(ThemeMode.SYSTEM),
        autoCopy = json.optBoolean("autoCopy", true),
        haptics = json.optBoolean("haptics", false),
        persistentReadyNotification = json.optBoolean("persistentReadyNotification", true),
        saveFailedAudio = json.optBoolean("saveFailedAudio", true),
        wifiOnly = json.optBoolean("wifiOnly", false),
        sendAudioFormat = SendAudioFormat.fromStored(json.optString("sendAudioFormat", "auto")),
        maxAutomaticUploadMinutes = json.optInt("maxAutomaticUploadMinutes", 20).coerceIn(1, 240),
        providerTimeoutSeconds = json.optInt("providerTimeoutSeconds", 20).coerceIn(5, 180),
        cancelRequestOnAbandon = json.optBoolean("cancelRequestOnAbandon", true),
        keepDebugSendCopies = json.optBoolean("keepDebugSendCopies", false),
        audioSampleRateHz = AudioCaptureOptions.sampleRate(json.optInt("audioSampleRateHz", 48_000)),
        audioBitRateBps = AudioCaptureOptions.bitRate(json.optInt("audioBitRateBps", 192_000)),
        audioChannels = AudioCaptureOptions.channelCount(json.optInt("audioChannels", 1))
    )

    /**
     * Reads the per-mode prompt map, falling back to the two single-mode keys a
     * backup written before the extra modes existed carries. Prompts that are
     * only a copy of a shipped default are left out, so restoring an old backup
     * does not reinstate a superseded prompt.
     */
    private fun baseCleanupPromptsFromJson(json: JSONObject): Map<String, String> {
        val stored = json.optJSONObject("baseCleanupPrompts")
        if (stored != null) {
            return BaseCleanupMode.entries.mapNotNull { mode ->
                migratedBaseCleanupPrompt(mode, stored.optString(mode.stored))
                    ?.let { mode.stored to it }
            }.toMap()
        }
        return listOf(
            BaseCleanupMode.CLEAN to "baseCleanupCleanPrompt",
            BaseCleanupMode.POLISH to "baseCleanupPolishPrompt"
        ).mapNotNull { (mode, key) ->
            migratedBaseCleanupPrompt(mode, json.optString(key))?.let { mode.stored to it }
        }.toMap()
    }

    private fun audioEntryName(entry: TranscriptionEntry) = "audio/${safeId(entry.id)}.${safeFormat(entry.audioFormat)}"

    private fun ZipInputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 256 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Backup manifest is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MANIFEST = "transcription-backup.json"
        private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024

        internal fun safeId(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "Backup contains an invalid note id." }
            return value
        }

        internal fun safeFormat(value: String): String {
            val normalized = value.lowercase()
            require(normalized in setOf("m4a", "mp3", "ogg", "aac", "webm", "wav")) { "Backup contains an unsupported audio format." }
            return normalized
        }
    }
}
