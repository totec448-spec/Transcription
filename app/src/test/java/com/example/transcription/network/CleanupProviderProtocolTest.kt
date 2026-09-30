package com.example.transcription.network

import com.example.transcription.data.AppSettings
import com.example.transcription.data.stringMapFromJson
import com.example.transcription.data.withCleanupProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CleanupProviderProtocolTest {
    @Test fun keepsExactTagsSortsAvailableEndpointsAndParsesP50() {
        val providers = CleanupProviderProtocol.parse("""{"data":{"endpoints":[
            {"provider_name":"Host","tag":"host/turbo","status":0,"latency_last_30m":{"p50":0.25},"throughput_last_30m":{"p50":45}},
            {"provider_name":"Host","tag":"host/fp8","status":0,"latency_last_30m":null},
            {"provider_name":"Offline","tag":"offline","status":-2,"latency_last_30m":{"p50":0.1}},
            {"provider_name":"No slug","status":0}]}}""")
        assertEquals(listOf("host/turbo", "host/fp8", "offline"), providers.map { it.slug })
        assertEquals(0.25, providers[0].latencySeconds!!, 0.00001)
        assertEquals(45.0, providers[0].throughput!!, 0.00001)
        assertNull(providers[1].latencySeconds)
        assertFalse(providers[2].available)
    }

    @Test fun modelSpecificPinsSurviveSwitchAndPortableSerialization() {
        val settings = AppSettings().withCleanupProvider("model/a", "host/turbo").withCleanupProvider("model/b", "other")
        val restored = stringMapFromJson(JSONObject(settings.cleanupProviders).toString())
        assertEquals("host/turbo", restored["model/a"])
        val automatic = settings.withCleanupProvider("model/b", null)
        assertEquals("host/turbo", automatic.cleanupProviders["model/a"])
        assertFalse(automatic.cleanupProviders.containsKey("model/b"))
        assertTrue(stringMapFromJson("invalid").isEmpty())
    }
}
