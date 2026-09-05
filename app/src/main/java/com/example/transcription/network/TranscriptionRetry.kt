package com.example.transcription.network

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class ProviderHttpException(
    val status: Int,
    val retryAfter: String?,
    message: String
) : IOException(message)

/** Only explicit rejected requests are retried, never ambiguous connection failures. */
internal object TranscriptionRetry {
    fun delayMs(status: Int, retryAfter: String?, retry: Int, nowMs: Long): Long? {
        if (status !in setOf(429, 503) || retry >= 2) return null
        val seconds = retryAfter?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        val dateDelay = if (seconds == null && !retryAfter.isNullOrBlank()) runCatching {
            ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - nowMs
        }.getOrNull() else null
        val requested = seconds?.let { (it * 1000).toLong() } ?: dateDelay ?: (1000L shl retry)
        // A long provider cooldown should use the configured fallback immediately.
        if (requested > 5_000) return null
        return requested.coerceAtLeast(250)
    }

    fun <T> run(
        timeoutMs: Long,
        cancelled: () -> Boolean,
        now: () -> Long = System::currentTimeMillis,
        wait: (Long) -> Unit = Thread::sleep,
        attempt: (Int) -> T
    ): T {
        val started = now()
        var retry = 0
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("Transcription cancelled")
            val remaining = timeoutMs - (now() - started)
            if (remaining <= 0) throw IOException("Transcription timed out")
            try {
                return attempt(((remaining + 999) / 1000).toInt().coerceAtLeast(1))
            } catch (error: ProviderHttpException) {
                val delay = delayMs(error.status, error.retryAfter, retry++, now()) ?: throw error
                if (now() - started + delay >= timeoutMs) throw error
                var left = delay
                while (left > 0) {
                    if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("Transcription cancelled")
                    val slice = minOf(left, 100L)
                    wait(slice)
                    left -= slice
                }
            }
        }
    }
}
