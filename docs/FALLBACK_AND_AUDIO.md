# Fallback, re-transcription, and audio capture

## Automatic fallback contract

The primary and fallback model are stored independently in `SettingsStore`. The UI keeps them distinct when the primary selection changes. For every completed recording, `RecordingService` performs this exact sequence:

1. Prepare one transient send view for the current primary model. Automatic mode uses the archived M4A directly except for MAI-Transcribe 1.5, which receives a temporary MP3.
2. If the primary throws for any HTTP, provider, parsing, timeout, or connection error, prepare the fallback model's send view from the same unchanged archive and call it once.
3. Persist the transcript and the model ID that actually succeeded.
4. If both fail, retain the audio and expose one condensed error naming both failed models. With **Save failed recordings** enabled, copy the completed M4A into the permanent audio archive and create a failed Notes entry.

Preflight failures that make an upload impossible — missing API key or a blocked Wi-Fi-only upload — stop before model selection and use the same optional failed-audio archive. A genuinely missing audio file cannot be archived. Selecting the same model in both slots cannot cause a duplicate request; the fallback helper refuses to call an identical ID twice.

This lives below every recording trigger. Finishing in the app, through the foreground notification, or through a homescreen widget therefore uses the same sequence.

If the fallback succeeds, the note stores the fallback model ID and a bounded copy of the primary error. The detail view shows that diagnostic above the editable transcript. OpenRouter provider errors delivered inside an HTTP 200 response are still recognized and sent directly to model fallback.

## Failed-audio archive

**Save failed recordings** is enabled by default under Advanced settings. The first failure for a new recording copies the finalized M4A to `files/audio_history/`, creates one History entry with the bounded failure message, and redirects Retry to that archived file. Further failed retries update the same entry instead of creating duplicates. A successful retry clears the failure marker and converts the same entry into a normal transcript; its ID, timestamp, duration, and audio file stay stable. Download and delete remain available while the transcript is missing.

When the setting is disabled, the previous transient behavior remains: the latest failed M4A is available to Retry until it is discarded or a new recording replaces it. Re-transcribing an already archived successful note never changes its existing transcript into a failed entry when a retry fails.

## History re-transcription

The History detail action starts `RecordingService.ACTION_RETRANSCRIBE` with the entry ID. The service resolves the private archived audio path itself and shows foreground processing state. The existing transcript remains visible until success.

On success, `HistoryRepository.updateTranscription` atomically replaces only the text and model ID. The timestamp, duration, entry ID, and audio file stay unchanged. On failure, both the old text and archived audio remain, and Retry repeats the primary/fallback sequence. Discarding that error state clears only transient UI state; it cannot delete archived audio.

## Capture controls

Advanced settings expose MediaRecorder's speech-relevant AAC controls:

- Sample rate: 8, 12, 16, 22.05, 24, 32, 44.1, or 48 kHz.
- AAC bitrate: 24, 32, 48, 64, 96, 128, or 192 kbps.
- Channels: mono or stereo.

The default is **Highest · compressed**: 48 kHz, 192 kbps AAC, mono. Balanced (24 kHz / 96 kbps) and Compact (16 kHz / 48 kbps) presets are available, while the individual controls still allow custom combinations. Settings apply only when the next recording begins; they never transcode existing History audio. This is the highest compressed preset the app records, archives, and normally uploads. The UI displays an approximate size per hour derived from bitrate. Device encoders may reject particular high-rate or stereo combinations; that error is surfaced before an existing archive is changed.

### Upload and import formats

M4A remains the permanent source for microphone recordings. Android records high-quality AAC directly and the file stays compact. Automatic mode streams those exact bytes for normal providers; MAI-Transcribe 1.5 receives a temporary MP3 because its OpenRouter route rejected the recorded M4A. Manual M4A and MP3 overrides remain available. Temporary send copies are deleted after final success/failure unless debug retention is enabled.

Imported MP3, M4A, OGG/Opus, AAC, and WebM are likewise archived and streamed byte-for-byte with the matching `input_audio.format`. This includes WhatsApp's observed `audio/ogg; codecs=opus` share intent. The app performs no speculative format retry and never converts one of these already compact sources. If a different or uncompressed Android-decodable source is chosen, a bounded native `MediaExtractor`/`MediaCodec`/`MediaMuxer` pipeline produces AAC/M4A without an uncompressed intermediate file. The result is stored once and used by primary, fallback, playback, export, and future re-transcription.

## Error presentation

The Home error card is capped at 180 dp and four message lines. This prevents long provider bodies or combined errors from taking over roughly half the screen while keeping Retry and Discard visible. History re-transcription errors use a separate three-line inline message beside the retry action.
