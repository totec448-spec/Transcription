package com.example.transcription.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class TranscriptionRequestWriterTest {
    @Test
    fun writesCurrentOpenRouterJsonAndStreamsM4a() {
        val audio = byteArrayOf(0, 1, 2, 3, 127, -1)
        val file = File.createTempFile("openrouter-request", ".m4a").apply { writeBytes(audio) }
        try {
            val output = ByteArrayOutputStream()
            TranscriptionRequestWriter.write(output, "microsoft/mai-transcribe-1.5", file, "DE")
            val json = output.toString(Charsets.UTF_8.name())

            assertEquals(
                output.size().toLong(),
                TranscriptionRequestWriter.contentLength("microsoft/mai-transcribe-1.5", file, "DE")
            )
            assertTrue(json.contains("\"model\":\"microsoft/mai-transcribe-1.5\""))
            assertTrue(json.contains("\"format\":\"m4a\""))
            assertTrue(json.contains("\"language\":\"de\""))
            val encoded = Regex("\\\"data\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]
            assertArrayEquals(audio, Base64.getDecoder().decode(encoded))
        } finally {
            file.delete()
        }
    }

    @Test
    fun omitsAutoLanguageAndUnsupportedPromptFields() {
        val file = File.createTempFile("openrouter-request", ".m4a").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        try {
            val output = ByteArrayOutputStream()
            TranscriptionRequestWriter.write(output, "qwen/qwen3-asr-flash-2026-02-10", file, "auto")
            val json = output.toString(Charsets.UTF_8.name())

            assertEquals(
                output.size().toLong(),
                TranscriptionRequestWriter.contentLength("qwen/qwen3-asr-flash-2026-02-10", file, "auto")
            )
            assertFalse(json.contains("\"language\""))
            assertFalse(json.contains("\"prompt\""))
            assertFalse(json.contains("multipart"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun writesTheActualCompressedImportFormat() {
        val file = File.createTempFile("openrouter-request", ".ogg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        try {
            val output = ByteArrayOutputStream()
            TranscriptionRequestWriter.write(output, "qwen/qwen3-asr-flash-2026-02-10", file, "auto", "ogg")
            val json = output.toString(Charsets.UTF_8.name())

            assertTrue(json.contains("\"format\":\"ogg\""))
            assertEquals(
                output.size().toLong(),
                TranscriptionRequestWriter.contentLength("qwen/qwen3-asr-flash-2026-02-10", file, "auto", "ogg")
            )
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUncompressedUploadFormat() {
        val file = File.createTempFile("openrouter-request", ".raw").apply { writeBytes(byteArrayOf(1)) }
        try {
            TranscriptionRequestWriter.write(ByteArrayOutputStream(), "model", file, "auto", "wav")
        } finally {
            file.delete()
        }
    }

}
