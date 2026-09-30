package com.example.transcription.data

import org.json.JSONObject

internal fun stringMapFromJson(value: String?): Map<String, String> = runCatching {
    val json = JSONObject(value ?: "{}")
    json.keys().asSequence().mapNotNull { key ->
        (json.opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)?.let { key to it }
    }.toMap()
}.getOrDefault(emptyMap())

internal fun AppSettings.withCleanupProvider(modelId: String, slug: String?): AppSettings = copy(
    cleanupModel = modelId,
    cleanupProviders = slug?.trim()?.takeIf(String::isNotBlank)?.let { cleanupProviders + (modelId to it) }
        ?: (cleanupProviders - modelId)
)
