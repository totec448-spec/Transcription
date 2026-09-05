package com.example.transcription.network

import android.os.SystemClock
import android.util.Log
import com.example.transcription.data.ApiKeyProvider
import com.example.transcription.data.ProviderModels
import com.example.transcription.data.SecretStore
import com.example.transcription.data.TranscriptionProvider
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class ProviderTranscriptionRequest(
    val modelId: String,
    val audioFile: File,
    val audioFormat: String,
    val durationMs: Long,
    val language: String,
    val multimodalPrompt: String,
    val includeMultimodalReasoning: Boolean,
    val multimodalReasoningEffort: String?,
    val includeMultimodalTemperature: Boolean,
    val timeoutSeconds: Int
)

/**
 * How far along the one blocking HTTP call is.
 *
 * A minute-long recording spends most of its wait inside a single
 * `call.execute()`, which from the outside looks identical whether the file is
 * still climbing the uplink or the model is already thinking about it. These
 * come from OkHttp's own call events, so they cost nothing to produce and
 * describe what is actually happening rather than guessing at it.
 */
enum class ProviderStage { UPLOADING, WAITING, RECEIVING }

data class ProviderTranscriptionResult(
    val text: String,
    val provider: TranscriptionProvider,
    val modelId: String,
    val costUsd: Double? = null,
    val costEstimated: Boolean = false,
    val billedSeconds: Double? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null
)

/**
 * Single provider boundary used by recording, imports, retry, fallback, and the batch IME path.
 * Provider implementations only own authentication, request shape, accepted formats,
 * response parsing, and provider-specific usage.
 */
class TranscriptionProviderRegistry(
    private val secrets: SecretStore,
    private val baseClient: OkHttpClient = OkHttpClient()
) {
    private val activeCall = AtomicReference<Call?>()
    private val cancellationGeneration = AtomicLong()

    /**
     * Set by the caller that wants to narrate the wait. Invoked on the calling
     * thread, since transcription runs the request synchronously.
     */
    var onStage: ((ProviderStage) -> Unit)? = null

    fun transcribe(request: ProviderTranscriptionRequest): ProviderTranscriptionResult {
        val provider = ProviderModels.provider(request.modelId)
        val keyProvider = when (provider) {
            TranscriptionProvider.ELEVENLABS -> ApiKeyProvider.ELEVENLABS
            TranscriptionProvider.ASSEMBLYAI -> ApiKeyProvider.ASSEMBLYAI
            else -> ApiKeyProvider.OPENROUTER
        }
        val apiKey = secrets.readApiKey(keyProvider)
        require(apiKey.isNotBlank()) { "Add your ${keyProvider.label} API key in Settings, then retry." }
        return when (provider) {
            TranscriptionProvider.OPENROUTER_STT -> openRouterStt(apiKey, request)
            TranscriptionProvider.OPENROUTER_MULTIMODAL -> openRouterMultimodal(apiKey, request)
            TranscriptionProvider.OPENROUTER_TEXT ->
                error("A text-only cleanup model cannot be used for audio transcription.")
            TranscriptionProvider.ELEVENLABS -> elevenLabs(apiKey, request)
            TranscriptionProvider.ASSEMBLYAI -> assemblyAi(apiKey, request)
        }
    }

    fun cancelActive() {
        cancellationGeneration.incrementAndGet()
        activeCall.getAndSet(null)?.cancel()
    }

    private fun openRouterStt(apiKey: String, value: ProviderTranscriptionRequest): ProviderTranscriptionResult {
        val generation = cancellationGeneration.get()
        val model = ProviderModels.openRouterId(value.modelId)
        val requestBody = object : RequestBody() {
            override fun contentType() = JSON
            override fun contentLength() =
                TranscriptionRequestWriter.contentLength(model, value.audioFile, value.language, value.audioFormat)

            override fun writeTo(sink: okio.BufferedSink) {
                TranscriptionRequestWriter.write(sink.outputStream(), model, value.audioFile, value.language, value.audioFormat)
            }
        }
        val response = TranscriptionRetry.run(
            timeoutMs = value.timeoutSeconds * 1000L,
            cancelled = { cancellationGeneration.get() != generation }
        ) { remainingSeconds -> execute(
            Request.Builder()
                .url("https://openrouter.ai/api/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .header("X-OpenRouter-Title", "Transcription for Android")
                .post(requestBody)
                .build(),
            remainingSeconds,
            carriesAudio = true,
            returnsTranscript = true
        ) }
        val parsed = TranscriptionResponseParser.parse(response.body, response.status)
        return ProviderTranscriptionResult(
            text = parsed.text,
            provider = TranscriptionProvider.OPENROUTER_STT,
            modelId = model,
            costUsd = parsed.costUsd,
            billedSeconds = parsed.billedSeconds,
            promptTokens = parsed.totalTokens
        )
    }

    private fun openRouterMultimodal(apiKey: String, value: ProviderTranscriptionRequest): ProviderTranscriptionResult {
        val model = ProviderModels.openRouterId(value.modelId)
        val body = MultimodalAudioRequestBody(
            model = model,
            prompt = value.multimodalPrompt,
            audioFile = value.audioFile,
            format = value.audioFormat,
            includeReasoning = value.includeMultimodalReasoning,
            reasoningEffort = value.multimodalReasoningEffort,
            includeTemperature = value.includeMultimodalTemperature
        )
        val response = execute(
            Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("X-OpenRouter-Title", "Transcription for Android")
                .post(body)
                .build(),
            value.timeoutSeconds,
            carriesAudio = true,
            returnsTranscript = true
        )
        val json = response.json()
        openRouterError(json, response.status)
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
        require(text.isNotBlank()) { "OpenRouter returned no transcript." }
        val usage = json.optJSONObject("usage")
        return ProviderTranscriptionResult(
            text = text,
            provider = TranscriptionProvider.OPENROUTER_MULTIMODAL,
            modelId = model,
            costUsd = usage?.nullableDouble("cost"),
            promptTokens = usage?.nullableLong("prompt_tokens"),
            completionTokens = usage?.nullableLong("completion_tokens")
        )
    }

    private fun elevenLabs(apiKey: String, value: ProviderTranscriptionRequest): ProviderTranscriptionResult {
        val providerModelId = ProviderModels.elevenLabsId(value.modelId)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model_id", providerModelId)
            .addFormDataPart("tag_audio_events", "false")
            .addFormDataPart("diarize", "false")
            .apply {
                val language = elevenLabsLanguage(value.language)
                if (language != null) addFormDataPart("language_code", language)
            }
            .addFormDataPart(
                "file",
                value.audioFile.name,
                value.audioFile.asRequestBody(mediaType(value.audioFormat))
            )
            .build()
        val response = execute(
            Request.Builder()
                .url("https://api.elevenlabs.io/v1/speech-to-text")
                .header("xi-api-key", apiKey)
                .post(body)
                .build(),
            value.timeoutSeconds,
            carriesAudio = true,
            returnsTranscript = true
        )
        val json = response.json()
        val text = json.optString("text").trim()
        require(text.isNotBlank()) { "ElevenLabs returned no transcript." }
        return ProviderTranscriptionResult(
            text = text,
            provider = TranscriptionProvider.ELEVENLABS,
            modelId = value.modelId,
            costUsd = value.durationMs / 3_600_000.0 * ELEVENLABS_PRICE_PER_HOUR,
            costEstimated = true,
            billedSeconds = value.durationMs / 1_000.0
        )
    }

    private fun assemblyAi(apiKey: String, value: ProviderTranscriptionRequest): ProviderTranscriptionResult {
        val providerModelId = ProviderModels.assemblyAiId(value.modelId)
        // The recording crosses the wire exactly once, here. What follows is
        // job control against a URL, so nothing after this line uploads audio
        // however many requests it takes.
        val upload = execute(
            Request.Builder()
                .url("https://api.assemblyai.com/v2/upload")
                .header("Authorization", apiKey)
                .post(value.audioFile.asRequestBody(OCTET_STREAM))
                .build(),
            value.timeoutSeconds,
            carriesAudio = true
        ).json().optString("upload_url")
        require(upload.isNotBlank()) { "AssemblyAI did not return an upload URL." }

        val payload = AssemblyAiRequestProtocol.payload(
            audioUrl = upload,
            providerModelId = providerModelId,
            language = value.language
        )

        val submitted = execute(
            Request.Builder()
                .url("https://api.assemblyai.com/v2/transcript")
                .header("Authorization", apiKey)
                .post(payload.toString().toRequestBody(JSON))
                .build(),
            value.timeoutSeconds
        ).json()
        val id = submitted.optString("id")
        require(id.isNotBlank()) { submitted.optString("error", "AssemblyAI did not create a transcript job.") }

        val deadline = SystemClock.elapsedRealtime() + value.timeoutSeconds * 1_000L
        var result = submitted
        // Backed off rather than fixed. A flat 350 ms meant a ten-second job
        // cost thirty round trips, each one a TLS request and a JSON parse, to
        // learn "still processing" thirty times. Growing the gap keeps the
        // first answer prompt — a short recording that finishes quickly is
        // still noticed within half a second — while a longer wait settles
        // into roughly one request per second instead of three.
        var pollDelayMs = FIRST_POLL_DELAY_MS
        while (result.optString("status") in UNFINISHED_STATUSES) {
            if (SystemClock.elapsedRealtime() >= deadline) throw IOException("AssemblyAI timed out.")
            Thread.sleep(pollDelayMs)
            pollDelayMs = (pollDelayMs * POLL_BACKOFF / 100).coerceAtMost(MAX_POLL_DELAY_MS)
            result = execute(
                Request.Builder()
                    .url("https://api.assemblyai.com/v2/transcript/$id")
                    .header("Authorization", apiKey)
                    .get()
                    .build(),
                maxOf(5, ((deadline - SystemClock.elapsedRealtime()) / 1_000L).toInt())
            ).json()
        }
        if (result.optString("status") != "completed") {
            error(result.optString("error", "AssemblyAI transcription failed."))
        }
        val text = result.optString("text").trim()
        require(text.isNotBlank()) { "AssemblyAI returned no transcript." }
        return ProviderTranscriptionResult(
            text = text,
            provider = TranscriptionProvider.ASSEMBLYAI,
            modelId = value.modelId,
            costUsd = value.durationMs / 3_600_000.0 * ASSEMBLYAI_PRICE_PER_HOUR,
            costEstimated = true,
            billedSeconds = value.durationMs / 1_000.0
        )
    }

    private fun execute(
        request: Request,
        timeoutSeconds: Int,
        carriesAudio: Boolean = false,
        returnsTranscript: Boolean = false
    ): HttpResult {
        val client = baseClient.newBuilder()
            .connectTimeout(minOf(timeoutSeconds, 20).toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .apply { stageListener(carriesAudio, returnsTranscript)?.let { eventListener(it) } }
            .build()
        val call = client.newCall(request)
        activeCall.set(call)
        val started = SystemClock.elapsedRealtime()
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.i("TranscriptionPerf", "provider_host=${request.url.host} total_ms=${SystemClock.elapsedRealtime() - started} status=${response.code}")
                if (!response.isSuccessful) throw ProviderHttpException(
                    response.code, response.header("Retry-After"), providerError(body, response.code)
                )
                return HttpResult(response.code, body)
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    /**
     * Narrates a call only when it is one the person waiting can recognize.
     *
     * A transcription is not always one request. AssemblyAI's is three kinds:
     * the audio upload, a job submission, and a status poll. Every one of them
     * used to report the same stages, and the job submission is a few hundred
     * bytes of JSON — so it flashed "Uploading to AssemblyAI" and "Receiving
     * the transcript" past within a single frame, in the middle of the wait,
     * making one upload of the recording look like several round trips. The
     * upload response has the same problem in the other direction: it hands
     * back a storage URL, not a transcript, so announcing "receiving" there
     * claimed the answer had arrived when the job had not yet been created.
     *
     * So the two facts that matter are named per request rather than guessed
     * from whether a body exists: whether this call is carrying the recording,
     * and whether its response is the transcript. Job control announces
     * nothing at all — the wait it belongs to is already named on screen, and
     * replacing that label for a few milliseconds and putting it back is the
     * flicker itself.
     */
    private fun stageListener(carriesAudio: Boolean, returnsTranscript: Boolean): EventListener? {
        val stage = onStage ?: return null
        if (!carriesAudio && !returnsTranscript) return null
        return object : EventListener() {
            override fun callStart(call: Call) {
                if (!carriesAudio) stage(ProviderStage.WAITING)
            }

            override fun requestBodyStart(call: Call) {
                if (carriesAudio) stage(ProviderStage.UPLOADING)
            }

            override fun requestBodyEnd(call: Call, byteCount: Long) {
                if (carriesAudio) stage(ProviderStage.WAITING)
            }

            override fun responseHeadersEnd(call: Call, response: Response) {
                if (returnsTranscript) stage(ProviderStage.RECEIVING)
            }
        }
    }

    private fun providerError(body: String, status: Int): String = runCatching {
        val json = JSONObject(body)
        val error = json.opt("error")
        val message = when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> json.optString("detail").ifBlank { json.optString("message") }
        }
        val provider = (error as? JSONObject)?.optJSONObject("metadata")
            ?.optString("provider_name")?.takeIf { it.isNotBlank() }
        val reason = when (status) {
            429 -> "Rate limited or provider capacity exhausted"
            402 -> "Insufficient API credits"
            503 -> "Provider temporarily unavailable"
            else -> null
        }
        listOfNotNull("HTTP $status", provider, reason, message.ifBlank { body.take(500) })
            .joinToString(": ")
    }.getOrDefault("HTTP $status: ${body.take(500)}")

    private fun openRouterError(json: JSONObject, status: Int) {
        val value = json.opt("error") ?: return
        val message = if (value is JSONObject) {
            val base = value.optString("message").ifBlank { "Provider returned an error" }
            val metadata = value.optJSONObject("metadata")
            val provider = metadata?.optString("provider_name")?.takeIf(String::isNotBlank)
            val raw = metadata?.optString("raw")
                ?.takeIf { it.isNotBlank() && !it.equals(base, ignoreCase = true) }
                ?.take(280)
            buildString {
                append(base)
                provider?.let { append(" · "); append(it) }
                raw?.let { append(": "); append(it) }
            }
        } else value.toString()
        throw IOException("OpenRouter $status: $message")
    }

    private fun elevenLabsLanguage(value: String): String? = when (value.lowercase()) {
        "", "auto" -> null
        "en" -> "eng"
        "de" -> "deu"
        "fr" -> "fra"
        "es" -> "spa"
        "it" -> "ita"
        "pt" -> "por"
        else -> null
    }

    private fun mediaType(format: String) = when (format.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> "audio/mp4"
    }.toMediaType()

    private data class HttpResult(val status: Int, val body: String) {
        fun json() = JSONObject(body)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val ELEVENLABS_PRICE_PER_HOUR = 0.22
        const val ASSEMBLYAI_PRICE_PER_HOUR = 0.21

        /** Job states that mean "ask again", built once rather than per poll. */
        val UNFINISHED_STATUSES = setOf("queued", "processing")

        /** Short enough that a quickly finished job is not made to wait for us. */
        const val FIRST_POLL_DELAY_MS = 350L

        /** Roughly one request per second once the job is clearly not instant. */
        const val MAX_POLL_DELAY_MS = 1_000L
        const val POLL_BACKOFF = 160L
    }
}

/**
 * AssemblyAI's newest Universal models reject legacy formatting switches even
 * when those switches are set to false. Keep submission deliberately minimal:
 * model routing plus an optional explicit language only.
 */
internal object AssemblyAiRequestProtocol {
    data class Submission(
        val audioUrl: String,
        val providerModelId: String,
        val languageCode: String?
    )

    fun submission(audioUrl: String, providerModelId: String, language: String) =
        Submission(
            audioUrl = audioUrl,
            providerModelId = providerModelId,
            languageCode = language.takeIf { it != "auto" && it.isNotBlank() }
        )

    fun payload(audioUrl: String, providerModelId: String, language: String): JSONObject {
        val value = submission(audioUrl, providerModelId, language)
        return JSONObject()
            .put("audio_url", value.audioUrl)
            .put("speech_models", org.json.JSONArray().put(value.providerModelId))
            .apply {
                value.languageCode?.let { put("language_code", it) }
            }
    }
}

private class MultimodalAudioRequestBody(
    private val model: String,
    private val prompt: String,
    private val audioFile: File,
    private val format: String,
    private val includeReasoning: Boolean,
    private val reasoningEffort: String?,
    private val includeTemperature: Boolean
) : RequestBody() {
    private val prefix = JSONObject.quote(model).let { quotedModel ->
        "{\"model\":$quotedModel,\"messages\":[{\"role\":\"user\",\"content\":[{" +
            "\"type\":\"text\",\"text\":${JSONObject.quote(prompt)}},{\"type\":\"input_audio\"," +
            "\"input_audio\":{\"data\":\""
    }.toByteArray()
    private val suffix = buildString {
        append("\",\"format\":${JSONObject.quote(format)}}]}]")
        if (includeReasoning) {
            append(",\"reasoning\":{")
            reasoningEffort?.let { append("\"effort\":${JSONObject.quote(it)},") }
            append("\"exclude\":true}")
        }
        if (includeTemperature) append(",\"temperature\":0.7")
        append("}")
    }.toByteArray()

    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength() = prefix.size + ((audioFile.length() + 2L) / 3L) * 4L + suffix.size

    override fun writeTo(sink: okio.BufferedSink) {
        sink.write(prefix)
        Base64.getEncoder().wrap(NonClosingOutputStream(sink.outputStream())).use { encoded ->
            audioFile.inputStream().use { input -> input.copyTo(encoded, 256 * 1024) }
        }
        sink.write(suffix)
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }
}

private fun JSONObject.nullableDouble(name: String) =
    if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

private fun JSONObject.nullableLong(name: String) =
    if (has(name) && !isNull(name)) optLong(name).takeIf { it > 0L } else null
