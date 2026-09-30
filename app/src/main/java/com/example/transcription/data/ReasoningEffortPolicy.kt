package com.example.transcription.data

/** UI and wire requests share the same catalog allowlist. */
internal object ReasoningEffortPolicy {
    val gatewayEfforts = listOf("minimal", "low", "medium", "high", "xhigh", "max")

    fun lowest(model: TranscriptionModel): String? = model.reasoningEfforts
        .filter { it != "none" }
        .minByOrNull { gatewayEfforts.indexOf(it).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }

    fun normalize(model: TranscriptionModel, value: String): String {
        val effort = value.lowercase()
        if (model.reasoningMandatory && effort in setOf("auto", "none")) return lowest(model) ?: "auto"
        if (effort in setOf("auto", "none")) return effort
        return if (effort in model.reasoningEfforts) effort else lowest(model) ?: "auto"
    }

    fun options(model: TranscriptionModel): List<String> = buildList {
        if (!model.reasoningMandatory || model.reasoningEfforts.isEmpty()) add("auto")
        if (!model.reasoningMandatory) add("none")
        addAll(model.reasoningEfforts.filter { it != "none" }.distinct())
    }
}
