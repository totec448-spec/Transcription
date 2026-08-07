# Architecture

## Runtime flow

1. `MainActivity` requests microphone/notification permissions and dispatches an explicit action to `RecordingService`.
2. `RecordingService` records AAC/M4A into `files/latest/recording.m4a` while publishing time and normalized microphone levels through `RecordingController`. See [Microphone levels](#microphone-levels) for where those levels come from.
3. The same service owns pause, resume, discard, and finish, keeping app, notification, and widget actions on one state machine.
4. Finish closes the recorder and changes the state to `PROCESSING`.
5. `BatchTranscriptionEngine` runs the current primary once, then the distinct fallback once after any provider/HTTP/parsing/timeout error. `AudioSendPreparer` exposes the unchanged archive directly or creates one temporary provider-compatible send copy.
7. `TranscriptionProviderRegistry` owns the shared upload/status/error/result boundary. It routes OpenRouter STT, OpenRouter multimodal, ElevenLabs, and AssemblyAI while keeping provider-specific authentication, request construction, format handling, response parsing, and pricing at the edge.
8. On success, `HistoryRepository` atomically writes the transcript, the service always archives the original M4A under the same entry ID, and auto-copy updates the Android clipboard. The stored model ID is the model that actually succeeded, including a fallback.
9. On failure of both distinct models, state becomes `ERROR`. With failed-audio saving enabled, the finalized M4A is archived once and represented by a failed History entry; Retry updates that same entry. Otherwise `recording.m4a` remains transiently available. Retry always runs the complete primary/fallback sequence again.

## Native import and Android sharing

1. `MainActivity` advertises `ACTION_SEND` for `audio/*` and also exposes an `OpenDocument` picker from Notes. Both paths pass the granted `content://` URI to a dedicated data-sync foreground service; microphone permission is not part of import processing.
2. A pending `TranscriptionEntry` is persisted before I/O starts. `HistoryNavigationController` switches to Notes and holds the entry ID until the detail UI is ready, so cold shares and warm shares open the same live detail screen.
3. `AudioImportManager` copies from the URI with a 256 KiB bounded stream and probes the actual audio track with `MediaExtractor`. MP3, M4A, OGG/Opus, AAC, and WebM remain byte-identical. Other Android-decodable sources stream through `MediaCodec` into AAC/M4A and `MediaMuxer`; no uncompressed intermediate file is written.
4. `AudioImportService` runs the same selected-primary/distinct-fallback sequence exactly once, stores the successful model or compact failure on that same entry, updates the clipboard setting, and stops its foreground service.
5. The detail UI follows the repository entry by ID rather than retaining a stale object. It renders `Transcribing…` while pending, a short `Transcribed` confirmation on the processing-to-success transition, and then the normal editor/player/actions. A killed pending import is recovered as an interrupted failed entry instead of remaining stuck forever.

## State machine

`IDLE -> RECORDING <-> PAUSED -> PROCESSING -> SUCCESS`

Failures from recording setup or transcription enter `ERROR`. A transcription error can go back to `PROCESSING` via Retry. Discard returns any active/error state to `IDLE` and removes only unsaved latest audio; an archived History file is never deleted when its re-transcription fails.

## Voice-directed cleanup

`CleanupCoordinator` is one process-local pipeline shared by Home, Note detail,
and the IME:

1. It snapshots the complete target text and records only the spoken edit
   instruction into `cache/cleanup/instruction.m4a`.
2. It deliberately reads `SettingsStore.selectedModel`, never the IME's separate
   model preference, and runs that audio through the existing
   `BatchTranscriptionEngine` with the same language, multimodal prompt, wire
   format, timeout, and distinct fallback.
3. `CleanupInstructionPolicy` counts meaningful Unicode word/number runs.
   Instructions below the configurable threshold (eight by default) and
   punctuation-only provider artifacts are silently discarded here. Temporary
   audio is removed and no edit-model request is created. Zero disables this
   guard.
4. It sends the instruction transcript, complete original text, strict editable
   cleanup system prompt, selected OpenRouter text model, and capability-gated
   reasoning/temperature to one non-streaming chat-completions request. If the
   selected wire model starts with `deepseek/`, both spoken and automatic
   cleanup add `provider.only = ["deepseek"]`; this pins inference to DeepSeek's
   own endpoint and forbids third-party hosts without changing routing for any
   other model family.
5. The owning surface receives only the complete replacement. Home and Notes
   update their existing text; the IME selects and replaces the entire host
   editor field.
6. Temporary instruction audio is deleted. Cleanup never creates a History
   entry, touches the clipboard, publishes `RecordingController`, starts a
   foreground service, or posts/cancels a notification.

The cleanup state machine is
`IDLE -> RECORDING -> TRANSCRIBING -> REWRITING -> SUCCESS`. Error and explicit
cancel remove the temporary file. Cancel also abandons the shared transcription
engine and disconnects an active text request. Only one cleanup owner can be
active.

## Source map

- `recording/RecordingService.kt`: microphone lifecycle, state transitions, API dispatch, clipboard, and audio retention.
- `recording/AudioImportService.kt`: URI import foreground lifecycle, primary/fallback dispatch, and direct-open completion.
- `recording/AudioImportManager.kt`: streamed URI copy, track probing, direct compressed-format preservation, and native uncommon-format conversion.
- `recording/NotificationHelper.kt`: active three-action notification and idle quick-record notification.
- `recording/RecordingActionReceiver.kt`: secure explicit action bridge from notifications/widgets.
- `recording/NotificationRestoreReceiver.kt`: boot/package-update restoration for the opt-in ready notification.
- `recording/BatchTranscriptionEngine.kt`: common primary/fallback, chunk, cost, and result pipeline.
- `recording/CleanupCoordinator.kt`: notification-free instruction capture, shared STT dispatch, one cleanup-model call, cancellation, and result ownership.
- `recording/AudioSendPreparer.kt`: zero-copy direct send or temporary M4A/MP3 conversion.
- `recording/MicLevelNormalizer.kt`: floor/speech trackers and the single level mapping behind every meter in the app.
- `recording/MicPeakSource.kt`: encoder amplitudes for the `MediaRecorder` paths, with the metering-only `AudioRecord` fallback for devices whose amplitude is permanently 0.
- `recording/MicProfile.kt`: what one microphone measured, and the preference-backed store that carries it across recordings.
- `network/OpenRouterClient.kt`: public catalog fetch, streaming JSON/Base64 transcription client, and bounded text-cleanup request.
- `network/TranscriptionProviders.kt`: shared provider interface and provider-specific adapters.
- `data/PriceNormalizer.kt`: converts provider-specific time billing to USD/audio-hour; token-priced STT models use the published estimated per-minute equivalent.
- `data/SecretStore.kt`: AES-GCM key encryption backed by Android Keystore.
- `data/HistoryRepository.kt`: local JSON history with `AtomicFile` crash-safe replacement.
- `data/SettingsStore.kt`: small preference-backed settings state.
- `data/ModelCatalogCache.kt`: atomic last-known-good transcription and cleanup catalogs.
- `widget/TranscriptionWidgetProvider.kt`: size-responsive `RemoteViews` rendering and controls.
- `ime/VoiceInputMethodService.kt`: display-relative 40% voice-only IME layout, themed in-surface model browser, shared batch/live recording state, selection-aware accelerated delete key, direct text insertion, accepted-live audio/History persistence, and keyboard switching.
- `ime/StreamingVoiceSession.kt`: provider-specific PCM/WebSocket transport for the cataloged ElevenLabs and AssemblyAI live models plus a zero-conversion WAV tee of the exact transmitted PCM. Capture starts with the session rather than with the socket, so audio spoken during the handshake is queued and delivered on open; audio leaves in 200 ms batches; and a silence gate stops sending 700 ms after the last speech, committing the turn as it closes and replaying a 400 ms pre-roll when speech returns.
- `recording/LiveRecordingCoordinator.kt`: process-local bridge that lets the existing foreground service's notification and widget Finish/Discard actions control the active IME session without duplicating editor or WebSocket ownership.
- ElevenLabs Scribe v2/batch and realtime entries are maintained locally from the official model documentation. The generic ElevenLabs model-list request is deliberately absent because scope-restricted STT keys can return HTTP 401 for catalog access. AssemblyAI's documented models also use the maintained catalog because no public STT listing endpoint exists.
- `data/ClipboardFeedback.kt`: the single manual/automatic clipboard boundary; cancels and replaces its Toast so confirmations never queue.
- `ui/TranscriptionApp.kt`: Record, History, and Settings Compose surfaces.

## Data and privacy

- API key: encrypted using an Android Keystore AES key; encrypted bytes and IV are stored in private SharedPreferences.
- Latest audio: private app storage at `files/latest/recording.m4a`.
- Archived audio: private app storage under `files/audio_history/`; deleting a History item deletes its audio too.
- All user-facing History deletion entry points confirm before calling the repository. A row long-press exposes the same safe actions without changing repository ownership.
- Imported audio: the same private archive, with its real wire format and original display name persisted on the History entry. Temporary import copies live only in `cache/audio_import/` and are removed after preparation.
- Re-transcription updates the existing History entry's text and successful model ID in place. It does not duplicate or replace the archived audio file, and it leaves the old transcript intact until a new attempt succeeds.
- History: private app storage at `files/transcription_history.json`.
- Backup: encrypted cloud backup and device-to-device transfer include `transcription_history.json`, `audio_history/`, and normal settings. Every persisted History/Settings mutation requests a backup pass. A user-controlled portable ZIP can export and merge-restore the same data through Android's document provider. `secrets.xml` and transient latest audio are excluded from both paths. The OpenRouter API key must be entered again after reinstall because an Android Keystore key is intentionally destroyed with the old app sandbox.
- Cleanup instruction audio is transient under `cache/cleanup/`, is not archived
  or backed up, and is deleted on success, error, or cancel.
- Network: OpenRouter audio/text/catalog requests go to `https://openrouter.ai`
  over HTTPS; configured ElevenLabs/AssemblyAI transcription paths use their
  documented HTTPS endpoints.

## Model catalog and hourly prices

The app fetches models whose OpenRouter `architecture.output_modalities` contains `transcription`. That avoids mixing general audio-understanding or realtime speech models into a completed-file transcription workflow.

One additional text-output catalog request is parsed once into audio-input
multimodal models and text-input cleanup models, avoiding duplicate full
text-catalog downloads. Every remote catalog merges provider by provider. A
failed OpenRouter STT or OpenRouter text refresh retains that provider's
last-known-good slice instead of allowing another successful
provider to erase it. The merged result is stored with `AtomicFile` and loaded
before network access. A complete automatic refresh receives a six-hour TTL;
partial failures retain the provider slices and use a ten-minute in-process
retry backoff without advancing that success timestamp. The visible refresh
control bypasses both limits.

OpenRouter's STT catalog currently does not advertise `language` for these models and exposes no per-model language list. `ModelLanguageCatalog` therefore matches current model-family IDs to provider-published profiles for Qwen3 ASR Flash, Parakeet v3, Voxtral, Whisper/GPT-4o Transcribe, and MAI Transcribe. Unknown future STT IDs use Android's complete ISO 639-1 catalog because the OpenRouter transcription endpoint itself documents the hint. The same validation runs independently for primary and fallback attempts; an unsupported persisted choice is replaced with Auto.

OpenRouter's `pricing.prompt` field has model-dependent units for STT models. `PriceNormalizer` applies the unit used by each current model family:

- per minute × 60;
- per second × 3600;
- per hour unchanged;
- GPT-4o Transcribe token rates shown as OpenRouter/OpenAI's estimated audio-hour equivalent.

Unknown future time-billed STT models default to the most common per-minute interpretation and remain visibly sourced from the live catalog. The offline fallback is intentionally short and exists only to keep Settings usable without network access.

## Microphone levels

Every meter in the app — the in-app waveform, the widget bitmap, the IME microphone halo, and the cleanup halo — draws the same normalized `0f..1f` level produced by `MicLevelNormalizer`.

**Where the raw peak comes from.** The live streaming path reads PCM and scans it with `MicLevelNormalizer.peakOfPcm16`. The two `MediaRecorder` paths — `RecordingService` and `CleanupCoordinator` — ask the encoder through `MicPeakSource`, which exists because `MediaRecorder.getMaxAmplitude()` returns a constant 0 for the whole recording on part of the MIUI/HyperOS fleet. On those devices the waveform is flat in this app and in most others, while the streaming path moves normally on the same microphone. If no non-zero amplitude has arrived 700 ms in, `MicPeakSource` opens a metering-only `AudioRecord` beside the recorder and reads peaks from that. That probe is paid once per device, not once per recording: any recording longer than 3 s stores its verdict, and a device already known to be silent opens the fallback at the first frame, so the waveform starts with the recording instead of a second later. The fallback never starts where the encoder meter works, gives up permanently if the second capture client cannot be opened, and shuts down again — clearing the verdict — if the encoder ever reports a real amplitude. Which path a device ends up on is logged once per recording as `mic_meter=`.

**How the level is normalized.** `MicLevelNormalizer` tracks two levels: the floor the signal returns to between words, and a deliberately slow reference for the level ordinary speech reaches. Speech lands at 55% deflection, leaving the rest as headroom so a raised voice is visibly louder, and anything within 6 dB of the floor renders as rest — a 60 dB room is still rest. The previous reference followed the signal within a syllable, which made it equal to the signal and pinned every spoken frame to full scale. Because the output is already perceptually scaled, all four renderers are linear in it; the square-root visual gain they used to apply is gone.

**What is remembered.** Learning the floor and the speech level takes a few seconds of speech, and those seconds are visible in the meter. `MicProfileStore` therefore keeps the measurement across recordings in `mic_profiles` preferences, keyed by how the level was measured — `recorder` for encoder amplitudes, `pcm` for a scanned peak, since the two are different scales. The live path and a fallen-back batch recorder share the `pcm` profile. Each recording folds its own measurement into the stored one rather than replacing it: the floor follows the new room closely, the speech margin only a quarter of the way, so one shouted note cannot recalibrate the microphone. A recording that never heard speech stores nothing.

## Widget constraint

`RemoteViews` does not support an interactive free-form `EditText`. The large layout therefore uses a read-only transcript preview. The widget root toggles recording while idle/active; Pause, fallback, discard, and done remain independent child controls.

The widget renders an anti-aliased waveform bitmap from the same bounded amplitude history used by Compose. Width, height, stroke and bar spacing are generated in density-correct physical pixels instead of raw low-resolution pixels. Idle, recording and processing all use the same 44/58-bar geometry. Each `ImageView` uses `centerCrop`, so narrower control states crop the sides rather than compressing the bitmap. A short new recording is interpolated across that fixed canvas until enough real samples exist, so its first speech is visible in the center crop immediately instead of waiting for a right-aligned buffer to arrive. During recording, the service refreshes only waveform/status at a bounded 600 ms interval. Pause/Resume and Discard stay independent, the white Accept button is restored, and tapping the remaining active body also finishes. Processing removes the idle Record view with `GONE`, so its fallback/abandon controls align to the right without reserving an invisible 48 dp slot.

Explicit app Light/Dark widget drawables use hard-coded palette colors. They do not reference `values-night`, preventing a manual Light selection from receiving a system-dark background while dynamic text and waveform colors already switch to light mode.

## Voice keyboard constraint

Android requires the user to enable an input method explicitly. Once enabled, the app targets 40% of the current display height for the complete IME footprint. `keyboardContentHeightPx()` subtracts Android's measured navigation/globe strip from that target before sizing the app-owned input view, with a 236 dp minimum required by the compact toolbar plus recorder controls. The recorder control row receives an additional bottom margin equal to one half of that strip, shifting it upward enough to visually exclude the separately perceived SystemUI area. The full remaining toolbar width belongs to the selected model; a small drawn keyboard glyph returns to the previous IME. Tapping the model control swaps the recorder for a themed, scrollable in-surface browser instead of opening a stock Android popup. Its dense 42 dp rows, 56 dp floating back bubble with 64 dp bottom-system-bar clearance, hardware Back handling, and Android 13+ `PRIORITY_OVERLAY` back-gesture callback all close without forcing a selection or dismissing the IME. Idle contains no instructional copy. Recording keeps Abandon, the 184 dp amplitude-reactive microphone with a deliberately small centered app vector, and batch-only Pause; live and idle use that right slot for an accelerated hold-to-delete key. `BalancedRecorderControlsLayout` pins the microphone to the exact center and places each 68 dp two-slot outer column halfway through the free corridor between a screenshot-calibrated 128 dp center exclusion zone and the real device edge. The 12 dp slot gap holds Edit below Space on the left and the primary Enter/Send action below Delete on the right. The otherwise-unused recorder stage shares the microphone's start/finish action, while child controls consume their own taps. Its `ReleaseInsideGesture` starts only after release inside the stage; leaving the keyboard disarms that press permanently, even if the finger returns before release. Background presses forward their pressed state only to the bounded microphone ripple rather than illuminating the whole IME. Tapping the microphone again accepts/finishes, so no separate transcription-Accept button exists. Delete first checks the editor selection and removes the complete range; only an empty selection falls back to one preceding Unicode code point. Live models leave only the unstable provider partial in the composing region, preventing subsequent provider updates from rewriting already committed text edits. They use the provider model ID selected from the same catalog.

Large non-virtualized browsers share `BrowserRenderingPolicy`: the keyboard
Notes browser, keyboard model browser, and anchored app model menu construct
30 rows at a time. Keyboard page insertion waits for a 90 ms scroll quiet
period, uses one page per boundary approach, and disables stretch overscroll so
changing the content height does not bounce an active fling. The app Notes
overview stays on Compose `LazyColumn`, which already virtualizes rows. App
model pickers filter streaming models through the same policy; the keyboard
keeps Live models because it owns the streaming session lifecycle.

Live sessions publish `RECORDING`, amplitude, elapsed time, `PROCESSING`, and `SUCCESS` through the same `RecordingController` consumed by the foreground notification and widget. `RecordingService` remains the sole notification owner; the small coordinator only forwards Finish/Discard back to the IME because the editor connection and WebSocket must remain there. `StreamingVoiceSession` writes each 16 kHz mono PCM16 chunk once to the socket and once to a sequential WAV file, adding only its 44-byte header. Accept moves that file into `audio_history`, creates exactly one `TranscriptionEntry` even when the valid result is empty, and exposes normal playback, export, backup, and re-transcription. Abandon deletes the temporary WAV and creates no Note. No resampling, codec, second recorder, or provider-specific History system is introduced.

Each batch IME start generates a UUID and passes it to `RecordingService`. The
service replaces the previous terminal `RecordingState` before recorder setup
and carries the UUID through recording, processing, success, and error. The IME
poller ignores every state whose UUID does not match its active request. This
removes the dispatch race in which polling could observe and commit the previous
global `SUCCESS` before the service published the new `RECORDING`. A matching
cancel action also closes the start-versus-view-dismiss race without touching an
unrelated recording. The same request suppresses only automatic result copying;
ordinary app, notification, widget, and re-transcription flows retain the global
auto-copy setting.

`ImeInteractionPolicy` maps the host `EditorInfo` to either an explicit editor
action or a newline. Search, Go, Send, Next, Previous, Done, and custom actions
use `performEditorAction`; multiline fields, `IME_FLAG_NO_ENTER_ACTION`, and
unspecified fields receive `\n`. This gives browser search fields their native
submit behavior while chat/app editors with a separate send control keep a line
break.

Idle voice-keyboard controls use code-drawn, bounds-centered Space/Delete glyphs
instead of font symbols. Cleanup is the same 68 dp size directly below Space,
and Delete remains available throughout cleanup capture and processing. The
cleanup surface stays neutral rather than inheriting the blue recording accent.
Its first tap snapshots the complete host editor and changes `✦` to `✓`; its
second tap finishes the instruction. `EDIT · mm:ss` confirms capture, while a
white same-silhouette layer expands by at most 22% and reaches at most 50% alpha
from measured amplitude. It is not a second ring or autonomous animator. During
processing it becomes an inert ellipsis. The instruction transcript is never
committed; only the final complete replacement is selected and committed once.

## Plain-transcription provider policy

ElevenLabs and AssemblyAI batch/live transports are deliberately feature-minimal. Their requests contain only authentication, model selection, required audio transport settings, optional user-selected language, and technically necessary streaming endpointing. ElevenLabs batch explicitly sets `tag_audio_events=false` and `diarize=false`. AssemblyAI Universal-3.5 Pro receives a minimal batch payload containing `audio_url`, `speech_models`, and an optional non-Auto `language_code`; legacy `format_text`, `punctuate`, `speaker_labels`, and `entity_detection` keys are omitted because current Universal models reject incompatible switches even when false. Live URLs omit keyterms/prompts, timestamps, entity detection, background filtering/voice isolation, diarization, multichannel separation, and formatting flags. ElevenLabs `commit_strategy=vad` is speech endpointing recommended for microphone streaming; it does not isolate audio or enrich transcript content. OpenRouter multimodal remains the only prompt-bearing path because the chat-completions endpoint requires a transcription instruction.

`AmplitudeMicView` has no animator or autonomous frame loop. Live audio delivers at most one level every 50 ms, sub-visible level jitter is ignored, and remaining invalidations are coalesced through `postInvalidateOnAnimation`. Batch state is sampled every 120 ms. The delete key schedules one main-thread repeat callback only while held and removes it on release, cancellation, or detach.

## OpenRouter request compatibility

OpenRouter STT uses `POST /api/v1/audio/transcriptions`; OpenRouter multimodal uses `POST /api/v1/chat/completions` with text plus `input_audio`. Exact compressed bytes are Base64-streamed without buffering the full file. Automatic mode preserves M4A for ordinary recordings and creates a temporary MP3 for MAI. Multimodal reasoning is emitted only when live catalog metadata advertises it, mandatory models never receive `none`, and reasoning output is excluded. Temperature `0.7` is likewise emitted only for multimodal models whose live `supported_parameters` contains `temperature`; it is never added to unrelated or incompatible endpoints.

Provider failures can be embedded in a nominal HTTP 200 response after routing has started. `TranscriptionResponseParser` detects that top-level OpenRouter error envelope. When the distinct fallback model succeeds, the primary model's condensed error is retained on the History entry and the current recording state, then shown directly below the primary model on Home as well as in Notes. `TranscriptionPerf` logs only model suffix, validated format, byte counts, request-write/header/body timings, and status; it never logs audio, transcript text, source names, or credentials.

Files longer than three minutes use a common batch engine. It creates bounded 180-second compressed parts with 750 ms overlap, transcribes every part through the same primary/fallback policy, removes duplicated overlap words, and deletes transient parts. M4A/OGG/WebM use container remuxing, MP3 uses compressed frame extraction, and no uncompressed upload is created. OpenRouter `usage.cost`, seconds, and token totals are accumulated across parts and persisted with the single joined History entry.

Picker and Android-share imports enter one foreground FIFO. Each URI receives a durable pending History entry immediately; queue position and part progress are updated atomically until success or a retained failure. A single synchronized state transition closes the worker/empty-queue race when a new intent arrives as the prior job finishes.
