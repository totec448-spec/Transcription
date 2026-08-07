# Third-party notices

This application bundles or links against the components below. Each keeps its
own license; the Apache-2.0 grant in `LICENSE` covers only this project's own
source.

## Why Apache-2.0 is available despite the LGPL dependency

Apache-2.0 and LGPL-3.0 are compatible in this direction. Apache-2.0 code may
be combined with LGPL-3.0/GPL-3.0 code, because LGPL-3.0 explicitly permits
incorporating Apache-2.0 material — the reverse (Apache-2.0-only distribution
of LGPL code) is what is not allowed, and that is not what happens here.

Concretely: this project writes no FFmpeg code, makes no modification to
FFmpegKit, and links it dynamically as prebuilt native libraries. FFmpegKit
therefore stays a separate work under LGPL-3.0 while this project's own source
is licensed under Apache-2.0. The obligations that survive are attribution and
the ability to relink — both satisfied below.

## Shipped in the APK

| Component | Version | License |
| --- | --- | --- |
| [FFmpegKit (maintained fork)](https://github.com/ffmpegkit-maintained) — `dev.ffmpegkit-maintained:ffmpeg-kit-full` | 8.1.7 | **LGPL-3.0** |
| [smart-exception-java](https://github.com/arthenica/smart-exception) — `com.arthenica:smart-exception-java` | 0.2.0 | BSD-3-Clause |
| [OkHttp](https://square.github.io/okhttp/) — `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache-2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) (BOM) | 2026.02.01 | Apache-2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) — core-ktx, activity-compose, lifecycle-runtime-ktx | see `gradle/libs.versions.toml` | Apache-2.0 |
| [Kotlin](https://kotlinlang.org/) standard library and compiler plugins | 2.2.10 | Apache-2.0 |

## Test-only, not distributed

| Component | Version | License |
| --- | --- | --- |
| [JUnit 4](https://junit.org/junit4/) | 4.13.2 | EPL-1.0 |
| AndroidX Test — espresso-core, ext:junit | see `gradle/libs.versions.toml` | Apache-2.0 |

## FFmpegKit and the LGPL-3.0

The `ffmpeg-kit-full` package is the **LGPL** variant. The separate
`ffmpeg-kit-full-gpl` package — which adds x264, x265, xvidcore, vid.stab and
rubberband — is deliberately *not* used, because linking it would place this
application under the GPL. Do not switch to a `-gpl` artifact without
re-examining the license of this project as a whole.

FFmpegKit is linked dynamically as native `.so` libraries inside the APK and
is not statically bound into the application code. Toward LGPL-3.0 section 4,
this project provides:

1. This notice, identifying the library, its version and its license.
2. The complete corresponding source of this application, published in this
   repository under a license that permits modification, which allows anyone
   to rebuild the APK against a modified or newer build of FFmpegKit. The
   build needs no keys or private material — see the README.

That is the substance of what section 4 asks for in this configuration. It is
a good-faith reading, not a legal opinion: bundling a shared library inside an
APK is not the same situation the LGPL's "suitable shared library mechanism"
describes, and anyone redistributing compiled builds commercially should get
their own review of the packaging, including shipping the full LGPL-3.0 and
GPL-3.0 texts alongside the binary.

FFmpeg itself and the external libraries in the `full` package (including
lame, opus, vorbis, vpx, dav1d, libass, gnutls, opencore-amr and others) are
licensed under LGPL-2.1-or-later or compatible terms; the combined binary is
distributed under LGPL-3.0. Upstream sources:

- <https://github.com/ffmpegkit-maintained>
- <https://ffmpeg.org/download.html>

If you redistribute a **compiled** build of this app, ship this file with it
and keep the corresponding source available.

## Codec patents

The `full` package can encode AAC and decode a wide range of formats. Patent
licensing for some codecs (notably AAC and H.264) is administered separately
from copyright licensing and is not granted by any of the licenses above.
Distributing source code is unaffected; shipping compiled binaries
commercially in some jurisdictions may not be.

## Network services

The app talks to OpenRouter, ElevenLabs and AssemblyAI using API keys the user
supplies. No key, endpoint credential or account is bundled with this
repository, and each service is governed by its own terms.
