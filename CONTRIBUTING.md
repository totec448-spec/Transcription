# Contributing

Issues and pull requests are welcome. This is a small personal project, so
expect review to take a few days.

## Building

You need Android Studio (or at least an Android SDK with platform 36) and a
JDK. The Gradle daemon provisions its own JDK 21 via the toolchain config in
`gradle/gradle-daemon-jvm.properties`, so any reasonably current JDK is fine
for launching the wrapper.

```
./gradlew testDebugUnitTest assembleDebug
```

On Windows use `.\gradlew.bat`. The debug APK ends up in
`app/build/outputs/apk/debug/`.

No API keys are needed to build or run the unit tests. To actually transcribe
something you need at least one key (OpenRouter, ElevenLabs, or AssemblyAI),
entered in the app at runtime — see the README.

## Tests

Unit tests live in `app/src/test` and cover the request protocols, model
catalog handling, cleanup policies, and audio format decisions. Run them with
`./gradlew testDebugUnitTest`. CI runs the same tests plus a debug build on
every push and pull request.

If you change provider request behavior, add or adjust a protocol test — those
tests are what keep the app from silently breaking against an API.

## Pull requests

- Keep changes focused; one topic per PR.
- Make sure `./gradlew testDebugUnitTest` passes.
- The project's own code is Apache-2.0. Don't add dependencies with licenses
  that conflict with that (in particular nothing GPL — see
  `THIRD_PARTY_NOTICES.md` for why the LGPL FFmpegKit build is deliberately
  the non-GPL variant). If you add a dependency, add it to
  `THIRD_PARTY_NOTICES.md` too.

## Release signing

Release builds are signed with a private keystore that is not in the
repository. A clone without it still builds — the release type falls back to
the debug key, which is fine for development but not distributable. To sign
your own release, create a keystore and a `keystore.properties` in the project
root:

```
storeFile=your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Both files are gitignored; keep them that way.
