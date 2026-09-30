package com.example.transcription.network

import org.junit.Assert.*
import org.junit.Test

class CleanupStreamProtocolTest {
    private fun read(stream: String) = CleanupStreamProtocol.read(stream.reader().buffered(), "Empty cleanup")

    @Test fun truncatedConnectionNeverAppliesPartialReplacement() {
        val failure = runCatching { read("data: {\"choices\":[{\"delta\":{\"content\":\"Partial\"}}]}\n\n") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("before the response completed"))
    }

    @Test fun midstreamHttp200ErrorIsNotSuccess() {
        val failure = runCatching { read("data: {\"error\":{\"message\":\"Provider disconnected\"}}\n\ndata: [DONE]\n\n") }
        assertTrue(failure.exceptionOrNull() is OpenRouterException)
        assertEquals("Provider disconnected", failure.exceptionOrNull()!!.message)
    }

    @Test fun outputLimitDoesNotReplaceWholeFieldWithTruncatedText() {
        assertTrue(runCatching { read("data: {\"choices\":[{\"delta\":{\"content\":\"Truncated\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n") }.isFailure)
    }

    @Test fun multilineEventsAreParsedAndReasoningIsIgnored() {
        val result = read("event: message\nid: 1\n: keepalive\ndata: {\"choices\":[\ndata: {\"delta\":{\"content\":\"Fertig\",\"reasoning_details\":[{\"text\":\"secret\"}]}}]}\n\ndata: [DONE]\n\n")
        assertEquals("Fertig", result.text)
    }
}
