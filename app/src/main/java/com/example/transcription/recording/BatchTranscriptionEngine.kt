package com.example.transcription.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Build
import com.example.transcription.data.SendAudioFormat
import com.example.transcription.data.TranscriptionModel
import com.example.transcription.data.TranscriptionProvider
import com.example.transcription.network.ProviderStage
import com.example.transcription.network.ProviderTranscriptionRequest
import com.example.transcription.network.TranscriptionProviderRegistry
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class BatchProgress(val label: String, val completed: Int, val total: Int)

data class BatchTranscription(
    val text: String,
    val model: String,
    val fallbackMessage: String?,
    val costUsd: Double?,
    val costEstimated: Boolean,
    val chunkCount: Int,
    val billedSeconds: Double?,
    val totalTokens: Long?,
    /** Billed separately from the transcription, so it is carried separately. */
    val cleanupCostUsd: Double? = null,
    /**
     * How much dead air the pre-upload pass removed, or `null` when it did not
     * run or kept too little to bother reporting. Worth surfacing because it is
     * the difference between what was recorded and what was paid for.
     */
    val silenceRemovedMs: Long? = null
)

class BatchTranscriptionException(
    message: String,
    val completedChunks: Int,
    val chunkCount: Int,
    val costUsd: Double?
) : Exception(message)

/** Shared path for recordings, re-transcriptions, shared files and picker imports. */
class BatchTranscriptionEngine(
    private val context: Context,
    private val providers: TranscriptionProviderRegistry
) {
    private val fallbackRequested = AtomicBoolean(false)
    private val abandoned = AtomicBoolean(false)

    fun useFallback() {
        fallbackRequested.set(true)
        providers.cancelActive()
    }

    fun abandon(cancelNetwork: Boolean) {
        abandoned.set(true)
        if (cancelNetwork) providers.cancelActive()
    }

    fun isAbandoned() = abandoned.get()

    fun transcribe(
        file: File,
        audioFormat: String,
        durationMs: Long,
        primaryModel: String,
        fallbackModel: String,
        language: String,
        sendAudioFormat: SendAudioFormat,
        multimodalPrompt: String,
        multimodalReasoningEffort: String,
        providerTimeoutSeconds: Int,
        keepDebugSendCopies: Boolean,
        catalogModels: List<TranscriptionModel>,
        trimSilence: Boolean = false,
        // The spoken Edit instruction runs through this same engine and must
        // stay raw: cleaning it would rewrite the command instead of the text.
        applyBaseCleanup: Boolean = true,
        onProgress: (BatchProgress) -> Unit = {}
    ): BatchTranscription {
        var partIndex = 0
        var partCount = 1
        var lastLabel: String? = null

        // Deduplicated because these arrive from more than one place — OkHttp
        // repeats its call events on a retry and the AssemblyAI poll loop
        // repeats them once per poll — and republishing an unchanged label
        // would rewrite the notification, the widget and the note for nothing.
        fun publish(label: String, completed: Int = partIndex, total: Int = partCount) {
            if (label == lastLabel) return
            lastLabel = label
            onProgress(BatchProgress(label, completed, total))
        }

        fun part(base: String) = if (partCount <= 1) base else "$base · part ${partIndex + 1} of $partCount"

        // A whole-file pass that ran and declined to keep its output still
        // answered the question for every part inside it. Leaving the per-part
        // trim armed made a six-minute recording encode itself three times over
        // and throw all three away — twelve of its thirty seconds.
        val trimAttempted = SilenceTrimmer.shouldTrim(trimSilence, durationMs)
        val trimPerChunk = trimSilence && !trimAttempted
        if (trimAttempted) publish("Cutting silence", 0, 1)
        // Trimming runs before chunking, not per chunk. The pauses are what
        // push a recording over the provider's three-minute limit in the first
        // place, so removing them first can turn a two-part upload back into a
        // single request — and the parts that do remain are then cut at the
        // right places instead of at offsets measured in dead air.
        val trimmed = trimWholeRecording(file, audioFormat, durationMs, primaryModel, sendAudioFormat, trimSilence)
        val effectiveDurationMs = trimmed?.durationMs ?: durationMs
        // Only what a whole-file pass actually removed. A probe that reads back
        // marginally longer than the source is rounding, not negative silence.
        val silenceRemovedMs = (durationMs - effectiveDurationMs).takeIf { trimmed != null && it > 0L }
        if (effectiveDurationMs > AudioChunkPlan.MAX_CHUNK_MS) publish("Splitting into parts", 0, 1)
        val prepared = AudioChunker(context).prepare(
            trimmed?.file ?: file,
            trimmed?.format ?: audioFormat,
            effectiveDurationMs
        )
        partCount = prepared.chunks.size
        val chunkDurationMs = effectiveDurationMs / prepared.chunks.size.coerceAtLeast(1)
        var cost = 0.0
        var hasCost = false
        var costEstimated = false
        var seconds = 0.0
        var hasSeconds = false
        var tokens = 0L
        var hasTokens = false
        val texts = mutableListOf<String>()
        val usedModels = mutableListOf<String>()
        val fallbackMessages = mutableListOf<String>()
        var activeProvider = ""
        // The whole wait for a minute of audio happens inside one blocking
        // request, so without these the status would sit on a single label from
        // the moment the file leaves until the transcript comes back.
        providers.onStage = { stage ->
            publish(
                part(
                    when (stage) {
                        ProviderStage.UPLOADING -> "Uploading to $activeProvider"
                        ProviderStage.WAITING -> "$activeProvider is transcribing"
                        ProviderStage.RECEIVING -> "Receiving the transcript"
                    }
                )
            )
        }
        try {
            prepared.chunks.forEachIndexed { index, chunk ->
                if (abandoned.get()) throw TranscriptionAbandonedException()
                val attemptedPrimary = !fallbackRequested.get()
                val selectedModel = if (attemptedPrimary) primaryModel else fallbackModel
                val providerLabel = com.example.transcription.data.ProviderModels.provider(selectedModel).label
                partIndex = index
                activeProvider = providerLabel
                preparationLabel(chunk, selectedModel, sendAudioFormat, trimPerChunk, chunkDurationMs)
                    ?.let { publish(part(it)) }
                val outcome = try {
                    val initial = transcribePrepared(
                        chunk,
                        selectedModel,
                        chunkDurationMs,
                        language,
                        sendAudioFormat,
                        multimodalPrompt,
                        MultimodalReasoningResolver.resolve(selectedModel, multimodalReasoningEffort, catalogModels),
                        MultimodalParameterResolver.supportsTemperature(selectedModel, catalogModels),
                        providerTimeoutSeconds,
                        keepDebugSendCopies,
                        trimPerChunk,
                        chunkDurationMs
                    )
                    selectedModel to initial
                } catch (error: Exception) {
                    if (abandoned.get()) throw TranscriptionAbandonedException()
                    if (fallbackModel.isBlank() || selectedModel == fallbackModel) throw error
                    fallbackMessages += "Part ${index + 1}: ${error.message ?: "Primary model failed"}"
                    activeProvider = com.example.transcription.data.ProviderModels.provider(fallbackModel).label
                    publish(part("Retrying with $activeProvider"))
                    try {
                        fallbackModel to transcribePrepared(
                            chunk,
                            fallbackModel,
                            chunkDurationMs,
                            language,
                            sendAudioFormat,
                            multimodalPrompt,
                            MultimodalReasoningResolver.resolve(fallbackModel, multimodalReasoningEffort, catalogModels),
                            MultimodalParameterResolver.supportsTemperature(fallbackModel, catalogModels),
                            providerTimeoutSeconds,
                            keepDebugSendCopies,
                            trimPerChunk,
                            chunkDurationMs
                        )
                    } catch (fallbackError: Exception) {
                        throw Exception(
                            "${selectedModel.substringAfterLast('/')}: ${error.message}; " +
                                "${fallbackModel.substringAfterLast('/')}: ${fallbackError.message}",
                            fallbackError
                        )
                    }
                }
                if (abandoned.get()) throw TranscriptionAbandonedException()
                val (usedModel, response) = outcome
                texts += response.text
                usedModels += usedModel
                val resolvedCost = response.costUsd ?: estimateCost(
                    catalogModels.firstOrNull { it.id == usedModel || it.id == "openrouter-multimodal/$usedModel" },
                    chunkDurationMs,
                    response.promptTokens,
                    response.completionTokens
                )
                resolvedCost?.let {
                    cost += it
                    hasCost = true
                    costEstimated = costEstimated || response.costEstimated || response.costUsd == null
                }
                val chunkSeconds = response.billedSeconds ?: (chunkDurationMs / 1_000.0)
                com.example.transcription.AppContainer.usage.record(
                    provider = com.example.transcription.data.ProviderModels.provider(usedModel),
                    seconds = chunkSeconds,
                    costUsd = resolvedCost,
                    estimated = response.costEstimated || response.costUsd == null
                )
                response.billedSeconds?.let { seconds += it; hasSeconds = true }
                listOfNotNull(response.promptTokens, response.completionTokens).sum().takeIf { it > 0L }?.let {
                    tokens += it
                    hasTokens = true
                }
                publish(
                    if (partCount == 1) "Transcribed" else "Transcribed part ${index + 1} of $partCount",
                    index + 1,
                    partCount
                )
            }
            val joined = TranscriptJoiner.join(texts)
            val cleaned = if (applyBaseCleanup && !abandoned.get()) {
                val settings = com.example.transcription.AppContainer.settings.settings.value
                // The effective mode, not the stored one: without an OpenRouter
                // key the pass below skips, and announcing it would be a lie.
                val cleanupMode = BaseTextProcessor.effectiveMode(settings)
                if (cleanupMode != com.example.transcription.data.BaseCleanupMode.OFF) {
                    publish("Cleaning up · ${cleanupMode.label}", partCount, partCount)
                }
                BaseTextProcessor.processDetailed(
                    text = joined,
                    settings = settings,
                    cleanupModels = com.example.transcription.AppContainer.models.cleanupModels.value,
                    isCancelled = { abandoned.get() }
                )
            } else CleanedText(joined, null)
            return BatchTranscription(
                text = cleaned.text,
                model = usedModels.distinct().joinToString(" + "),
                fallbackMessage = fallbackMessages.takeIf { it.isNotEmpty() }?.joinToString("\n")?.take(800),
                costUsd = cost.takeIf { hasCost },
                costEstimated = costEstimated,
                chunkCount = prepared.chunks.size,
                billedSeconds = seconds.takeIf { hasSeconds },
                totalTokens = tokens.takeIf { hasTokens },
                cleanupCostUsd = cleaned.costUsd,
                silenceRemovedMs = silenceRemovedMs
            )
        } finally {
            providers.onStage = null
            prepared.close()
            trimmed?.close()
        }
    }

    /**
     * What `AudioSendPreparer` is about to spend time on for this part, named
     * before it starts rather than after: on a long recording the re-encode is
     * seconds of apparent hang, and "Sending" while nothing is being sent is
     * the label that made the whole wait look like one opaque block.
     *
     * `null` when there is nothing to prepare. The whole-recording trim above
     * already writes the wire format, so on the common path this step returns
     * the file untouched — and the "Preparing the upload" it used to announce
     * regardless appeared and vanished inside one frame, which is the flicker
     * rather than a report of it.
     */
    private fun preparationLabel(
        chunk: AudioChunk,
        modelId: String,
        sendAudioFormat: SendAudioFormat,
        trimSilence: Boolean,
        chunkDurationMs: Long
    ): String? {
        val target = AudioSendPreparer.wireFormat(modelId, chunk.format, sendAudioFormat)
        return when {
            SilenceTrimmer.shouldTrim(trimSilence, chunkDurationMs) -> "Cutting silence"
            !chunk.format.equals(target, ignoreCase = true) -> "Converting to ${target.uppercase()}"
            else -> null
        }
    }

    /**
     * Removes the dead air from the whole recording once, in the format the
     * wire will want anyway, so nothing downstream has to re-encode.
     *
     * Returns `null` whenever trimming is off, too short to be worth it, or
     * saved too little — in every one of those cases the caller simply proceeds
     * with the original file, which is why the failure path is not an error.
     */
    private fun trimWholeRecording(
        source: File,
        sourceFormat: String,
        durationMs: Long,
        modelId: String,
        sendAudioFormat: SendAudioFormat,
        trimSilence: Boolean
    ): TrimmedRecording? {
        if (!SilenceTrimmer.shouldTrim(trimSilence, durationMs)) return null
        val format = AudioSendPreparer.wireFormat(modelId, sourceFormat, sendAudioFormat)
        val directory = File(context.cacheDir, "transcription_trim/${UUID.randomUUID()}").apply { mkdirs() }
        val output = SilenceTrimmer.trim(source, File(directory, "trimmed.$format"), format)
        if (output == null) {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
            return null
        }
        // The trimmed length decides how many parts this becomes, so it is read
        // back rather than scaled from the byte ratio, which a variable bitrate
        // encode would make a guess.
        val trimmedMs = runCatching { AudioProbe.read(output).durationUs / 1_000L }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: durationMs
        return TrimmedRecording(output, format, trimmedMs, directory)
    }

    private fun transcribePrepared(
        chunk: AudioChunk,
        modelId: String,
        durationMs: Long,
        language: String,
        sendAudioFormat: SendAudioFormat,
        multimodalPrompt: String,
        multimodalReasoning: ResolvedMultimodalReasoning,
        includeMultimodalTemperature: Boolean,
        timeoutSeconds: Int,
        keepDebugCopy: Boolean,
        trimSilence: Boolean,
        chunkDurationMs: Long
    ) = AudioSendPreparer(context).prepare(
        source = chunk.file,
        sourceFormat = chunk.format,
        modelId = modelId,
        preference = sendAudioFormat,
        keepDebugCopy = keepDebugCopy,
        trimSilence = trimSilence,
        durationMs = chunkDurationMs
    ).use { send ->
        providers.transcribe(
            ProviderTranscriptionRequest(
                modelId = modelId,
                audioFile = send.file,
                audioFormat = send.format,
                durationMs = durationMs,
                language = language,
                multimodalPrompt = multimodalPrompt,
                includeMultimodalReasoning = multimodalReasoning.include,
                multimodalReasoningEffort = multimodalReasoning.effort,
                includeMultimodalTemperature = includeMultimodalTemperature,
                timeoutSeconds = timeoutSeconds
            )
        )
    }

}

internal data class ResolvedMultimodalReasoning(val include: Boolean, val effort: String?)

internal object MultimodalReasoningResolver {
    fun resolve(
        modelId: String,
        preference: String,
        catalogModels: List<TranscriptionModel>
    ): ResolvedMultimodalReasoning {
        if (com.example.transcription.data.ProviderModels.provider(modelId) != TranscriptionProvider.OPENROUTER_MULTIMODAL) {
            return ResolvedMultimodalReasoning(include = false, effort = null)
        }
        val model = catalogModels.firstOrNull { it.id == modelId }
        val supportsReasoning = model?.reasoningMandatory == true ||
            model?.reasoningEfforts?.isNotEmpty() == true ||
            model?.supportedParameters?.contains("reasoning") == true
        if (!supportsReasoning) return ResolvedMultimodalReasoning(include = false, effort = null)
        val normalized = preference.lowercase()
        val effort = when {
            normalized == "auto" -> if (model?.reasoningMandatory == true) null else "none"
            normalized == "none" && model?.reasoningMandatory == true -> null
            model == null || model.reasoningEfforts.isEmpty() || normalized in model.reasoningEfforts -> normalized
            else -> model.defaultReasoningEffort ?: model.reasoningEfforts.lastOrNull()
        }
        return ResolvedMultimodalReasoning(include = true, effort = effort)
    }

}

internal object MultimodalParameterResolver {
    fun supportsTemperature(modelId: String, catalogModels: List<TranscriptionModel>): Boolean {
        if (com.example.transcription.data.ProviderModels.provider(modelId) != TranscriptionProvider.OPENROUTER_MULTIMODAL) {
            return false
        }
        return catalogModels.firstOrNull { it.id == modelId }
            ?.supportedParameters
            ?.contains("temperature") == true
    }
}

private fun estimateCost(
        model: TranscriptionModel?,
        durationMs: Long,
        promptTokens: Long?,
        completionTokens: Long?
    ): Double? {
        model ?: return null
        if (promptTokens != null || completionTokens != null) {
            val input = promptTokens?.times(model.inputPricePerMillionUsd ?: return null)?.div(1_000_000.0) ?: 0.0
            val output = completionTokens?.times(model.outputPricePerMillionUsd ?: 0.0)?.div(1_000_000.0) ?: 0.0
            return input + output
        }
        return model.pricePerHourUsd?.let { durationMs / 3_600_000.0 * it }
}

class TranscriptionAbandonedException : Exception("Transcription abandoned")

private class TrimmedRecording(
    val file: File,
    val format: String,
    val durationMs: Long,
    private val directory: File
) : Closeable {
    override fun close() {
        directory.listFiles()?.forEach(File::delete)
        directory.delete()
    }
}

internal data class ChunkRange(val startMs: Long, val endMs: Long)

internal object AudioChunkPlan {
    const val MAX_CHUNK_MS = 180_000L
    const val OVERLAP_MS = 750L

    /**
     * The parts come out the same length rather than filled to the brim and
     * followed by a remainder. Four minutes used to split into three minutes
     * plus one, and the leftover minute is where a provider's language
     * detection has the least to work with — a trimmed recording once ended up
     * with a fifty-second tail of humming as its own part and came back as
     * "no spoken audio". Two even halves give both parts something to go on and
     * cost nothing, since the count is unchanged.
     */
    fun ranges(durationMs: Long): List<ChunkRange> {
        if (durationMs <= 0L || durationMs <= MAX_CHUNK_MS) return listOf(ChunkRange(0L, durationMs.coerceAtLeast(0L)))
        // Each part after the first repeats OVERLAP_MS of the one before it, so
        // the parts cover less ground than their lengths add up to. Counting
        // them against that shortened stride is what keeps the even lengths from
        // creeping past the provider's ceiling.
        val stride = MAX_CHUNK_MS - OVERLAP_MS
        val parts = (durationMs - OVERLAP_MS + stride - 1) / stride
        val length = (durationMs + (parts - 1) * OVERLAP_MS + parts - 1) / parts
        val ranges = mutableListOf<ChunkRange>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(start + length, durationMs)
            ranges += ChunkRange(start, end)
            if (end == durationMs) break
            start = end - OVERLAP_MS
        }
        return ranges
    }
}

/**
 * Stitches the parts back together, dropping the words a part repeats from the
 * end of the one before it.
 *
 * The overlap search is the same comparison at 24 widths, then 23, and so on,
 * so the words it compares are normalized once into [tail] and [head] instead
 * of inside the candidate loop, where the longest match re-lowercased and
 * re-trimmed the same words twenty-odd times over. Only the last
 * [MAX_OVERLAP_WORDS] words of the accumulated result can overlap anything, so
 * that is all that is kept — the previous version re-split the entire joined
 * transcript for every further part.
 */
internal object TranscriptJoiner {
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_OVERLAP_WORDS = 24

    fun join(parts: List<String>): String {
        var result = parts.firstOrNull().orEmpty().trim()
        var tail = normalizedWords(result).takeLast(MAX_OVERLAP_WORDS)
        parts.drop(1).forEach { rawNext ->
            val next = rawNext.trim()
            if (next.isBlank()) return@forEach
            val right = next.split(WHITESPACE).filter(String::isNotBlank)
            val head = right.take(MAX_OVERLAP_WORDS).map(::normalizedWord)
            val overlap = (minOf(MAX_OVERLAP_WORDS, tail.size, head.size) downTo 2).firstOrNull { count ->
                tail.subList(tail.size - count, tail.size) == head.subList(0, count)
            } ?: 0
            val kept = right.drop(overlap)
            result = (result.trimEnd() + " " + kept.joinToString(" ")).trim()
            tail = (tail + kept.map(::normalizedWord)).takeLast(MAX_OVERLAP_WORDS)
        }
        return result
    }

    private fun normalizedWords(value: String) =
        value.split(WHITESPACE).filter(String::isNotBlank).map(::normalizedWord)

    private fun normalizedWord(value: String) = value.lowercase().trim { !it.isLetterOrDigit() }
}

private data class AudioChunk(val file: File, val format: String)

private class PreparedChunks(val chunks: List<AudioChunk>, private val cleanup: File?) : Closeable {
    override fun close() {
        cleanup?.listFiles()?.forEach(File::delete)
        cleanup?.delete()
    }
}

private class AudioChunker(private val context: Context) {
    /**
     * Reading a probe means opening the container and parsing its track table,
     * and every part used to pay for its own — of the same unchanged file, from
     * inside the loop that was already splitting it. A six-minute recording
     * parsed its own header three times before it sent anything. One read up
     * front answers the same question for every part.
     */
    fun prepare(source: File, wireFormat: String, suppliedDurationMs: Long): PreparedChunks {
        val sourceProbe = lazy { AudioProbe.read(source) }
        val durationMs = suppliedDurationMs.takeIf { it > 0L } ?: (sourceProbe.value.durationUs / 1_000L)
        val ranges = AudioChunkPlan.ranges(durationMs)
        if (ranges.size == 1) return PreparedChunks(listOf(AudioChunk(source, wireFormat)), null)

        val directory = File(context.cacheDir, "transcription_chunks/${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            // The transcoded copy the fallback branch works from, and the probe
            // of it, are likewise made once and shared by every part.
            val normalized = lazy {
                File(directory, "normalized.m4a").also { target ->
                    NativeM4aTranscoder.transcode(source, target, sourceProbe.value)
                }
            }
            val normalizedProbe = lazy { AudioProbe.read(normalized.value) }
            val chunks = ranges.mapIndexed { index, range ->
                val extension = wireFormat.lowercase()
                val target = File(directory, "part-${index + 1}.$extension")
                when (extension) {
                    "m4a" -> remux(source, sourceProbe.value, target, range, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    "ogg" -> {
                        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Long OGG files require Android 10 or newer." }
                        remux(source, sourceProbe.value, target, range, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
                    }
                    "webm" -> remux(source, sourceProbe.value, target, range, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
                    "mp3" -> extractFrames(source, sourceProbe.value, target, range)
                    else -> {
                        val normalizedTarget = File(directory, "part-${index + 1}.m4a")
                        remux(
                            normalized.value,
                            normalizedProbe.value,
                            normalizedTarget,
                            range,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        )
                        return@mapIndexed AudioChunk(normalizedTarget, "m4a")
                    }
                }
                AudioChunk(target, extension)
            }
            PreparedChunks(chunks, directory)
        } catch (error: Throwable) {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
            throw error
        }
    }

    private fun remux(source: File, probe: AudioProbe, target: File, range: ChunkRange, outputFormat: Int) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            extractor.selectTrack(probe.trackIndex)
            extractor.seekTo(range.startMs * 1_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val activeMuxer = MediaMuxer(target.absolutePath, outputFormat)
            muxer = activeMuxer
            val outputTrack = activeMuxer.addTrack(extractor.getTrackFormat(probe.trackIndex))
            activeMuxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(probe.trackFormat.intOrLocal(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024).coerceAtLeast(64 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L || sampleTime >= range.endMs * 1_000L) break
                if (sampleTime < range.startMs * 1_000L) {
                    extractor.advance()
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, sampleTime - range.startMs * 1_000L, 0)
                activeMuxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            check(target.length() > 0L) { "Audio chunk was empty." }
        } finally {
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
            extractor.release()
        }
    }

    private fun extractFrames(source: File, probe: AudioProbe, target: File, range: ChunkRange) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            extractor.selectTrack(probe.trackIndex)
            extractor.seekTo(range.startMs * 1_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buffer = ByteBuffer.allocateDirect(probe.trackFormat.intOrLocal(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024).coerceAtLeast(64 * 1024))
            BufferedOutputStream(target.outputStream(), 256 * 1024).use { output ->
                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0L || sampleTime >= range.endMs * 1_000L) break
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (sampleTime >= range.startMs * 1_000L) {
                        val bytes = ByteArray(size)
                        buffer.position(0)
                        buffer.get(bytes)
                        output.write(bytes)
                    }
                    extractor.advance()
                }
            }
            check(target.length() > 0L) { "MP3 chunk was empty." }
        } finally {
            extractor.release()
        }
    }
}

private fun android.media.MediaFormat.intOrLocal(key: String, fallback: Int) =
    if (containsKey(key)) getInteger(key) else fallback
