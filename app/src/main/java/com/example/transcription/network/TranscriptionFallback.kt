package com.example.transcription.network

internal data class ModelTranscription<T>(
    val value: T,
    val model: String,
    val usedFallback: Boolean,
    val primaryErrorMessage: String? = null
)

/** Runs the fallback only after the primary model throws and records which model succeeded. */
internal object TranscriptionFallback {
    fun <T> run(primaryModel: String, fallbackModel: String, attempt: (String) -> T): ModelTranscription<T> {
        return try {
            ModelTranscription(attempt(primaryModel), primaryModel, usedFallback = false)
        } catch (primaryError: Exception) {
            if (fallbackModel.isBlank() || fallbackModel == primaryModel) throw primaryError
            try {
                ModelTranscription(
                    attempt(fallbackModel),
                    fallbackModel,
                    usedFallback = true,
                    primaryErrorMessage = primaryError.shortMessage()
                )
            } catch (fallbackError: Exception) {
                throw TranscriptionFallbackException(primaryModel, primaryError, fallbackModel, fallbackError)
            }
        }
    }
}

internal class TranscriptionFallbackException(
    primaryModel: String,
    primaryError: Exception,
    fallbackModel: String,
    fallbackError: Exception
) : Exception(
    "Both transcription models failed. " +
        "${primaryModel.substringAfterLast('/')}: ${primaryError.shortMessage()}; " +
        "${fallbackModel.substringAfterLast('/')}: ${fallbackError.shortMessage()}"
)

private fun Exception.shortMessage(): String = message
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.take(240)
    .orEmpty()
    .ifBlank { this::class.java.simpleName }
