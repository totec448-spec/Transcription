package com.example.transcription.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CachedModelCatalog(
    val transcription: List<TranscriptionModel> = emptyList(),
    val cleanup: List<TranscriptionModel> = emptyList()
)

/**
 * Last-known-good catalog storage.
 *
 * A provider outage must never erase models obtained successfully earlier.
 * AtomicFile also prevents a process kill during refresh from leaving a partial
 * JSON file that would make every OpenRouter model disappear on the next start.
 */
class ModelCatalogCache(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "model_catalog.json"))

    @Synchronized
    fun read(): CachedModelCatalog = runCatching {
        val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
        CachedModelCatalog(
            transcription = root.optJSONArray("transcription").models().map { model ->
                if (root.optInt("version", 1) < 2 && model.provider == TranscriptionProvider.OPENROUTER_STT) {
                    model.copy(pricePerHourUsd = null, priceNote = "Refresh catalog for corrected pricing")
                } else model
            },
            cleanup = root.optJSONArray("cleanup").models()
        )
    }.getOrDefault(CachedModelCatalog())

    @Synchronized
    fun write(value: CachedModelCatalog) {
        val data = JSONObject()
            .put("version", 2)
            .put("transcription", value.transcription.toJson())
            .put("cleanup", value.cleanup.toJson())
            .toString()
            .toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(data)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun List<TranscriptionModel>.toJson() = JSONArray().apply {
        forEach { model ->
            put(JSONObject().apply {
                put("id", model.id)
                put("name", model.name)
                put("description", model.description)
                model.pricePerHourUsd?.let { put("pricePerHourUsd", it) }
                put("priceNote", model.priceNote)
                put("createdAt", model.createdAt)
                put("supportedParameters", JSONArray(model.supportedParameters.toList()))
                put("provider", model.provider.name)
                model.inputPricePerMillionUsd?.let { put("inputPricePerMillionUsd", it) }
                model.outputPricePerMillionUsd?.let { put("outputPricePerMillionUsd", it) }
                model.audioPricePerMillionUsd?.let { put("audioPricePerMillionUsd", it) }
                put("reasoningEfforts", JSONArray(model.reasoningEfforts))
                put("defaultReasoningEffort", model.defaultReasoningEffort ?: "")
                put("reasoningMandatory", model.reasoningMandatory)
                put("streaming", model.streaming)
            })
        }
    }

    private fun JSONArray?.models(): List<TranscriptionModel> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    TranscriptionModel(
                        id = id,
                        name = item.optString("name", id),
                        description = item.optString("description"),
                        pricePerHourUsd = item.nullableDouble("pricePerHourUsd"),
                        priceNote = item.optString("priceNote"),
                        createdAt = item.optLong("createdAt"),
                        supportedParameters = item.optJSONArray("supportedParameters").strings().toSet(),
                        provider = runCatching {
                            TranscriptionProvider.valueOf(item.optString("provider"))
                        }.getOrDefault(ProviderModels.provider(id)),
                        inputPricePerMillionUsd = item.nullableDouble("inputPricePerMillionUsd"),
                        outputPricePerMillionUsd = item.nullableDouble("outputPricePerMillionUsd"),
                        audioPricePerMillionUsd = item.nullableDouble("audioPricePerMillionUsd"),
                        reasoningEfforts = item.optJSONArray("reasoningEfforts").strings(),
                        defaultReasoningEffort = item.optString("defaultReasoningEffort").takeIf(String::isNotBlank),
                        reasoningMandatory = item.optBoolean("reasoningMandatory"),
                        streaming = item.optBoolean("streaming")
                    )
                )
            }
        }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.nullableDouble(key: String) =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
}
