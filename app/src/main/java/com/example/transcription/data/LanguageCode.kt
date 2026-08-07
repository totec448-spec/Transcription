package com.example.transcription.data

/** Normalizes UI and legacy values to OpenRouter's documented ISO-639-1 language field. */
object LanguageCode {
    fun normalize(raw: String): String {
        val value = raw.trim().lowercase().replace('_', '-')
        return when (value) {
            "", "auto", "automatic", "automatisch", "detect" -> "auto"
            "german", "deutsch" -> "de"
            "english", "englisch" -> "en"
            "spanish", "español", "spanisch" -> "es"
            "french", "français", "französisch" -> "fr"
            "italian", "italiano", "italienisch" -> "it"
            "portuguese", "português", "portugiesisch" -> "pt"
            else -> value.substringBefore('-').takeIf { it.matches(Regex("^[a-z]{2}$")) } ?: "auto"
        }
    }
}
