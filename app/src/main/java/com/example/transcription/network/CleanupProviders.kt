package com.example.transcription.network

import com.example.transcription.data.ProviderModels
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CleanupProviderEndpoint(
    val slug: String,
    val name: String,
    val latencySeconds: Double?,
    val throughput: Double?,
    val available: Boolean
)

/** Small on-demand cache: never fetch hundreds of endpoint lists with the model catalog. */
class CleanupProviderRepository {
    private data class Cached(val fetchedAt: Long, val endpoints: List<CleanupProviderEndpoint>)
    private val cache = mutableMapOf<String, Cached>()

    fun fetch(modelId: String, apiKey: String, force: Boolean = false): List<CleanupProviderEndpoint> {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[modelId]?.takeIf { !force && now - it.fetchedAt < 300_000L }?.let { return it.endpoints } }
        val id = ProviderModels.openRouterId(modelId)
        val connection = URL("https://openrouter.ai/api/v1/models/$id/endpoints").openConnection() as HttpURLConnection
        val endpoints = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "Providers unavailable (HTTP $status). Tap Retry." }
            CleanupProviderProtocol.parse(body)
        } finally { connection.disconnect() }
        synchronized(cache) { cache[modelId] = Cached(now, endpoints) }
        return endpoints
    }
}

internal object CleanupProviderProtocol {
    fun parse(body: String): List<CleanupProviderEndpoint> {
        val endpoints = JSONObject(body).getJSONObject("data").getJSONArray("endpoints")
        return buildList {
            for (index in 0 until endpoints.length()) {
                val item = endpoints.getJSONObject(index)
                // The tag is the exact routing slug, including endpoint variants.
                // Display names cannot reliably be converted into slugs.
                val slug = item.optString("tag").takeIf(String::isNotBlank) ?: continue
                add(CleanupProviderEndpoint(
                    slug, item.optString("provider_name", slug),
                    percentile(item, "latency_last_30m"), percentile(item, "throughput_last_30m"),
                    item.optInt("status", 0) == 0
                ))
            }
        }.distinctBy { it.slug }.sortedWith(
            compareByDescending<CleanupProviderEndpoint> { it.available }
                .thenBy { it.latencySeconds ?: Double.MAX_VALUE }.thenBy { it.name }
        )
    }

    private fun percentile(item: JSONObject, key: String): Double? {
        val value = item.optJSONObject(key)?.optDouble("p50") ?: item.optDouble(key)
        return value.takeIf { it.isFinite() && it >= 0 }
    }
}
