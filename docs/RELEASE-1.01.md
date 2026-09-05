# Transcription 1.01

## Changes and investigation

- Home: the empty text field now always composes its inner editor, with the placeholder layered above it. The former conditional omitted the editor entirely while empty.
- Home autosave now runs only for explicit edits and waits 350 ms instead of 700 ms. Recording progress/result updates no longer get written back as user edits. Saves are suspended during capture/processing, and a late save cannot overwrite a different recording. Recording state updates are atomic across threads.
- The recorder tray displays the pipeline stage instead of the fixed Processing label. This improves visibility; an end-to-end reproduction of the reported hang remains a device check.
- Cleanup previously silently discarded every command shorter than eight words. The default is now one word. Existing eight-word settings migrate once; other thresholds are retained. Empty or punctuation-only input never requests a rewrite. Rejected instructions now report an actionable error, and a missing OpenRouter key is reported before recording.
- Cleanup uses OpenRouter provider.sort=latency with provider fallback enabled, removing the DeepSeek-only constraint. This prioritizes endpoints for the selected model; it does not invent latency rankings for the model picker. Reference: https://openrouter.ai/docs/guides/routing/provider-selection
- The cleanup prompt explicitly separates source text from editing instructions and explains short German requests. Stock prompts migrate; customized prompts remain intact.
- Each cleanup recording gets a unique temporary file. Cancellation generations are visible to the worker and engine ownership is checked before publication.
- Live finalization consumes its callback once and ignores cleanup completion after the originating live session was abandoned.

## Existing local optimizations reviewed and included

The checkout already contained eleven modified source files when this work began. Their related performance changes were retained and inspected: shared HTTP chat handling and connection reuse, encrypted-key read caching, cheaper history progress persistence, catalog/view and date-format caching, shared waveform buffers, cached audio probing and normalized transcript overlap matching, and earlier live finalization when a provider final arrives. No keys, signing files, recordings, or local build logs are part of the release source.

## Validation

The first build invocation found no JAVA_HOME. Subsequent invocations use Android Studio's bundled JDK. Final gates: testDebugUnitTest, assembleRelease, lintDebug, release-signature and manifest verification, GitHub CI, and published asset checksum comparison. The final local run passed all 137 tests (0 failures/errors/skips), assembled the release, and passed Lint with 0 errors and 64 warnings. A transient Kotlin constant-order compilation error during prompt editing was corrected before this final run. APK signature verification passed and its certificate SHA-256 matches the published 1.0.0 APK (d075ff04a187b8cffca592f884f129f7b8edb29b3586e1af1000d04192b38cc7). The manifest reports the original application ID, versionName 1.01 and versionCode 2. Git diff whitespace validation and a credential-pattern scan of changed source found no issues. GitHub publication follows these local gates; its CI result and asset verification are reported with the release handoff.

Device checks remain outstanding: type into an empty Home field, dictate through the keyboard into Home, finish then immediately switch editors, and run short cleanup commands with a configured provider key. No device installation or paid provider request was performed during this release; no measured API latency reduction is claimed.
