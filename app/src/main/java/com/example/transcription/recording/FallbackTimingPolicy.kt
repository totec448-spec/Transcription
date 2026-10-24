package com.example.transcription.recording

internal object FallbackTimingPolicy {
    fun attemptPrimary(hasFallback: Boolean, requested: Boolean, delaySeconds: Int) =
        !hasFallback || (!requested && delaySeconds > 0)

    fun requestSeconds(primary: Boolean, hasFallback: Boolean, delaySeconds: Int, providerSeconds: Int) =
        if (primary && hasFallback) delaySeconds.coerceIn(1, 20) else providerSeconds.coerceAtLeast(1)
}
