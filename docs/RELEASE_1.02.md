# Transcription 1.02: Fix MAI-Transcribe and prices

- Automatic audio preparation now sends MP3 to MAI-Transcribe 2, including when selected as the fallback. The archived recording remains unchanged.
- OpenRouter STT retries short HTTP 429/503 failures before switching models, respects Retry-After and cancellation, and distinguishes rate limiting from insufficient credits.
- Correct MAI-Transcribe 2 hourly billing and second-based models. Whisper endpoint prices are normalized by provider because DeepInfra bills seconds and Groq bills hours. Unknown units remain unavailable instead of silently assuming minutes.
- Invalidate old cached STT prices while retaining models; request a corrected catalog. Preserve actual usage.cost, and repair duration estimates when token counts accompany duration-billed responses.

Android versionCode 3. Live provider/device testing and the signed release APK require the existing release environment.

Pricing references checked 2026-09-05:
- https://openrouter.ai/api/v1/models?output_modalities=transcription
- https://openrouter.ai/openai/whisper-large-v3-turbo
- https://openrouter.ai/api/v1/models/openai/whisper-large-v3-turbo/endpoints
- https://openrouter.ai/collections/speech-to-text-models
- https://openrouter.ai/docs/api/reference/errors-and-debugging
