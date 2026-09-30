# Work log: 1.03, 2026-09-30

## Scope and diagnosis

The requested release adds AssemblyAI 3.6 Pro Live, investigates automatic Cleanup/Spoken Edit, permits a fixed cleanup host, selects the minimum required reasoning effort, and lets the center microphone skip automatic Cleanup without losing transcription.

- Inspected clean `main` at `e6c463c`, existing version 1.02/code 3, GitHub releases and workflows, app/keyboard selectors, settings/cache/backup, automatic and spoken cleanup, batch/service ownership, and streaming transport. Consulted prior cleanup/release notes and reverified current source/provider documentation.
- Checked the official AssemblyAI v3 model enum, PCM contract, languages and published price. Verified the OpenRouter model reasoning catalog and exact endpoint tags for MiMo and DeepSeek; tags are used directly, without deriving slugs from display names.
- The user reported brief/disappearing Cleanup status with Meta Spark, then confirmed MiMo worked and supplied an OpenRouter log screenshot showing a completed text request. This does not establish a universal cleanup failure or a reproducible Spark root cause. No private screenshot, real transcript or credentials are committed.
- Source defects found: unsupported saved reasoning efforts survived model switches; Auto was serialized as `effort: none`; unknown selected cleanup models were silently replaced by the first catalog entry; request failures silently returned raw text; live cleanup discarded cost/failure details; the center microphone was disabled during cleanup; settings updates could race a background catalog refresh.
- No device is listed by `adb devices -l`; new-APK device/provider behavior has not been claimed as verified.

## Implementation

- Added the 3.6 streaming model ID, API mapping, streaming detection, language profile and fixed catalog entry. Reused the existing keyboard streaming audio protocol and kept batch selectors free of streaming models.
- Added an on-demand endpoint repository, five-minute in-memory cache, refresh/retry and inline model-row host disclosure. Pins are stored per model in settings and portable backups. Requests use `provider.only` with the exact tag and `allow_fallbacks=false`; unpinned requests retain latency sorting/fallbacks.
- Added one reasoning policy shared by UI, cleanup and multimodal transcription. Mandatory Off/Auto resolves to the lowest supported effort. Unsupported values normalize before requests; absent effort metadata does not create fictitious levels.
- Added streamed cleanup parsing: comments/multiline SSE framing, content-only accumulation, usage accounting, `[DONE]` validation, mid-stream errors, and rejection of truncated/content-filtered replacements. Reasoning text is never displayed or retained.
- Added a request-owned automatic cleanup task. Skip completes immediately with raw text and cancels only that task's connection. A late response cannot complete the result a second time. Cancellation before connection assignment is checked as well. The batch service and live keyboard own their tasks; spoken instructions still bypass automatic cleanup.
- Added an explicit cleanup stage to recording state and progress. The center control skips only that stage. Request IDs prevent a stale keyboard action from skipping another recording. Full abandon stays a separate action.
- Preserved cleanup warnings in existing result/note feedback, surfaced keyboard warnings, and retained live cleanup cost. Spoken Edit rejects applying a result over changed text. Synchronized settings updates preserve user choices during catalog refresh.
- Normalized mixed-encoding files (`Models.kt`, `TESTING.md`) to UTF-8. An initial whole-file Windows-1252 conversion damaged already-UTF-8 punctuation and caused an existing preset-label test to fail; corrected it by preserving valid UTF-8 and decoding only legacy bytes. Required-reasoning tests were updated for the newly requested minimum-effort behavior.
- Bumped to 1.03/code 4. Added test-only JSON/MockWebServer libraries; neither is shipped in the APK.

## Verification and publication

Commands used: `gradlew.bat testDebugUnitTest assembleRelease lintDebug --console=plain` with Android Studio's bundled JDK, `git diff --check`, provider catalog GETs, `adb devices -l`, GitHub CLI release inspection, SDK APK/signature tools. Local build logs remain ignored under `build/`.

Regression coverage includes exact streaming ID/catalog eligibility/languages; model identity after catalog removal; minimum mandatory reasoning and actual option lists; fixed endpoint routing in both automatic and spoken requests; Off/Auto serialization; provider-tag parsing/P50/unknown metrics; model-specific pin serialization; HTTP/stream failures; content-only streamed replacement and final usage; immediate skip, skip-before-start and rejection of late responses.

Final local validation:

- `testDebugUnitTest assembleRelease lintDebug`: BUILD SUCCESSFUL; **171 tests**, zero failures/errors/skips.
- Lint: zero errors, 69 warnings (includes dependency-version/catalog suggestions for test-only libraries and existing platform/UI warnings).
- `git diff --check`: passed. Git only reports Windows line-ending normalization notices.
- Signed release APK verifies with signature scheme v2. Certificate SHA-256 `d075ff04a187b8cffca592f884f129f7b8edb29b3586e1af1000d04192b38cc7`, matching the published 1.02 verification certificate.
- Package `io.github.totec448spec.transcription`, version `1.03`/code `4`, min SDK 28, target SDK 36; non-debuggable; arm64-v8a, armeabi-v7a, x86 and x86_64.
- APK SHA-256 `9755b785337922eba2eed7f5e1b100558e6266ed72f245bfe1c229d3180d40bf`.
- Release signing material, build logs and private recordings are excluded from the commit/release. Only the signed APK, checksum, verification record and existing privacy/license notices are publication assets.

The release verification attachment records the source commit and GitHub CI result. Publication is gated on that source revision passing CI and asset digests matching the local signed APK. Device installation/runtime and live provider performance remain unverified.

Publication receipt (2026-09-30):

- Released source and lightweight tag `v1.03`: `ed6c94b80122d60feb3bfd1e58ab9ed9e385c794`. The GitHub tag API confirmed this exact commit.
- [GitHub CI run 36738128966](https://github.com/totec448-spec/Transcription/actions/runs/36738128966) succeeded for that source, including tests, APK assembly and artifact upload.
- [Public release v1.03](https://github.com/totec448-spec/Transcription/releases/tag/v1.03) published at `2026-09-30T15:43:25Z`, with `isDraft=false` confirmed by GitHub CLI.
- All five uploaded asset SHA-256 digests matched local files before publication: APK, checksums, verification record, privacy notice and third-party notices. The public release reports the same digests.
- Independently downloaded the published 1.02 APK, matched its published digest, and verified that its signing certificate equals 1.03's certificate, confirming update compatibility.
- This publication receipt is a documentation-only follow-up; the release tag, APK and verified source revision remain unchanged.
