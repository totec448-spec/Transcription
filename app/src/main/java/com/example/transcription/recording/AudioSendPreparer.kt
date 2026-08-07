package com.example.transcription.recording

import android.content.Context
import android.os.SystemClock

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.transcription.data.ProviderModels
import com.example.transcription.data.SendAudioFormat
import java.io.Closeable
import java.io.File
import java.util.UUID

data class PreparedSendAudio(
    val file: File,
    val format: String,
    val conversionMs: Long,
    private val temporaryDirectory: File?,
    private val keepTemporary: Boolean
) : Closeable {
    override fun close() {
        if (keepTemporary) return
        temporaryDirectory?.listFiles()?.forEach(File::delete)
        temporaryDirectory?.delete()
    }
}

/**
 * Preserves the archive byte-for-byte. Conversion is performed only when the selected wire
 * format differs, and only inside cache. The no-conversion M4A path allocates no extra file.
 */
class AudioSendPreparer(private val context: Context) {
    fun prepare(
        source: File,
        sourceFormat: String,
        modelId: String,
        preference: SendAudioFormat,
        keepDebugCopy: Boolean,
        trimSilence: Boolean = false,
        durationMs: Long = 0L
    ): PreparedSendAudio {
        val normalizedSource = sourceFormat.lowercase().ifBlank { source.extension.lowercase() }
        val targetFormat = wireFormat(modelId, normalizedSource, preference)

        // Trimming already re-encodes, so it produces the wire format in the
        // same pass instead of running a second conversion behind it.
        if (SilenceTrimmer.shouldTrim(trimSilence, durationMs)) {
            val directory = File(context.cacheDir, "transcription_send/${UUID.randomUUID()}").apply { mkdirs() }
            val started = SystemClock.elapsedRealtime()
            val trimmed = SilenceTrimmer.trim(source, File(directory, "send.$targetFormat"), targetFormat)
            if (trimmed != null) {
                return PreparedSendAudio(
                    trimmed,
                    targetFormat,
                    SystemClock.elapsedRealtime() - started,
                    directory,
                    keepDebugCopy
                )
            }
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }

        if (normalizedSource == targetFormat) {
            return PreparedSendAudio(source, targetFormat, 0L, null, keepTemporary = false)
        }

        val directory = File(context.cacheDir, "transcription_send/${UUID.randomUUID()}").apply { mkdirs() }
        val target = File(directory, "send.$targetFormat")
        val started = SystemClock.elapsedRealtime()
        try {
            val codecArguments = if (targetFormat == "mp3") {
                arrayOf("-codec:a", "libmp3lame", "-b:a", "192k")
            } else {
                arrayOf("-codec:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            }
            val arguments = arrayOf("-hide_banner", "-loglevel", "error", "-y", "-i", source.absolutePath, "-vn") +
                codecArguments + arrayOf(target.absolutePath)
            val session = FFmpegKit.executeWithArguments(arguments)
            check(ReturnCode.isSuccess(session.returnCode) && target.isFile && target.length() > 0L) {
                "Audio conversion failed: ${session.allLogsAsString.takeLast(500)}"
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            Log.i(
                "TranscriptionPerf",
                "conversion=${normalizedSource}_to_$targetFormat ms=$elapsed input_bytes=${source.length()} output_bytes=${target.length()}"
            )
            return PreparedSendAudio(target, targetFormat, elapsed, directory, keepDebugCopy)
        } catch (error: Throwable) {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
            throw error
        }
    }

    companion object {
        /**
         * The format this recording will be sent in. Shared with the trimming
         * pass, which re-encodes anyway and therefore has to land on the same
         * answer or the file would be converted twice.
         *
         * MAI 1.5 is the reason this is not simply "keep what we have": it
         * rejects everything but MP3, so that model overrides the source format
         * rather than following it.
         */
        fun wireFormat(modelId: String, sourceFormat: String, preference: SendAudioFormat): String {
            val normalizedSource = sourceFormat.lowercase()
            return when (preference) {
                SendAudioFormat.AUTO -> when {
                    ProviderModels.openRouterId(modelId) == ProviderModels.OPENROUTER_MAI_1_5 -> "mp3"
                    normalizedSource in setOf("m4a", "mp3") -> normalizedSource
                    else -> ProviderModels.automaticWireFormat(modelId)
                }
                SendAudioFormat.M4A -> "m4a"
                SendAudioFormat.MP3 -> "mp3"
            }
        }
    }
}
