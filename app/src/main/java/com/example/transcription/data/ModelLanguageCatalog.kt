package com.example.transcription.data

/** Provider-published language profiles for current STT model families. Null means OpenRouter ISO-639-1. */
object ModelLanguageCatalog {
    private val qwen3AsrFlash = codes("zh en fr de ru it es pt ja ko ar")
    private val parakeetV3 = codes("bg hr cs da nl en et fi fr de el hu it lv lt mt pl pt ro sk sl es sv ru uk")
    private val voxtral = codes("en zh hi es ar fr pt ru de ja ko it nl")
    private val whisper = codes(
        "en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt jv su"
    )
    private val euOfficial = codes("bg hr cs da nl en et fi fr de el hu ga it lv lt mt pl pt ro sk sl es sv")

    fun supportedCodes(modelId: String): Set<String>? {
        val id = modelId.lowercase()
        return when {
            "qwen3-asr-flash" in id -> qwen3AsrFlash
            "parakeet-tdt-0.6b-v3" in id -> parakeetV3
            "voxtral" in id && "transcribe" in id -> voxtral
            "whisper" in id -> whisper
            "gpt-4o" in id && "transcribe" in id -> whisper
            "mai-transcribe" in id -> euOfficial
            else -> null
        }
    }

    fun supports(modelId: String, language: String): Boolean {
        val code = LanguageCode.normalize(language)
        return code == "auto" || supportedCodes(modelId)?.contains(code) != false
    }

    private fun codes(value: String) = value.split(' ').filter(String::isNotBlank).toSet()
}
