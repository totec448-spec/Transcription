package com.example.transcription.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CleanupRequestTest {
    private fun withServer(block: (OpenRouterClient, MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(OpenRouterClient(server.url("/chat/completions").toString()), server)
        }
    }
    private fun answer() = MockResponse().setBody("""{"choices":[{"message":{"content":"Clean text"}}],"usage":{"cost":0.001}}""")

    @Test fun automaticCleanupPinsExactEndpointWithoutFallbackAndDisablesOptionalReasoning() = withServer { client, server ->
        server.enqueue(answer())
        val result = client.processText("test-key", "openrouter-text/xiaomi/mimo-v2.6-pro", "Clean", "uh text",
            "none", true, false, 5, "deepinfra/fp8")
        assertEquals("Clean text", result.text)
        assertEquals(0.001, result.costUsd!!, 0.000001)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("xiaomi/mimo-v2.6-pro", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("deepinfra/fp8", body.getJSONObject("provider").getJSONArray("only").getString(0))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertFalse(body.getJSONObject("reasoning").getBoolean("enabled"))
        assertFalse(body.has("temperature"))
    }

    @Test fun spokenEditUsesSamePinAndAutoDoesNotSendNone() = withServer { client, server ->
        server.enqueue(answer())
        client.rewriteText("test-key", "openrouter-text/test/model", "Edit", "Original", "Shorter",
            null, true, false, 5, "host/turbo")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("host/turbo", body.getJSONObject("provider").getJSONArray("only").getString(0))
        assertFalse(body.getJSONObject("reasoning").has("effort"))
        assertFalse(body.getJSONObject("reasoning").has("enabled"))
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("SPOKEN EDIT INSTRUCTION"))
    }

    @Test fun unpinnedCleanupStillSortsByLatency() = withServer { client, server ->
        server.enqueue(answer())
        client.processText("test-key", "test/model", "Clean", "text", "minimal", true, false, 5)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertEquals("latency", body.getJSONObject("provider").getString("sort"))
        assertFalse(body.getJSONObject("provider").has("only"))
        assertEquals("minimal", body.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun httpFailureKeepsProviderErrorForUserFeedback() = withServer { client, server ->
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"Pinned provider unavailable"}}"""))
        val failure = runCatching { client.processText("test-key", "test/model", "Clean", "text", null, false, false, 5) }.exceptionOrNull()
        assertTrue(failure is OpenRouterException)
        assertTrue(failure!!.message!!.contains("Pinned provider unavailable"))
    }

    @Test fun cancelledBeforeConnectionMakesNoRequest() = withServer { client, server ->
        client.cancelProcess()
        assertTrue(runCatching { client.processText("test-key", "test/model", "Clean", "text", null, false, false, 5) }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun nullOrEmptyFencedContentNeverBecomesReplacementText() = withServer { client, server ->
        for (content in listOf("null", "\"```text\\n```\"")) {
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":$content}}]}"))
            assertTrue(runCatching { client.processText("test-key", "test/model", "Clean", "text", null, false, false, 5) }.isFailure)
        }
    }

    @Test fun streamedCleanupReturnsOnlyContentAndFinalUsage() = withServer { client, server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ": OPENROUTER PROCESSING\n\n" +
            "data: {\"choices\":[{\"delta\":{\"reasoning\":\"hidden\",\"content\":\"Clean \"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"text\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"cost\":0.002,\"total_tokens\":21}}\n\n" +
            "data: [DONE]\n\n"))
        val result = client.processText("test-key", "test/model", "Clean", "text", null, false, false, 5)
        assertEquals("Clean text", result.text)
        assertEquals(0.002, result.costUsd!!, 0.000001)
        assertEquals(21L, result.totalTokens)
    }
}
