package com.example.transcription.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.transcription.AppContainer
import com.example.transcription.data.ProviderModels
import com.example.transcription.data.RecordingPhase
import com.example.transcription.data.TranscriptionModel
import com.example.transcription.data.TranscriptionProvider
import com.example.transcription.network.TranscriptionProviderRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class CleanupPhase { IDLE, RECORDING, TRANSCRIBING, REWRITING, SUCCESS, ERROR }

data class CleanupState(
    val ownerId: String? = null,
    val phase: CleanupPhase = CleanupPhase.IDLE,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val message: String? = null
) {
    val busy: Boolean
        get() = phase == CleanupPhase.RECORDING ||
            phase == CleanupPhase.TRANSCRIBING ||
            phase == CleanupPhase.REWRITING
}

/**
 * Shared cleanup pipeline for Home, History and the voice IME.
 *
 * This intentionally owns no notification, History entry, clipboard write, or
 * global RecordingState. It records only the short edit instruction, sends that
 * audio through BatchTranscriptionEngine, makes exactly one text-model request,
 * returns the replacement to the caller, and removes the temporary audio.
 */
object CleanupCoordinator {
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CleanupState())
    val state: StateFlow<CleanupState> = _state

    private var recorder: MediaRecorder? = null
    private val micLevel = MicLevelNormalizer(TICK_MS.toInt())
    private val micPeaks = MicPeakSource(TICK_MS.toInt())
    private var micProfileKey = MicPeakSource.KEY_RECORDER
    private var meteringActive = false
    private var applicationContext: Context? = null
    private var instructionFile: File? = null
    private var originalText = ""
    private var startedAt = 0L
    @Volatile private var generation = 0L
    private var resultCallback: ((String) -> Unit)? = null
    @Volatile private var activeEngine: BatchTranscriptionEngine? = null

    @Synchronized
    fun start(
        context: Context,
        ownerId: String,
        text: String,
        onResult: (String) -> Unit
    ): Boolean {
        if (_state.value.busy) return false
        if (text.isBlank()) {
            _state.value = CleanupState(ownerId, CleanupPhase.ERROR, message = "There is no text to edit.")
            return false
        }
        if (RecordingController.state.value.phase in setOf(
                RecordingPhase.RECORDING,
                RecordingPhase.PAUSED,
                RecordingPhase.PROCESSING
            )
        ) {
            _state.value = CleanupState(ownerId, CleanupPhase.ERROR, message = "Finish the current recording first.")
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = CleanupState(ownerId, CleanupPhase.ERROR, message = "Microphone permission is required.")
            return false
        }

        AppContainer.initialize(context)
        if (AppContainer.secrets.readApiKey().isBlank()) {
            _state.value = CleanupState(ownerId, CleanupPhase.ERROR,
                message = "Add an OpenRouter API key in Settings to use cleanup.")
            return false
        }
        val app = context.applicationContext
        val file = File(app.cacheDir, "cleanup/instruction-${java.util.UUID.randomUUID()}.m4a").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val settings = AppContainer.settings.settings.value
        val activeRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(app)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        val prepared = runCatching {
            activeRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(settings.audioChannels)
                setAudioEncodingBitRate(settings.audioBitRateBps)
                setAudioSamplingRate(settings.audioSampleRateHz)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }
        if (prepared.isFailure) {
            activeRecorder.release()
            _state.value = CleanupState(
                ownerId,
                CleanupPhase.ERROR,
                message = "Cleanup recording could not start: ${prepared.exceptionOrNull()?.message ?: "unknown error"}"
            )
            return false
        }

        generation += 1
        applicationContext = app
        instructionFile = file
        originalText = text
        resultCallback = onResult
        recorder = activeRecorder
        micPeaks.start(app, AppContainer.micProfiles)
        micProfileKey = micPeaks.profileKey()
        micLevel.reset(AppContainer.micProfiles.load(micProfileKey))
        meteringActive = true
        startedAt = SystemClock.elapsedRealtime()
        _state.value = CleanupState(ownerId, CleanupPhase.RECORDING)
        tick(generation)
        return true
    }

    @Synchronized
    fun finish(ownerId: String) {
        val current = _state.value
        if (current.ownerId != ownerId || current.phase != CleanupPhase.RECORDING) return
        handler.removeCallbacksAndMessages(null)
        val duration = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(250L)
        val stopped = runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        releaseMeter()
        if (stopped.isFailure || instructionFile?.length() == 0L) {
            fail(ownerId, "No cleanup instruction was recorded.")
            return
        }
        _state.value = current.copy(
            phase = CleanupPhase.TRANSCRIBING,
            elapsedMs = duration,
            amplitude = 0f,
            message = "Understanding your edit…"
        )
        runCleanup(generation, ownerId, duration)
    }

    @Synchronized
    fun cancel(ownerId: String) {
        if (_state.value.ownerId != ownerId) return
        generation += 1
        handler.removeCallbacksAndMessages(null)
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        releaseMeter()
        activeEngine?.abandon(cancelNetwork = true)
        activeEngine = null
        AppContainer.openRouter.cancelRewrite()
        instructionFile?.delete()
        clearRequest()
        _state.value = CleanupState()
    }

    fun isOwnerActive(ownerId: String) = _state.value.ownerId == ownerId && _state.value.busy

    private fun runCleanup(requestGeneration: Long, ownerId: String, durationMs: Long) {
        val context = applicationContext ?: return fail(ownerId, "Cleanup context was lost.")
        val file = instructionFile ?: return fail(ownerId, "Cleanup audio was lost.")
        val sourceText = originalText
        Thread cleanupThread@{
            runCatching {
                val settings = AppContainer.settings.settings.value
                if (settings.wifiOnly && !context.isOnWifi()) {
                    error("Wi-Fi only is enabled. Connect to Wi-Fi or disable it, then retry.")
                }
                val transcriptionModels = AppContainer.models.models.value
                val resolved = AppContainer.resolvedTranscriptionModels(settings.selectedModel)
                val primary = batchModel(resolved.first, transcriptionModels)
                val fallback = if (resolved.second.isBlank()) "" else {
                    batchModel(resolved.second, transcriptionModels, primary)
                }
                val engine = BatchTranscriptionEngine(context, TranscriptionProviderRegistry(AppContainer.secrets))
                synchronized(this) {
                    check(requestGeneration == generation) { "Cleanup cancelled." }
                    activeEngine = engine
                }
                val instruction = engine.transcribe(
                    file = file,
                    audioFormat = "m4a",
                    durationMs = durationMs,
                    primaryModel = primary,
                    fallbackModel = fallback,
                    language = settings.language,
                    sendAudioFormat = settings.sendAudioFormat,
                    multimodalPrompt = settings.multimodalPrompt,
                    multimodalReasoningEffort = settings.multimodalReasoningEffort,
                    providerTimeoutSeconds = settings.providerTimeoutSeconds,
                    keepDebugSendCopies = settings.keepDebugSendCopies,
                    catalogModels = transcriptionModels,
                    applyBaseCleanup = false
                ).text.trim()
                synchronized(this) {
                    if (activeEngine === engine) activeEngine = null
                }
                check(requestGeneration == generation) { "Cleanup cancelled." }
                if (!CleanupInstructionPolicy.shouldRequestRewrite(
                        instruction,
                        settings.cleanupMinimumInstructionWords
                    )
                ) {
                    handler.post {
                        if (requestGeneration == generation) {
                            fail(ownerId, if (instruction.isBlank()) "No edit instruction was recognized. Please try again."
                                else "Instruction too short. Lower Minimum instruction words in Settings or use a longer instruction.")
                        }
                    }
                    return@cleanupThread
                }
                handler.post {
                    if (requestGeneration == generation) {
                        _state.value = _state.value.copy(
                            phase = CleanupPhase.REWRITING,
                            message = "Applying only the requested changes…"
                        )
                    }
                }

                val cleanupModel = AppContainer.models.cleanupModels.value
                    .firstOrNull { it.id == settings.cleanupModel }
                    ?: AppContainer.models.cleanupModels.value.first()
                check(requestGeneration == generation) { "Cleanup cancelled." }
                val reasoning = CleanupReasoningResolver.resolve(cleanupModel, settings.cleanupReasoningEffort)
                AppContainer.openRouter.rewriteText(
                    apiKey = AppContainer.secrets.readApiKey(),
                    model = cleanupModel.id,
                    systemPrompt = settings.cleanupPrompt,
                    originalText = sourceText,
                    spokenInstruction = instruction,
                    reasoningEffort = reasoning.effort,
                    includeReasoning = reasoning.include,
                    includeTemperature = "temperature" in cleanupModel.supportedParameters,
                    timeoutSeconds = settings.providerTimeoutSeconds
                ).also {
                    // Counted the same way the automatic pass is: a spoken edit
                    // is a billed text request, and leaving it out of the totals
                    // made the running cost quietly understate what was spent.
                    AppContainer.usage.record(
                        provider = com.example.transcription.data.TranscriptionProvider.OPENROUTER_TEXT,
                        seconds = 0.0,
                        costUsd = it.costUsd,
                        estimated = it.costUsd == null
                    )
                }.text
            }.onSuccess { replacement ->
                handler.post {
                    if (requestGeneration != generation) return@post
                    instructionFile?.delete()
                    activeEngine = null
                    resultCallback?.invoke(replacement)
                    clearRequest()
                    _state.value = CleanupState(ownerId, CleanupPhase.SUCCESS, message = "Text updated")
                    handler.postDelayed({
                        if (_state.value.ownerId == ownerId && _state.value.phase == CleanupPhase.SUCCESS) {
                            _state.value = CleanupState()
                        }
                    }, 1_200L)
                }
            }.onFailure { error ->
                handler.post {
                    if (requestGeneration == generation) fail(ownerId, error.message ?: "Cleanup failed.")
                }
            }
        }.start()
    }

    private fun batchModel(
        requested: String,
        models: List<TranscriptionModel>,
        differentFrom: String? = null
    ): String {
        val validRequested = models.firstOrNull {
            it.id == requested &&
                !it.streaming &&
                it.provider != TranscriptionProvider.OPENROUTER_TEXT
        }?.id
        return validRequested?.takeIf { it != differentFrom }
            ?: models.firstOrNull {
                !it.streaming &&
                    it.id != differentFrom &&
                    it.provider != TranscriptionProvider.OPENROUTER_TEXT
            }?.id
            ?: ProviderModels.OPENROUTER_WHISPER_LARGE_V3
    }

    @Synchronized
    private fun fail(ownerId: String, message: String) {
        handler.removeCallbacksAndMessages(null)
        recorder?.release()
        recorder = null
        releaseMeter()
        activeEngine?.abandon(cancelNetwork = true)
        activeEngine = null
        AppContainer.openRouter.cancelRewrite()
        instructionFile?.delete()
        clearRequest()
        _state.value = CleanupState(ownerId, CleanupPhase.ERROR, message = message)
    }

    private fun clearRequest() {
        applicationContext = null
        instructionFile = null
        originalText = ""
        resultCallback = null
    }

    private fun tick(requestGeneration: Long) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (requestGeneration != generation || _state.value.phase != CleanupPhase.RECORDING) return
                val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val raw = micPeaks.read(amplitude)
                // Same two scales, same rule as the main recorder: a device that
                // falls back to PCM is measured against the PCM profile.
                if (micPeaks.profileKey() != micProfileKey) {
                    micProfileKey = micPeaks.profileKey()
                    micLevel.reset(AppContainer.micProfiles.load(micProfileKey))
                }
                // The fixed 48 dB window this replaced was squared on top, so a
                // quiet microphone produced a halo that never moved and a loud
                // one produced a halo that was never not at maximum.
                _state.value = _state.value.copy(
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    amplitude = micLevel.accept(raw)
                )
                handler.postDelayed(this, TICK_MS)
            }
        }, TICK_MS)
    }

    /**
     * Stops metering and keeps what this instruction taught us about the
     * microphone. Safe to call from every path that releases the recorder.
     */
    private fun releaseMeter() {
        if (!meteringActive) return
        meteringActive = false
        micPeaks.stop()
        micLevel.observed()?.let { AppContainer.micProfiles.record(micProfileKey, it) }
    }

    private fun Context.isOnWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** How often the halo around the cleanup microphone is refreshed. */
    private const val TICK_MS = 120L
}
