# Model language profiles

OpenRouter documents an optional ISO-639-1 `language` field on the transcription endpoint, but its July 2026 transcription catalog does not provide structured per-model language arrays and does not list `language` in `supported_parameters` for the models tested on device. The app therefore uses model-family profiles from provider documentation rather than disabling the selector.

- Qwen3 ASR Flash: 11 languages — Chinese, English, French, German, Russian, Italian, Spanish, Portuguese, Japanese, Korean, and Arabic. Source: [Qwen3 ASR Flash announcement](https://qwen.ai/blog?id=qwen3-asr-flash).
- NVIDIA Parakeet TDT 0.6B v3: 25 European languages. Source: [NVIDIA Riva ASR model overview](https://docs.nvidia.com/ace-for-games/Riva-asr/2.0/developer-pack.html).
- Mistral Voxtral transcription: 13 languages — English, Chinese, Hindi, Spanish, Arabic, French, Portuguese, Russian, German, Japanese, Korean, Italian, and Dutch. Source: [Mistral speech transcription docs](https://docs.mistral.ai/studio-api/audio/speech_to_text).
- OpenAI Whisper and GPT-4o transcription families: the multilingual Whisper language set, normalized to ISO-639-1 codes. Source: [OpenAI Whisper repository](https://github.com/openai/whisper).
- Microsoft MAI Transcribe: official EU language set, matching the current OpenRouter catalog description.

Unknown future transcription IDs stay usable with the full Android ISO-639-1 list. Profiles are matched by stable model-family fragments so dated/snapshot variants inherit the same list. `ModelLanguageCatalogTest` protects current profile sizes and primary/fallback validation.
