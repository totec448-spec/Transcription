# Connected-device performance evidence — 2026-07-21

Device: physical Android test device.

## Navigation and scrolling

Test sequence after `gfxinfo reset`: Home → Notes swipe, Notes list scroll, Notes → Settings swipe, Settings scroll, then two right swipes back to Home.

- Total frames: 312
- Janky frames: 4 (1.28%)
- 50th percentile: 14 ms
- 90th percentile: 24 ms
- 95th percentile: 36 ms
- 99th percentile: 117 ms
- Slow UI thread / missed deadlines: 4
- Slow bitmap uploads: 0

## Recording CPU

`adb shell top -b -n 5 -d 1 -p <pid>` was sampled with the app backgrounded and recording started from the real launcher widget.

- Idle process: 0.0% in five consecutive one-second samples.
- Original 44.1 kHz / 128 kbps recording: 11–16% of one CPU core.
- Voice-optimized 16 kHz / 64 kbps recording, latest real widget run: 7.4%, 8.0%, 7.0%, 12.0%, and 7.0% of one CPU core across five one-second samples.
- Real widget-recording temperature sample: unchanged at 35.5 °C before/after in the earlier run; the latest measured battery temperature was 34.8 °C.
- Representative recording memory before the codec reduction: 106.9 MB total PSS, 215.5 MB total RSS. Codec reduction did not add memory-heavy structures.

## Runtime proof

- `dumpsys activity services` showed `RecordingService` as a foreground microphone service after the real widget tap.
- Active widget screenshot shows Waveform, Pause, Discard, and Done with Done at the stable far-right anchor.
- Active notification screenshot shows a live system chronometer. `dumpsys notification` reported `android.showChronometer=true`; no one-second app-side notification update loop is used.
- Tapping notification content and the widget waveform left the system launcher as `topResumedActivity`; only their explicit controls dispatch actions.
- Test recordings were discarded through the widget X; `dumpsys activity services` then reported no active service. No OpenRouter transcription request or credit spend was used for profiling.

## Build verification

Final checks:

```
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Build, unit tests, and lint completed successfully before the final install. Payload tests additionally verify JSON `input_audio`, M4A format, streamed Base64 integrity, normalized language inclusion, Auto omission, and absence of a prompt field.

## Connected-device recovery and audio proof — 2026-07-22

The final debug APK was installed in-place on the same device without clearing its existing Notes entries.

- A new recording made with **Highest · compressed** was copied read-only from app-private storage and inspected with `ffprobe`: AAC LC, 48,000 Hz, mono, exactly 192,000 bit/s, M4A/MP4 container. The sample was 15.232 seconds and 372,441 bytes. This proves the physical device encoder honored the selected capture settings; `TranscriptionRequestWriterTest` separately proves those exact bytes are Base64-streamed as `format: "m4a"`.
- For a no-credit failure test, **Wi-Fi only uploads** was enabled and Wi-Fi was temporarily disabled before finishing a three-second recording. The service stopped before any OpenRouter model request, archived one 76,505-byte M4A, and added exactly one Notes entry.
- Notes rendered the new item as **Transcription failed · audio saved**. Its detail screen displayed the retained error, playable waveform, and the four compact actions: Copy (disabled without text), Re-transcribe, Download, and Delete.
- The test entry was deleted through its real UI action. Its exact archived file disappeared, Notes returned to its original data set, Wi-Fi was re-enabled, and **Wi-Fi only uploads** was restored to off. No pre-existing History entry or audio file was removed.
- Final automated result: 18 unit tests, zero failures/errors/skips; lint zero errors (31 warnings); debug APK SHA-256 `9E3E2D0BE149414B6AAF552F45986266A18D8336B31D0DDAEAE9CD7F18DC5163`.

## Release and restore verification — 2026-07-22

- `testDebugUnitTest`, `assembleRelease`, and `lintRelease` completed successfully: 22 unit tests passed and Release Lint reported zero errors (32 warnings).
- `app-release.apk` was installed with `adb install -r`; package flags omit `DEBUGGABLE`, and `run-as com.example.transcription` is rejected as expected. SHA-256: `6A5B4410B496FDC705BFF07E98D7AEA9E8E0618A2F216E25BD28C7E3D997179B`.
- The connected device reports Android Backup Manager enabled and the installed package has `ALLOW_BACKUP`. Resource validation passed for encrypted cloud/device-transfer inclusion of History metadata, archived audio files, settings, and per-entry audio-format metadata. The API-key preferences are outside every include list.
- A Gradle `connectedDebugAndroidTest` attempt was permanently removed from this physical-device workflow after the failed instrumentation run uninstalled the target debug package and erased its sandbox. The regular app was restored immediately. Future physical-device updates use only the non-debuggable release APK with `adb install -r`; codec instrumentation belongs on an emulator or disposable package.
- The MAI request immediately before this release succeeded only through the configured fallback. Its primary error was not recoverable because the previous helper discarded it after fallback success. The release now retains future primary errors on the saved note and recognizes OpenRouter error envelopes embedded in HTTP 200 responses before model fallback.

## Direct-M4A Qwen latency — 2026-07-22

After removing all local audio conversion and same-model format retries, the connected device re-transcribed a real archived note once with Qwen3 ASR Flash as primary. The request sent the original 291,564-byte M4A as a fixed-length 388,836-byte Base64 JSON body. Writing/upload handoff took 18 ms, the app used 0% sampled CPU, the Alibaba/OpenRouter header wait took 2,272 ms, response-body reading took 1 ms, and status was 200. No app-owned `MediaCodec` activity occurred. The remaining processing delay is therefore provider inference/network round-trip, not phone processing. The final writer uses a 256 KiB streaming buffer to keep long-note handoff overhead bounded without creating a second full in-memory copy.

## Native audio import and WhatsApp share proof — 2026-07-22

- A WhatsApp voice note used as the test fixture advertised `audio/ogg; codecs=opus` and a `PTT-<date>-WA<n>.opus` filename. Android's share sheet listed Transcription as a matching native target after the release upgrade.
- Sharing it opened the new History detail directly. The app archived the exact 24,150-byte OGG/Opus source, displayed its filename and 10-second duration, and Qwen returned the expected German transcript. `TranscriptionPerf` recorded a fixed-length 32,284-byte JSON request: 72 ms write, 1,885 ms provider/header wait, 1 ms response read, HTTP 200. Foreground-service start to completed response was 2,052 ms, leaving at most 94 ms for activity routing, URI copy/probe, persistence, navigation, and request setup. No app-owned codec event occurred.
- The Notes upload arrow was tested separately through Android Files with a locally generated 8,612-byte, one-second MP3. The detail opened automatically and Qwen accepted `format=mp3`: 11,568-byte JSON, 96 ms write, 1,927 ms provider/header wait, 1 ms response read, HTTP 200. The blank transcript was valid because the fixture contained only a sine tone.
- The synthetic MP3 Note, device Downloads fixture, local fixture, and temporary screenshots were deleted after verification. The successful real WhatsApp Note was retained. No existing user Note or WhatsApp message was modified.
- Final local gates: 24 unit tests, zero failures; Release Lint zero errors (31 warnings); non-debuggable release APK installed with `adb install -r`. APK SHA-256: `A7E8A73EB0972B99543EF8A85F767D59F473D583316CA875E99C54E9943A038D`.

## FIFO, long-MP3 and portable-backup proof — 2026-07-22

- Multiple real Android Files selections entered the foreground FIFO and completed sequentially with Qwen3 ASR Flash. Direct MP3 payloads of 4,752,842 and 3,998,428 bytes returned HTTP 200; History exposed queued/processing state rather than rejecting concurrent imports.
- A real 3:22, 5.6 MB MP3 crossed the three-minute boundary and produced two compressed requests: 2,880,000 bytes and 365,952 bytes. Both returned HTTP 200. The detail page showed one joined transcript, `2 parts`, and `US$ 0.0071` accumulated from OpenRouter response usage.
- Portable backup produced a 35,573,979-byte ZIP with 33 entries: one manifest plus compressed archived audio. The user selected the ZIP in Android Files and confirmed successful restore. The API key is not part of the manifest or ZIP include set.
- The system launcher was used to inspect the large transcript widget and horizontal active-control widget. Size-specific outer radii, a concentric 10 dp inner preview radius inside the 22 dp shell, centered separator, organic idle waveform, and thin active silence floor replace the stretched one-layout geometry.
- An ADB-injected MediaStore URI without a delegable grant exposed a `SecurityException` at foreground-service start. This is not the normal Android share/picker path, but the final code catches the invalid grant and creates a bounded failed History item rather than crashing.
