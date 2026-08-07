package com.example.transcription.network

import android.os.SystemClock
import android.util.Log
import com.example.transcription.data.PriceNormalizer
import com.example.transcription.data.LanguageCode
import com.example.transcription.data.ProviderModels
import com.example.transcription.data.TranscriptionModel
import com.example.transcription.data.TranscriptionProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

class OpenRouterClient {
    @Volatile private var rewriteConnection: HttpURLConnection? = null
    @Volatile private var processConnection: HttpURLConnection? = null
    fun fetchTranscriptionModels(apiKey: String = ""): List<TranscriptionModel> {
        val connection = (URL("https://openrouter.ai/api/v1/models?output_modalities=transcription")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val body = connection.readBody()
        if (connection.responseCode !in 200..299) throw OpenRouterException(connection.responseCode, errorMessage(body))
        val array = JSONObject(body).getJSONArray("data")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val promptPrice = item.optJSONObject("pricing")?.optString("prompt")?.toDoubleOrNull() ?: 0.0
                val parameters = item.optJSONArray("supported_parameters")
                add(
                    TranscriptionModel(
                        id = id,
                        name = item.optString("name", id),
                        description = item.optString("description"),
                        pricePerHourUsd = PriceNormalizer.pricePerHour(id, promptPrice),
                        priceNote = PriceNormalizer.note(id),
                        createdAt = item.optLong("created"),
                        provider = TranscriptionProvider.OPENROUTER_STT,
                        supportedParameters = buildSet {
                            if (parameters != null) for (parameterIndex in 0 until parameters.length()) {
                                add(parameters.optString(parameterIndex))
                            }
                        }
                    )
                )
            }
        }
    }

    fun fetchTextCatalog(apiKey: String = ""): OpenRouterTextCatalog {
        val connection = (URL("https://openrouter.ai/api/v1/models?output_modalities=text")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val body = connection.readBody()
        if (connection.responseCode !in 200..299) throw OpenRouterException(connection.responseCode, errorMessage(body))
        val array = JSONObject(body).getJSONArray("data")
        val multimodal = mutableListOf<TranscriptionModel>()
        val text = mutableListOf<TranscriptionModel>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val architecture = item.optJSONObject("architecture") ?: continue
            if (!architecture.optJSONArray("output_modalities").contains("text")) continue
            val inputModalities = architecture.optJSONArray("input_modalities")
            val pricing = item.optJSONObject("pricing")
            val prompt = pricing?.optString("prompt")?.toDoubleOrNull()
            val completion = pricing?.optString("completion")?.toDoubleOrNull()
            val audio = pricing?.optString("audio")?.toDoubleOrNull()
            val parameters = item.optJSONArray("supported_parameters")
            val reasoning = item.optJSONObject("reasoning")
            val id = item.getString("id")
            val commonParameters = buildSet {
                if (parameters != null) for (parameterIndex in 0 until parameters.length()) {
                    add(parameters.optString(parameterIndex))
                }
            }
            val reasoningEfforts = reasoning?.optJSONArray("supported_efforts").stringValues()
            val defaultReasoning = reasoning?.optString("default_effort")?.takeIf(String::isNotBlank)
            val reasoningMandatory = reasoning?.optBoolean("mandatory", false) == true
            if (inputModalities.contains("audio")) {
                multimodal += TranscriptionModel(
                    id = "openrouter-multimodal/$id",
                    name = item.optString("name", id),
                    description = item.optString("description"),
                    pricePerHourUsd = null,
                    priceNote = tokenPriceNote(prompt, completion, audio),
                    createdAt = item.optLong("created"),
                    supportedParameters = commonParameters,
                    provider = TranscriptionProvider.OPENROUTER_MULTIMODAL,
                    inputPricePerMillionUsd = prompt?.times(1_000_000),
                    outputPricePerMillionUsd = completion?.times(1_000_000),
                    audioPricePerMillionUsd = audio?.times(1_000_000),
                    reasoningEfforts = reasoningEfforts,
                    defaultReasoningEffort = defaultReasoning,
                    reasoningMandatory = reasoningMandatory
                )
            }
            if (inputModalities.contains("text")) {
                text += TranscriptionModel(
                    id = "openrouter-text/$id",
                    name = item.optString("name", id),
                    description = item.optString("description"),
                    pricePerHourUsd = null,
                    priceNote = tokenPriceNote(prompt, completion, null),
                    createdAt = item.optLong("created"),
                    supportedParameters = commonParameters,
                    provider = TranscriptionProvider.OPENROUTER_TEXT,
                    inputPricePerMillionUsd = prompt?.times(1_000_000),
                    outputPricePerMillionUsd = completion?.times(1_000_000),
                    reasoningEfforts = reasoningEfforts,
                    defaultReasoningEffort = defaultReasoning,
                    reasoningMandatory = reasoningMandatory
                )
            }
        }
        return OpenRouterTextCatalog(
            multimodalAudio = multimodal.sortedByDescending { it.createdAt },
            text = text.sortedByDescending { it.createdAt }
        )
    }

    fun fetchMultimodalAudioModels(apiKey: String = ""): List<TranscriptionModel> =
        fetchTextCatalog(apiKey).multimodalAudio

    fun rewriteText(
        apiKey: String,
        model: String,
        systemPrompt: String,
        originalText: String,
        spokenInstruction: String,
        reasoningEffort: String?,
        includeReasoning: Boolean,
        includeTemperature: Boolean,
        timeoutSeconds: Int
    ): ProcessedText {
        require(apiKey.isNotBlank()) { "Add your OpenRouter API key in Settings, then retry." }
        require(originalText.isNotBlank()) { "There is no text to edit." }
        require(spokenInstruction.isNotBlank()) { "No edit instruction was understood." }
        val payload = JSONObject()
            .put("model", ProviderModels.openRouterId(model))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(
                        JSONObject().put("role", "user").put(
                            "content",
                            CleanupTextProtocol.userMessage(originalText, spokenInstruction)
                        )
                    )
            )
            .applyCleanupProviderRouting(model)
        if (includeReasoning) {
            payload.put(
                "reasoning",
                JSONObject().put("effort", reasoningEffort ?: "none").put("exclude", true)
            )
        }
        if (includeTemperature) payload.put("temperature", 0)
        payload.put("usage", JSONObject().put("include", true))
        val connection = (URL("https://openrouter.ai/api/v1/chat/completions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = minOf(timeoutSeconds, 20) * 1_000
            readTimeout = timeoutSeconds * 1_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-OpenRouter-Title", "Scribe V2 Cleanup")
        }
        rewriteConnection = connection
        try {
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(payload.toString()) }
            val status = connection.responseCode
            val body = connection.readBody(status)
            if (status !in 200..299) throw OpenRouterException(status, errorMessage(body))
            val json = JSONObject(body)
            json.opt("error")?.let { throw OpenRouterException(status, errorMessage(body)) }
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()
            require(content.isNotBlank()) { "OpenRouter returned no replacement text." }
            val usage = json.optJSONObject("usage")
            return ProcessedText(
                text = CleanupTextProtocol.sanitizeReplacement(content),
                costUsd = usage?.optDouble("cost")?.takeIf { !it.isNaN() },
                totalTokens = usage?.optLong("total_tokens")?.takeIf { it > 0L }
            )
        } finally {
            if (rewriteConnection === connection) rewriteConnection = null
            connection.disconnect()
        }
    }

    fun cancelRewrite() {
        rewriteConnection?.disconnect()
        rewriteConnection = null
    }

    /**
     * The automatic pass over a finished transcript. Unlike [rewriteText] there
     * is no spoken instruction, so the transcript is the whole user message and
     * the mode's system prompt carries the entire behavior.
     *
     * It keeps its own connection: a base pass runs while a recording finishes,
     * a rewrite runs during a voice edit, and cancelling one must not kill the
     * other.
     */
    fun processText(
        apiKey: String,
        model: String,
        systemPrompt: String,
        text: String,
        reasoningEffort: String?,
        includeReasoning: Boolean,
        includeTemperature: Boolean,
        timeoutSeconds: Int
    ): ProcessedText {
        require(apiKey.isNotBlank()) { "Add your OpenRouter API key in Settings, then retry." }
        require(text.isNotBlank()) { "There is no text to process." }
        val payload = JSONObject()
            .put("model", ProviderModels.openRouterId(model))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", text))
            )
            .applyCleanupProviderRouting(model)
        if (includeReasoning) {
            payload.put(
                "reasoning",
                JSONObject().put("effort", reasoningEffort ?: "none").put("exclude", true)
            )
        }
        if (includeTemperature) payload.put("temperature", 0)
        // OpenRouter only itemizes what a call cost when asked, and the cleanup
        // pass is billed separately from the transcription it follows, so the
        // figure has to come back with the text or it cannot be attributed.
        payload.put("usage", JSONObject().put("include", true))
        val connection = (URL("https://openrouter.ai/api/v1/chat/completions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = minOf(timeoutSeconds, 20) * 1_000
            readTimeout = timeoutSeconds * 1_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-OpenRouter-Title", "Transcription base cleanup")
        }
        processConnection = connection
        try {
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(payload.toString()) }
            val status = connection.responseCode
            val body = connection.readBody(status)
            if (status !in 200..299) throw OpenRouterException(status, errorMessage(body))
            val json = JSONObject(body)
            json.opt("error")?.let { throw OpenRouterException(status, errorMessage(body)) }
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()
            require(content.isNotBlank()) { "OpenRouter returned no processed text." }
            val usage = json.optJSONObject("usage")
            return ProcessedText(
                text = CleanupTextProtocol.sanitizeReplacement(content),
                costUsd = usage?.optDouble("cost")?.takeIf { !it.isNaN() },
                totalTokens = usage?.optLong("total_tokens")?.takeIf { it > 0L }
            )
        } finally {
            if (processConnection === connection) processConnection = null
            connection.disconnect()
        }
    }

    fun cancelProcess() {
        processConnection?.disconnect()
        processConnection = null
    }

    private fun tokenPriceNote(prompt: Double?, completion: Double?, audio: Double?): String =
        buildString {
            prompt?.let { append("US$ ${"%.2f".format(it * 1_000_000)} / M input") }
            completion?.let {
                if (isNotEmpty()) append(" · ")
                append("US$ ${"%.2f".format(it * 1_000_000)} / M output")
            }
            audio?.let {
                if (isNotEmpty()) append(" · ")
                append("US$ ${"%.2f".format(it * 1_000_000)} / M audio")
            }
        }

    fun transcribe(
        apiKey: String,
        model: String,
        audioFile: File,
        language: String,
        audioFormat: String = "m4a"
    ): OpenRouterTranscription {
        val format = AudioInputFormat.normalize(audioFormat)
        val requestBytes = TranscriptionRequestWriter.contentLength(model, audioFile, language, format)
        val connection = (URL("https://openrouter.ai/api/v1/audio/transcriptions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setFixedLengthStreamingMode(requestBytes)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-OpenRouter-Title", "Transcription for Android")
        }

        val started = SystemClock.elapsedRealtime()
        BufferedOutputStream(connection.outputStream, NETWORK_BUFFER_BYTES).use { output ->
            TranscriptionRequestWriter.write(output, model, audioFile, language, format)
        }
        val uploadWrittenAt = SystemClock.elapsedRealtime()

        val status = connection.responseCode
        val headersAt = SystemClock.elapsedRealtime()
        val body = connection.readBody(status)
        val finished = SystemClock.elapsedRealtime()
        Log.i(
            PERF_TAG,
            "model=${model.substringAfterLast('/')} format=$format bytes=${audioFile.length()} " +
                "json_bytes=$requestBytes write_ms=${uploadWrittenAt - started} " +
                "headers_wait_ms=${headersAt - uploadWrittenAt} body_ms=${finished - headersAt} status=$status"
        )
        if (status !in 200..299) throw OpenRouterException(status, errorMessage(body))
        return TranscriptionResponseParser.parse(body, status).copy(
            generationId = connection.getHeaderField("X-Generation-Id")
        )
    }

    private fun HttpURLConnection.readBody(status: Int = responseCode): String {
        val stream = if (status in 200..299) inputStream else errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun errorMessage(body: String): String = runCatching {
        val json = JSONObject(body)
        val error = json.opt("error")
        when (error) {
            is JSONObject -> error.optString("message", body)
            is String -> error
            else -> body
        }
    }.getOrDefault(body.ifBlank { "Unknown OpenRouter error" })

    private companion object {
        const val PERF_TAG = "TranscriptionPerf"
        const val NETWORK_BUFFER_BYTES = 256 * 1024
    }
}

data class OpenRouterTextCatalog(
    val multimodalAudio: List<TranscriptionModel>,
    val text: List<TranscriptionModel>
)

/**
 * Per-request provider pinning for text cleanup.
 *
 * OpenRouter can serve open-weight DeepSeek models through several inference
 * companies. Cleanup is the deliberate exception: a DeepSeek model must run on
 * DeepSeek's own endpoint, even when another endpoint would be cheaper or more
 * available. Other model families retain OpenRouter's normal routing.
 */
internal object CleanupProviderRouting {
    private const val DEEPSEEK_PROVIDER = "deepseek"

    fun onlyProvider(modelId: String): String? {
        val wireId = ProviderModels.openRouterId(modelId).substringBefore(':')
        return DEEPSEEK_PROVIDER.takeIf {
            wireId.startsWith("$DEEPSEEK_PROVIDER/", ignoreCase = true)
        }
    }
}

private fun JSONObject.applyCleanupProviderRouting(modelId: String): JSONObject = apply {
    CleanupProviderRouting.onlyProvider(modelId)?.let { provider ->
        put("provider", JSONObject().put("only", JSONArray().put(provider)))
    }
}

internal object CleanupTextProtocol {
    fun userMessage(originalText: String, spokenInstruction: String) =
        "ORIGINAL TEXT\n$originalText\nEND ORIGINAL TEXT\n\n" +
            "SPOKEN EDIT INSTRUCTION\n$spokenInstruction\nEND SPOKEN EDIT INSTRUCTION"

    fun sanitizeReplacement(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return trimmed
        return trimmed
            .removePrefix("```")
            .substringAfter('\n', "")
            .removeSuffix("```")
            .trim()
    }
}

private fun JSONArray?.contains(value: String): Boolean {
    if (this == null) return false
    for (index in 0 until length()) if (optString(index) == value) return true
    return false
}

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

internal object TranscriptionResponseParser {
    fun parse(body: String, httpStatus: Int): OpenRouterTranscription {
        val json = JSONObject(body)
        json.openRouterError()?.let { throw it }
        val text = when {
            json.has("text") -> json.optString("text")
            json.has("transcript") -> json.optString("transcript")
            json.optJSONArray("segments") != null -> segmentsToText(json.getJSONArray("segments"))
            else -> throw OpenRouterException(httpStatus, "OpenRouter returned no transcript")
        }
        val usage = json.optJSONObject("usage")
        return OpenRouterTranscription(
            text = text,
            costUsd = usage?.optDouble("cost")?.takeIf { !it.isNaN() },
            billedSeconds = usage?.optDouble("seconds")?.takeIf { !it.isNaN() },
            totalTokens = usage?.optLong("total_tokens")?.takeIf { it > 0L }
        )
    }

    private fun JSONObject.openRouterError(): OpenRouterException? {
        val error = opt("error") ?: return null
        return when (error) {
            is JSONObject -> OpenRouterException(
                error.optInt("code", 500).takeIf { it in 400..599 } ?: 500,
                error.optString("message").ifBlank { error.toString() }
            )
            is String -> OpenRouterException(500, error)
            else -> OpenRouterException(500, error.toString())
        }
    }

    private fun segmentsToText(segments: JSONArray): String = buildString {
        for (index in 0 until segments.length()) {
            val segment = segments.getJSONObject(index)
            val speaker = segment.optString("speaker")
            val text = segment.optString("text")
            if (speaker.isNotBlank()) append("$speaker: ")
            append(text.trim())
            if (index < segments.length() - 1) append('\n')
        }
    }
}

data class OpenRouterTranscription(
    val text: String,
    val costUsd: Double? = null,
    val billedSeconds: Double? = null,
    val totalTokens: Long? = null,
    val generationId: String? = null
)

/** The result of one text pass, with what it cost attached. */
data class ProcessedText(
    val text: String,
    val costUsd: Double? = null,
    val totalTokens: Long? = null
)

/** Writes the current OpenRouter STT JSON shape without holding the Base64 audio in memory. */
internal object TranscriptionRequestWriter {
    fun contentLength(
        model: String,
        audioFile: File,
        language: String,
        audioFormat: String = "m4a"
    ): Long {
        val parts = requestParts(model, language, AudioInputFormat.normalize(audioFormat))
        val base64Bytes = ((audioFile.length() + 2L) / 3L) * 4L
        return parts.prefix.size.toLong() + base64Bytes + parts.suffix.size.toLong()
    }

    fun write(
        output: OutputStream,
        model: String,
        audioFile: File,
        language: String,
        audioFormat: String = "m4a"
    ) {
        val parts = requestParts(model, language, AudioInputFormat.normalize(audioFormat))
        output.write(parts.prefix)
        Base64.getEncoder().wrap(NonClosingOutputStream(output)).use { encoded ->
            audioFile.inputStream().use { input -> input.copyTo(encoded, 256 * 1024) }
        }
        output.write(parts.suffix)
    }

    private fun requestParts(model: String, language: String, audioFormat: String): RequestParts {
        val prefix = "{\"model\":${jsonString(model)},\"input_audio\":{\"data\":\""
        val suffix = buildString {
            append("\",\"format\":${jsonString(audioFormat)}}")
            val normalizedLanguage = LanguageCode.normalize(language)
            if (normalizedLanguage.isNotEmpty() && normalizedLanguage != "auto") {
                append(",\"language\":${jsonString(normalizedLanguage)}")
            }
            append("}")
        }
        return RequestParts(
            prefix.toByteArray(StandardCharsets.UTF_8),
            suffix.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private data class RequestParts(val prefix: ByteArray, val suffix: ByteArray)

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }
}

internal object AudioInputFormat {
    private val supportedCompressedFormats = setOf("m4a", "mp3", "ogg", "aac", "webm")

    fun normalize(value: String): String {
        val normalized = value.lowercase().trim()
        require(normalized in supportedCompressedFormats) { "Unsupported compressed audio format: $value" }
        return normalized
    }
}

class OpenRouterException(val statusCode: Int, override val message: String) : Exception(message)
