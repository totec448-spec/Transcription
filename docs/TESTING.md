# Verification

## Automated checks

Run:

```
./gradlew testDebugUnitTest assembleDebug lintDebug
```

`PriceNormalizerTest` covers per-minute, per-second, per-hour, and token-estimated price normalization. `LanguageCodeTest` covers valid, legacy, locale-tagged, and invalid language inputs. `ModelLanguageCatalogTest` covers model-family profiles and unknown-model behavior. `TranscriptionRequestWriterTest` verifies the current OpenRouter JSON/Base64 request shape, exact content length, actual compressed import formats, and rejection of uncompressed upload formats. `AudioImportFormatTest` covers direct MP3, M4A, and WhatsApp OGG/Opus handling plus native conversion selection for uncompressed input. `TranscriptionFallbackTest` verifies attempt order, no unnecessary second request, successful fallback attribution, same-model protection, and combined failure text. `AudioCaptureOptionsTest` verifies accepted capture values and safe defaults for invalid persisted values. `TranscriptionEntryTest` verifies that only a non-blank persisted error marks an archived entry as failed.

`CleanupTextProtocolTest` verifies strict separation of original text and spoken
instruction, bounded removal of accidental outer Markdown fences, and that all
DeepSeek cleanup IDs resolve to the original `deepseek` provider while other
model families remain unpinned.
`ModelCatalogRetentionTest` verifies that a failed partial refresh retains only
that provider's last-known-good models and that a successful slice replaces it.

## Device test matrix

Use a real device because emulator microphone, notification, widget launcher, and clipboard behavior are not sufficient proof.

1. **Permissions** — clean-install, start from the app, deny once, then grant. Confirm no recording starts without permission.
2. **Recording** — speak for 15 seconds. Confirm the waveform and elapsed time inside the app move and the notification system chronometer advances without new notifications being posted.
3. **Pause** — pause for 10 seconds, resume, and confirm paused time is excluded.
4. **Notification** — exercise Pause/Resume, Discard, and Done without opening the app.
5. **Success** — confirm OpenRouter returns text, History adds one item, and the clipboard contains the same text.
6. **Silence** — record silence. Confirm an empty transcript is accepted without a fake failure message.
7. **Retry** — use an invalid key or airplane mode, finish, restore connectivity/key, and retry without recording again.
8. **Replacement** — after an error, start a new recording and confirm the old `latest` audio is replaced.
9. **History editing** — edit/save/copy and relaunch the app to confirm persistence. Long-press a row at its left, center, and right edge; confirm the rounded app-style menu appears at the press location, then exercise Open, Copy, and Transcribe again. Tap Delete both there and in details, verify the confirmation dialog appears, cancel, and confirm the Note remains; then confirm one disposable Note deletion.
10. **Widget sizes** — resize through small, medium, and large; verify controls, state/duration, thicker short-window waveform, and preview. Tap the idle/active widget body and confirm it starts/finishes while child controls remain independent.
11. **Process/background** — record with the app backgrounded and screen locked; use the foreground notification to finish.
12. **Theme** — check system, light, and dark modes, including contrast and system bars.
13. **Catalog** — refresh and compare the count/model slugs with OpenRouter's transcription-filtered Models page.
14. **Wi-Fi only** — finish on mobile data, confirm retained audio/error, connect to Wi-Fi, and retry.
15. **Fallback** — select a deliberately failing primary and a working, distinct fallback. Finish once each from the app, notification, and widget; confirm one primary error is followed by one fallback request and History stores the fallback model ID. Then make both fail and confirm the compact error card does not exceed four message lines.
16. **Re-transcription** — open a History detail, tap **Transcribe again**, and confirm the old text/audio remain while processing. Confirm success updates that entry in place without creating a duplicate; confirm a failure leaves its audio and old text intact and can be retried.
17. **Audio quality** — record short clips at low/default/high sample rate and bitrate, plus mono/stereo on a device that supports it. Inspect the M4A metadata and file size, then transcribe each. Confirm unsupported device combinations produce a recording error rather than corrupting an existing archive.
18. **Widget visual parity** — inspect idle, recording, paused, and processing states at small/medium/large sizes. Confirm the state text remains readable, the waveform is vertically centered with rounded narrow bars, the microphone is the line icon rather than the launcher mark, and Done keeps the emphasized app-style hierarchy.
19. **Model-aware language** — refresh the live catalog and switch among Qwen3 ASR Flash, Parakeet v3, Voxtral, and Whisper. Confirm each provider-published language subset, then confirm an unknown future model uses the full ISO 639-1 list. The model menu must exactly match its selector card; unsupported persisted primary/fallback choices must return to Auto.
20. **Highest compressed audio** — after upgrade, confirm Advanced shows **Highest · compressed**, 48 kHz, 192 kbps, mono. Record and inspect a fresh M4A, then confirm a compatible model receives it directly. Exercise Balanced, Compact, and one Custom combination too.
21. **Failed-audio archive** — with **Save failed recordings** enabled, force both models or the Wi-Fi-only preflight to fail. Verify exactly one failed Notes entry appears, survives restart, exports its M4A, retries in place, and becomes a normal entry after success. Disable the setting and verify the same failure remains only in transient Retry state without adding a Notes entry.
22. **Wire-format request** — verify ordinary Automatic calls send the original archived M4A with zero conversion. Verify MAI creates one temporary MP3, deletes it after completion, and never changes the archive. Exercise both manual overrides and confirm no same-model format retry.
23. **HTTP-200 provider error** — return an OpenRouter body containing `error.code` and `error.message` with HTTP 200. Confirm it is treated as the primary failure, the distinct fallback receives the original M4A, and a succeeding fallback note displays the bounded primary error.
24. **Backup/restore** — with encrypted Android backup enabled, create one successful and one failed saved note, change model/audio settings, and request a system backup. Restore onto a clean install or second device. Confirm Notes metadata, both archived M4As, and settings return; confirm transient files and `secrets.xml` do not, and re-enter the API key. Never validate this by running `connectedAndroidTest` against the user's installed package because Gradle may uninstall the target package and erase data before the backup transport has run.
25. **Processing latency** — re-transcribe a saved note once with Qwen and inspect `TranscriptionPerf`. Confirm there is exactly one M4A request, no codec activity from the app, and the log splits request writing, provider-header wait, and response-body time without exposing transcript or key data.
26. **Native file import** — tap the Notes upload arrow, choose a small MP3, and confirm a pending Note opens immediately. Confirm exactly one `format=mp3` request, source-format playback/export, success or valid empty-silence result, and no local codec activity.
27. **WhatsApp share import** — long-press a WhatsApp voice note, choose Share, then Transcription. Verify the incoming MIME/container, direct Note opening, `Transcribing…` to `Transcribed` transition, real filename/duration, one matching compressed-format request, and transcript. Do not send, forward, star, or delete the WhatsApp message during this test.
28. **Clipboard feedback** — enable automatic copy and complete a transcription, then verify `Note copied` appears. Tap Copy rapidly at least five times and confirm there is only one current short confirmation rather than a delayed Toast queue.

## Current runtime coverage

Connected-device verification on a physical Android device covers microphone capture, pause/resume/discard, launcher-widget recording, live widget waveform updates, notification delivery, swipe navigation, Android Back behavior, Notes audio controls, the Android audio import/export pickers, direct WhatsApp OGG/Opus sharing, direct MP3 import, exact 48 kHz/192 kbps AAC recording metadata, and permanent failed-audio recovery through a blocked Wi-Fi-only preflight. Exact measurements are recorded in [PERFORMANCE.md](PERFORMANCE.md).

The live catalog was refreshed and a real MAI 1.5 request reached the provider using the current documented JSON shape, but that provider returned a generic error for the M4A recording. Qwen was restored as the selected model afterward. The Wi-Fi-only failure/archive path is device-proven without a paid request; a complete retry after connectivity restoration and deliberately forcing both paid model requests remain provider/key-dependent. Do not treat a successful Gradle build as proof of unexercised network paths.

## Queue, chunk, cost and portable-backup checks

28. **Import FIFO** — choose multiple audio files in one picker action or send multiple share intents before the first completes. Confirm every file immediately has its own Notes item, pending items show a queue position, the notification shows the pending count, and exactly one file uploads at a time in arrival order.
29. **Long compressed audio** — import an MP3 and an M4A longer than 3:00. Confirm each becomes bounded overlapping parts without an uncompressed intermediate, every part uses primary-then-fallback, the joined note removes overlap duplication, and transient chunk files disappear after success or failure.
30. **Actual cost** — compare a short note and a multi-part note with the OpenRouter generation log. Confirm details show provider-returned cost and a multi-part entry displays the sum of all successful part responses.
31. **Portable ZIP** — export from Settings, inspect the ZIP for manifest plus archived audio and absence of key preferences, then restore it. Confirm matching IDs merge, audio plays, settings return, and the API key remains whatever was already on the destination device.
32. **Invalid share grant** — launch a deliberately expired or ungranted content URI. Confirm the app records a bounded access failure and does not crash while normal WhatsApp and document-provider grants continue to import.
33. **Home fallback diagnostic** — choose a primary that returns invalid parameters and a working fallback. Confirm Home shows `Fallback used` plus the bounded primary error directly below the selected model while preserving the successful transcript.
34. **Voice keyboard discovery, layout, and session isolation** — open Setup → Voice keyboard, enable Transcription voice input in Android, return, tap Switch, and choose it. Confirm the complete IME footprint is approximately 40% of the display height: the app view must use `40% × display height − Android navigation-bar height`, subject only to its 236 dp compact-control minimum. Confirm the recorder row is shifted upward by an additional half of the measured navigation-bar height, the 184 dp microphone and halo stay clear of Android's globe/collapse shadow, and its small microphone glyph is visually centered. The 68 dp left/right control columns must each sit halfway between the visible blue center button and the corresponding outer wall, with equal 12 dp vertical slot gaps. Confirm Edit is below Space on the left and the neutral Enter/action control is below Delete on the right with the same surface styling as the other outer controls. In a Chrome search/URL field the right-bottom control must run the advertised Search/Go action; in WhatsApp and the app's multiline editors it must insert a newline instead of sending. It must be hidden during recording, processing, and voice editing. Confirm the surface follows the app's explicit Light/Dark choice, gives the model card all width except the small keyboard-icon control, and opens a themed full-width scrollable model surface. Confirm its 42 dp rows remain readable and both the 56 dp floating back bubble (64 dp above the system bar) and one system Back swipe close it without selecting a model or dismissing the keyboard. Idle contains no helper copy. Confirm the code-drawn Space and Delete glyphs are optically centered. Tap and release the otherwise-empty recorder background to confirm it shares the microphone's start/finish behavior without stealing model, keyboard, Abandon, Pause, Delete, Edit, or Enter taps; only the microphone's bounded ripple may illuminate. Repeat while dragging outside the keyboard before release: recording must not start, including if the finger re-enters before release. Select several characters in the host editor and confirm one Delete press removes the complete selection; with no selection, confirm one Unicode code point is removed, then hold and confirm repeat accelerates and stops immediately on release. For a batch model, confirm the blue microphone uses the conventional app vector glyph and low-opacity amplitude halo, Abandon remains left, Pause/Resume right, and a second microphone tap produces one final insertion without changing the clipboard or showing `Note copied`, even when Copy automatically is enabled. Complete one batch transcription, switch to another app/editor, reopen the IME, and tap the microphone: the old transcript must never appear before the new recording starts. Repeat by moving from Android launcher search to Chrome and from one YouTube Music field to another. For every listed live model, confirm Abandon remains left, Delete replaces Pause on the right and remains responsive while partial results arrive, composing text appears while speaking without restoring user-deleted committed text, and a second microphone tap finalizes it. During Live, the persistent Ready notification must be replaced by the active provider-labelled recording notification with a running timer; the widget must show Live state and level. Finish from the keyboard, notification body/Done, or widget must show Processing and then create exactly one Notes entry with the live model ID and a playable/exportable WAV. Re-transcribing that Note must create the configured temporary send format while preserving the WAV. Abandon from any surface must delete the temporary capture and create no Note.
35. **Capability-gated temperature** — use a multimodal live-catalog model with `temperature` support and verify `0.7` is sent. Use one without it and verify the field is absent rather than producing an invalid-parameters fallback.
36. **Notification chronometer** — start recording and confirm the single right-aligned custom-row chronometer advances from `0:00` without one-second notification reposts and freezes while paused. The same 42 dp row contains one left-aligned 14 sp state label; there must be no platform-template duplicate timer, transcript preview, tap instruction, or secondary line. Confirm actions remain separate and functional.
37. **Provider feature-minimal requests** — inspect one ElevenLabs batch, AssemblyAI batch, ElevenLabs live, and AssemblyAI live request. Confirm there are no prompts/keyterms, voice isolation/background filtering, timestamps, diarization/speaker identification, entities, summaries, sentiment, chapters, or multichannel separation enabled. ElevenLabs batch must send `tag_audio_events=false` and `diarize=false`; AssemblyAI Universal-3.5 Pro must send only `audio_url`, `speech_models`, and an optional explicit `language_code`—especially no `format_text`, `punctuate`, `speaker_labels`, or `entity_detection` keys. The only live segmentation field may be ElevenLabs `commit_strategy=vad`. OpenRouter multimodal is the explicit prompt-bearing exception.
38. **Notes menu anchor** — long-press the title/date area near the top, middle, and bottom of the Notes viewport. The rounded menu must originate at the pressed finger instead of one row lower; normal window-edge clamping may move it only enough to keep it on-screen.
37. **Widget invariant waveform** — verify small, medium, and large idle, recording, and processing states use crisp density-native rounded bars with unchanged spacing and height. Speak immediately after tapping Record and confirm the first samples are already visible; the short buffer must fill the visible crop rather than appearing only after several seconds. Controls may crop waveform edges but must never scale the bars narrower. Confirm processing controls end at the right edge, active Accept stays white, and tapping unused active body space still finishes.
38. **Widget explicit theme override** — while Android itself remains dark, switch the app to Light and confirm the widget background turns cream, waveform/text turn dark, Pause/Discard turn black, and Accept remains white. Switch back to Dark and confirm the complete palette changes together.

Connected-device proof now includes FIFO imports, a real two-part MP3, accumulated OpenRouter cost, and portable ZIP export/restore. The final physical-device workflow remains non-debuggable `assembleRelease` plus `adb install -r`; instrumentation stays on an emulator or disposable package.

## Cleanup and catalog regression matrix

39. **Cleanup Home/Notes** — enter a multi-paragraph text containing names,
    numbers, a link, and formatting. Tap Cleanup, confirm the icon becomes a red
    finish check without an active-recording notification, dictate one narrow
    change, then tap again. Confirm the complete target is replaced, only the
    requested change occurred, no new Note was created, no auto-copy Toast
    appeared, and `cache/cleanup/instruction.m4a` no longer exists.
    Confirm Cleanup is immediately left of Copy in both transcript surfaces;
    while active, X is the only control between Cleanup and Copy.
40. **Cleanup IME** — in a host editor, confirm Space is symmetric with Delete
    and Cleanup is directly below Delete. Tap `✦`; it must stay neutral rather
    than blue, become `✓`, show `EDIT · 00:xx`, retain the working Delete key,
    and expand the same white translucent silhouette visibly with volume (up to
    22%) without a separate ring. Tap `✓`; the instruction transcript must never
    appear, processing must show an ellipsis, Delete must remain available, and
    the entire host field must be replaced once. Confirm instruction STT uses
    the Main-app model even when the IME model picker shows another model.
41. **Cleanup cancellation/cost** — cancel during instruction STT and during the
    text request by pressing Abandon or closing the IME. Confirm temporary audio
    is deleted, host text is unchanged, and the provider request is cancelled.
    A successful cleanup should produce one STT generation plus one text
    generation; only a real primary failure may add the configured STT fallback.
    Set minimum instruction words to 8, then test silence, punctuation-only STT,
    seven meaningful words, and exactly eight meaningful words. The first three
    must return quietly to idle after STT, preserve the text, delete temporary
    audio, and create no edit-model generation. Exactly eight must create one
    edit-model generation. Set the value to 0 and confirm the guard is disabled.
42. **Catalog retention and TTL** — populate all catalogs, then independently
    fail OpenRouter STT and OpenRouter text refreshes. Relaunch and
    confirm each failed provider keeps its prior models. Confirm normal app/IME
    opens within six hours do not refetch, while the visible refresh button does.
43. **Large widget vertical balance** — resize to the large layout. Confirm the
    header begins 7 dp from the top, its height is 54 dp, bottom spacing remains
    visible, and the transcript preview gains the reclaimed vertical space.
44. **Space punctuation flyout** — in the keyboard, tap Space once and confirm a
    single space is inserted with no flyout. Hold Space and confirm the pill
    grows out of the key's top corner into the band above the controls, never
    overlapping the microphone, inside both keyboard edges, in light and dark,
    with a visible edge and shadow against the keyboard. Confirm the period is
    already armed and that releasing without moving inserts exactly `.` and no
    space. Slide sideways and confirm exactly one accent coin is lit at a time
    and that releasing on `(` inserts only `(`. Slide well below the Space key
    and confirm the release inserts nothing at all. Repeat on the narrowest
    available device and confirm the cells shrink instead of clipping. Start a
    recording and confirm Space and any open flyout disappear together.
45. **Microphone press feedback** — from idle, press and hold the microphone.
    Confirm the pressed highlight covers the 128 dp zone around the painted blue
    circle without filling the 184 dp square, and that a press on the
    transparent stage around it produces the same bounded highlight rather than
    a full-width block. Check both themes.
46. **Cleanup prompt upgrade** — on a build that stored the earlier strict
    default, relaunch and confirm Cleanup settings show the current default
    text. Store a custom prompt, relaunch, and confirm it survives untouched.
    Then dictate a light instruction such as "fix the punctuation" and confirm
    the text actually changes instead of coming back verbatim.
47. **Automatic cleanup modes** — set Clean and dictate "ähm also das Meeting ist
    neuer Absatz um drei". Confirm the filler is gone and the spoken command
    became a real paragraph break rather than the words. Switch to Polish and
    confirm the sentences are additionally smoothed. Walk the remaining modes
    with one long, rambling dictation that contains a name, a date and a link:
    Concise must be markedly shorter with all three intact, Formal and Casual
    must change only the register and add no greeting, sign-off or emoji, Notes
    must produce bullets with the action items separated out, and Prompt must
    return the request as an instruction. Switch to Off and confirm the raw
    transcript arrives with no text-model request. Confirm the prompt
    editor shows only the selected mode's prompt, and that a spoken Edit through
    ✦ still uses the separate Cleanup prompt and its own reasoning effort.
    Airplane-mode the phone mid-transcription and confirm a failed cleanup
    yields the raw transcript instead of an error or an empty note.
48. **Cleanup rewrites, never answers** — in each mode, dictate a transcript that
    is itself a question or an order, such as "was kostet ein Flug nach Rom" or
    "schreib mir bitte eine Antwort darauf". The inserted text must be that
    question or order, cleaned up; an answer, an added remark, or a refusal is a
    failure. Repeat with a dictation that addresses the model directly ("kannst
    du das kurz zusammenfassen") and confirm it comes back as text too.
49. **Cleanup prompt edits and defaults** — edit one mode's prompt, relaunch, and
    confirm the edit survives and the other modes are untouched. Clear the field
    (or retype the shipped default) and save; the row must go back to reporting
    that it follows the shipped default, and a subsequent app update must be
    able to change that mode's behaviour. On a build that stored the earlier
    Clean/Polish prompts, relaunch and confirm both show the current defaults
    while a genuinely custom prompt is carried over. Export a portable backup,
    reset, and import it; only the edited prompts must return.
50. **Key-aware models** — remove every key but AssemblyAI and confirm both the
    app and the keyboard transcribe with Universal-3.5 Pro and attempt no
    fallback. Repeat with only ElevenLabs (Scribe v2, no fallback) and only
    OpenRouter (Whisper Large V3 with Whisper Turbo as fallback). Restore all
    keys and confirm the app returns to Universal-3.5 Pro and the keyboard to
    Scribe v2.
51. **Silence trimming** — record 30 seconds with long pauses. Confirm the
    request is meaningfully smaller, the transcript is complete, and Notes
    playback still contains the pauses. Repeat on a very quiet microphone and
    confirm speech is not cut. Record under four seconds and confirm trimming is
    skipped. Disable the setting and confirm the original file is sent.
52. **Keyboard undo/redo** — in WhatsApp, dictate, then Back and confirm the
    field returns to its prior contents; Forward restores it. Run ✦ and confirm
    Back reverts the whole rewrite. Confirm both buttons are dim with nothing to
    step through, during recording, and while a panel is open. Switch to another
    app's field and confirm the stack is empty. Wait longer than two minutes and
    confirm the stack has lapsed.
53. **Usage totals** — transcribe once per provider and confirm Settings shows
    the minutes, request count, and cost, with OpenRouter exact and the other
    two marked `~`. Clear Notes and confirm the totals survive. Reset the
    counters and confirm they return to zero.
54. **Live silence gate** — with a Live model, dictate three sentences with a
    clear pause between them. Confirm the status line alternates `LIVE` and
    `PAUSED` within about a second of each pause, that each sentence is
    transcribed at the pause rather than only when the next one starts, and that
    no first or last word is clipped. Cough or tap the desk during a pause and
    confirm the gate stays closed. Disable **Trim silence before upload** and
    confirm the stream never pauses.
55. **Live start latency** — with a Live model on a slow connection, begin
    talking the instant the microphone button is tapped. Confirm the opening
    words appear in the transcript, and that the saved Note's audio starts with
    them.
56. **Microphone meter** — record in the app, on the widget, and with a Live
    model in the keyboard. Confirm the circle moves with the voice in all three
    rather than sitting pinned at full or flat at zero, that live and batch look
    alike, and that the meter is quiet in a silent room.
