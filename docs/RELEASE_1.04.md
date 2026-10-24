# Transcription 1.04 preview

The foreground recording service owns capture and transcription for every entry point, including live keyboard models. Closing the keyboard, opening the notification shade, changing editors, or destroying the IME view no longer sends a discard or abandon action. Reopening the keyboard immediately renders the shared phase, level and elapsed time. App, keyboard, notification and widget controls act on the same session. Completed recordings are retained in Notes. Automatic editor insertion is restricted to the originating request and editor; a global recording can also be targeted to the visible editor by finishing it there.

The fallback resolver honors the saved model and its API key. It no longer forces Whisper Turbo or overwrites the preference when key availability changes. An unavailable or duplicate fallback is disabled for the request while the saved choice is retained.

Advanced → Fallback & audio capture has numeric input and a slider for a 0–20 second primary request budget. This starts when the prepared audio is sent, never while recording. Zero bypasses the primary when a usable fallback exists. A missing fallback still allows the primary to run. The fallback keeps the separate Provider request limit. AssemblyAI upload, submission and polling now share one deadline. The new preference is included in portable backups.

## Installation

The original release signing key is not available in this environment. CI therefore builds a debug-signed `Transcription Preview` with the separate package `io.github.totec448spec.transcription.preview`. It installs alongside the existing application. Open the preview, add provider keys, grant microphone access and enable **Transcription Preview voice input** in Android. Settings and Notes can be moved using the existing Back up / Restore ZIP flow; API keys must be entered again. No uninstall is required.

CI runs the unit suite, assembles the preview, verifies its APK signature and publishes an installable prerelease. The debug signing identity is cached for later preview builds; it is not a production release key.

## Device verification still needed

- Start with MAI-Transcribe 2 in the preview keyboard, pull down and dismiss the notification shade, close/reopen the keyboard, then stop in the notification or app. Confirm duration continues and the whole note is saved.
- Start from the app or notification, open the keyboard, confirm it shows the same session and can pause, resume and finish it. Confirm old results never appear in another editor.
- Start a live model, hide the keyboard and finish from the notification. Confirm the WAV and transcript reach Notes and the microphone stops.
- Pick a non-Whisper fallback, restart the app, change an unrelated setting, and verify the selection survives. Test 0, 1 and 20 seconds; verify the fallback gets its independent request budget.
