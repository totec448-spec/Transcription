package com.example.transcription.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioImportFormatTest {
    @Test
    fun keepsMp3Direct() {
        val strategy = AudioImportFormat.detect("recording.mp3", "audio/mpeg", "audio/mpeg")
        assertEquals("mp3", strategy.wireFormat)
        assertFalse(strategy.transcode)
    }

    @Test
    fun keepsWhatsappOpusAsCompactOgg() {
        val strategy = AudioImportFormat.detect(
            "PTT-20260722-WA0001.opus",
            "audio/ogg; codecs=opus",
            "audio/opus"
        )
        assertEquals("ogg", strategy.wireFormat)
        assertEquals("ogg", strategy.extension)
        assertFalse(strategy.transcode)
    }

    @Test
    fun keepsM4aDirect() {
        val strategy = AudioImportFormat.detect("voice.m4a", "audio/mp4", "audio/mp4a-latm")
        assertEquals("m4a", strategy.wireFormat)
        assertFalse(strategy.transcode)
    }

    @Test
    fun convertsUncompressedOrUnknownAudioToM4a() {
        val strategy = AudioImportFormat.detect("studio-audio.wav", "audio/wav", "audio/raw")
        assertEquals("m4a", strategy.wireFormat)
        assertTrue(strategy.transcode)
    }
}
