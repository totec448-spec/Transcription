package com.example.transcription

import com.example.transcription.data.AudioCaptureOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCaptureOptionsTest {
    @Test
    fun acceptsSupportedCaptureValues() {
        assertEquals(48_000, AudioCaptureOptions.sampleRate(48_000))
        assertEquals(192_000, AudioCaptureOptions.bitRate(192_000))
        assertEquals(2, AudioCaptureOptions.channelCount(2))
    }

    @Test
    fun invalidPersistedValuesReturnVoiceDefaults() {
        assertEquals(48_000, AudioCaptureOptions.sampleRate(-1))
        assertEquals(192_000, AudioCaptureOptions.bitRate(0))
        assertEquals(1, AudioCaptureOptions.channelCount(8))
    }

    @Test
    fun highestCompressedIsTheDefaultQualityPreset() {
        val preset = AudioCaptureOptions.highestCompressed
        assertEquals("Highest · compressed", preset.label)
        assertEquals(48_000, preset.sampleRateHz)
        assertEquals(192_000, preset.bitRateBps)
        assertEquals(1, preset.channels)
        assertEquals(preset, AudioCaptureOptions.matchingPreset(48_000, 192_000, 1))
    }
}
