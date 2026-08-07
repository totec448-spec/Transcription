package com.example.transcription.recording

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

data class ImportedAudio(
    val file: File,
    val wireFormat: String,
    val durationMs: Long,
    val displayName: String
)

/**
 * Copies a content URI into private History storage without loading it into memory.
 * Already-compressed OpenRouter inputs stay byte-for-byte unchanged. Only uncommon or
 * uncompressed inputs are decoded and streamed into AAC/M4A; no WAV intermediate exists.
 */
class AudioImportManager(private val context: Context) {
    fun import(uri: Uri, entryId: String, mimeHint: String?): ImportedAudio {
        val displayName = queryDisplayName(uri) ?: "Shared audio"
        val importDir = File(context.cacheDir, "audio_import").apply { mkdirs() }
        val temporary = File(importDir, "$entryId.source")
        context.contentResolver.openInputStream(uri)?.buffered(COPY_BUFFER_BYTES)?.use { input ->
            temporary.outputStream().buffered(COPY_BUFFER_BYTES).use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        } ?: error("The shared audio file could not be opened.")
        require(temporary.length() > 0L) { "The shared audio file is empty." }

        return try {
            val probe = AudioProbe.read(temporary)
            val strategy = AudioImportFormat.detect(displayName, mimeHint, probe.trackMime)
            val destination = File(context.filesDir, "audio_history/$entryId.${strategy.extension}").apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }
            if (strategy.transcode) {
                NativeM4aTranscoder.transcode(temporary, destination, probe)
            } else if (!temporary.renameTo(destination)) {
                temporary.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
                    destination.outputStream().buffered(COPY_BUFFER_BYTES).use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
                temporary.delete()
            }
            ImportedAudio(destination, strategy.wireFormat, probe.durationUs.coerceAtLeast(0L) / 1_000L, displayName)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val COPY_BUFFER_BYTES = 256 * 1024
    }
}

internal data class ImportStrategy(val wireFormat: String, val extension: String, val transcode: Boolean)

internal object AudioImportFormat {
    fun detect(displayName: String, mimeHint: String?, trackMime: String): ImportStrategy {
        val mime = mimeHint.orEmpty().substringBefore(';').trim().lowercase()
        val track = trackMime.lowercase()
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return when {
            mime == "audio/mpeg" || track == "audio/mpeg" || extension == "mp3" -> direct("mp3")
            mime == "audio/ogg" || track in setOf("audio/opus", "audio/vorbis") || extension in setOf("ogg", "opus", "oga") -> direct("ogg")
            mime == "audio/webm" || extension == "webm" -> direct("webm")
            mime == "audio/aac" || extension == "aac" -> direct("aac")
            mime in setOf("audio/mp4", "audio/x-m4a") || extension in setOf("m4a", "mp4") -> direct("m4a")
            else -> ImportStrategy("m4a", "m4a", transcode = true)
        }
    }

    private fun direct(format: String) = ImportStrategy(format, format, transcode = false)
}

internal data class AudioProbe(
    val trackIndex: Int,
    val trackFormat: MediaFormat,
    val trackMime: String,
    val durationUs: Long,
    val sampleRate: Int,
    val channelCount: Int
) {
    companion object {
        fun read(file: File): AudioProbe {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        return AudioProbe(
                            trackIndex = index,
                            trackFormat = format,
                            trackMime = mime,
                            durationUs = format.longOr(MediaFormat.KEY_DURATION, 0L),
                            sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 48_000),
                            channelCount = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
                        )
                    }
                }
                error("The selected file contains no audio track.")
            } finally {
                extractor.release()
            }
        }
    }
}

private fun MediaFormat.intOr(key: String, fallback: Int) = if (containsKey(key)) getInteger(key) else fallback
private fun MediaFormat.longOr(key: String, fallback: Long) = if (containsKey(key)) getLong(key) else fallback

/** Synchronous, bounded native codec pipeline used only when a source is not a compact supported input. */
internal object NativeM4aTranscoder {
    private const val TIMEOUT_US = 10_000L

    fun transcode(source: File, destination: File, probe: AudioProbe) {
        require(probe.channelCount in 1..2) { "Audio with more than two channels is not supported." }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            extractor.selectTrack(probe.trackIndex)
            val activeDecoder = MediaCodec.createDecoderByType(probe.trackMime).apply {
                configure(probe.trackFormat, null, null, 0)
                start()
            }
            decoder = activeDecoder
            val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, probe.sampleRate, probe.channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (probe.channelCount == 1) 96_000 else 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            val activeEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            encoder = activeEncoder
            val activeMuxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer

            val decoded = ArrayDeque<PcmChunk>()
            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var extractorEnded = false
            var decoderEnded = false
            var encoderInputEnded = false
            var encoderEnded = false
            var muxerTrack = -1
            var idleTurns = 0

            while (!encoderEnded) {
                var progressed = false

                if (!extractorEnded) {
                    val index = activeDecoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val input = requireNotNull(activeDecoder.getInputBuffer(index)).apply { clear() }
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            activeDecoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorEnded = true
                        } else {
                            activeDecoder.queueInputBuffer(index, 0, size, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }

                if (!decoderEnded && decoded.size < 8) {
                    when (val index = activeDecoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val pcm = activeDecoder.outputFormat.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            require(pcm == AudioFormat.ENCODING_PCM_16BIT) { "The native decoder produced an unsupported PCM format." }
                            progressed = true
                        }
                        else -> if (index >= 0) {
                            if (decoderInfo.size > 0) {
                                val output = requireNotNull(activeDecoder.getOutputBuffer(index)).duplicate().apply {
                                    position(decoderInfo.offset)
                                    limit(decoderInfo.offset + decoderInfo.size)
                                }
                                val bytes = ByteArray(decoderInfo.size)
                                output.get(bytes)
                                decoded.add(PcmChunk(bytes, decoderInfo.presentationTimeUs))
                            }
                            decoderEnded = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            activeDecoder.releaseOutputBuffer(index, false)
                            progressed = true
                        }
                    }
                }

                if (!encoderInputEnded && (decoded.isNotEmpty() || decoderEnded)) {
                    val index = activeEncoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val input = requireNotNull(activeEncoder.getInputBuffer(index)).apply { clear() }
                        val chunk = decoded.peekFirst()
                        if (chunk == null) {
                            activeEncoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputEnded = true
                        } else {
                            val count = minOf(input.remaining(), chunk.remaining)
                            input.put(chunk.bytes, chunk.offset, count)
                            val bytesPerFrame = probe.channelCount * 2
                            val pts = chunk.presentationTimeUs + (chunk.offset / bytesPerFrame) * 1_000_000L / probe.sampleRate
                            activeEncoder.queueInputBuffer(index, 0, count, pts, 0)
                            chunk.offset += count
                            if (chunk.remaining == 0) decoded.removeFirst()
                        }
                        progressed = true
                    }
                }

                var drainEncoder = true
                while (drainEncoder) {
                    when (val index = activeEncoder.dequeueOutputBuffer(encoderInfo, if (progressed) 0L else TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> drainEncoder = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC encoder changed format twice." }
                            muxerTrack = activeMuxer.addTrack(activeEncoder.outputFormat)
                            activeMuxer.start()
                            muxerStarted = true
                            progressed = true
                        }
                        else -> if (index >= 0) {
                            val output = requireNotNull(activeEncoder.getOutputBuffer(index))
                            if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encoderInfo.size = 0
                            if (encoderInfo.size > 0) {
                                check(muxerStarted) { "AAC data arrived before its output format." }
                                output.position(encoderInfo.offset)
                                output.limit(encoderInfo.offset + encoderInfo.size)
                                activeMuxer.writeSampleData(muxerTrack, output, encoderInfo)
                            }
                            encoderEnded = encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            activeEncoder.releaseOutputBuffer(index, false)
                            progressed = true
                        }
                    }
                }

                idleTurns = if (progressed) 0 else idleTurns + 1
                check(idleTurns < 1_000) { "Native audio conversion stalled." }
            }
            check(muxerStarted && destination.length() > 0L) { "Native audio conversion produced no output." }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
            extractor.release()
        }
    }

    private data class PcmChunk(val bytes: ByteArray, val presentationTimeUs: Long, var offset: Int = 0) {
        val remaining: Int get() = bytes.size - offset
    }
}
