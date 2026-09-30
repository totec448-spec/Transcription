# Transcription 1.03

- **AssemblyAI Universal-3.6 Pro Live** is available in the keyboard model browser. It uses the v3 streaming API with `speech_model=universal-3-6-pro`, mono 16 kHz PCM, supports 32 languages, and displays the published $0.45/hour rate. Existing model choices are preserved; this is a streaming model, so it is excluded from the app's batch picker.
- **Choose the Cleanup host.** Open Settings → Cleanup → model browser and tap the small arrow at the right of a model row. Pick an OpenRouter host or endpoint variant. The choice is remembered per model, survives restart and portable backup, and applies to both automatic Cleanup and Spoken Edit. A fixed host cannot fall back to another host. Automatic routing continues to prefer latency. The list loads on demand and shows reported P50 latency/throughput when available, with an explicit unavailable label when OpenRouter returns no statistics.
- **Tap the center microphone to skip automatic Cleanup.** Once transcription finishes and Cleanup begins, the keyboard and app let you keep the raw transcript immediately. Cleanup streams in the background; skipping closes that request and ignores any late answer. The audio and note remain available. Supported hosts stop processing/billing when the stream is cancelled; provider support determines whether remote work actually stops.
- **Reasoning follows the API catalog.** Models requiring reasoning automatically use their lowest reported effort when the saved choice is Off/Auto or unsupported. The picker only lists catalog-reported levels. Optional reasoning can be disabled explicitly; Auto preserves the model default instead of sending `none` accidentally.
- **Cleanup failures are visible.** The raw transcript survives failed requests, with the model/error shown in the app, keyboard and saved note. A removed model is requested by its selected ID instead of silently being replaced by another model. Successful diagnostic logs say whether the returned text changed.
- Spoken Edit keeps text that changed while its request was running rather than overwriting newer edits.

Android version **1.03**, versionCode **4**. Source, automated checks, signature verification and public asset verification are recorded in [WORKLOG_1.03.md](WORKLOG_1.03.md). No connected Android device was available for functional device testing. The user confirmed the existing MiMo cleanup path worked during diagnosis; that is separate from validation of this new APK.

Provider references checked 2026-09-30:

- [AssemblyAI Streaming API](https://www.assemblyai.com/docs/api-reference/streaming-api/streaming-api)
- [AssemblyAI pricing](https://www.assemblyai.com/pricing)
- [OpenRouter provider routing](https://openrouter.ai/docs/guides/routing/provider-selection)
- [OpenRouter reasoning](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens)
- [OpenRouter streaming and cancellation](https://openrouter.ai/docs/api_reference/streaming)
- [OpenRouter endpoint catalog](https://openrouter.ai/api/v1/models/xiaomi/mimo-v2.6-pro/endpoints)
