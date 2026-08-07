# Privacy

This app has no backend. There is no account, no telemetry, no analytics, and
no server operated by this project. Nothing is collected about you, because
there is nowhere for it to be collected to.

What follows is every point at which data leaves the device or is stored on
it.

## What is sent off the device

**Audio and text go to the transcription provider you selected**, using the
API key you entered, at the moment you finish a recording or trigger a
cleanup. That is the entire outbound data flow. Concretely:

| What | Goes to | When |
| --- | --- | --- |
| The recorded or imported audio file | OpenRouter, ElevenLabs, or AssemblyAI — whichever you selected | When a transcription runs |
| Your API key, as an `Authorization` header | The same provider | With every request, for authentication |
| The transcript text | An OpenRouter text model | Only when cleanup or a voice edit runs |
| The **complete current content of the text field** | An OpenRouter text model | When you use the keyboard's voice-edit key, because the model needs the full text in order to rewrite it |

That last row matters and is easy to miss: a spoken edit sends whatever is
already in the field — text you typed yourself, or text another app put
there — not just what you dictated. Do not use voice edit on a field
containing something you would not send to a model.

### What the providers do with it

This is worth knowing before you dictate anything sensitive, because the
defaults are not privacy-maximal and this app does not currently override
them:

- **AssemblyAI** is reached through its global (US) endpoint. An EU endpoint
  exists but the app does not use it yet. Uploaded audio is removed by
  AssemblyAI after a short period, but **the finished transcript can be
  retained indefinitely** unless your account sets a TTL — and this app does
  not currently send a delete request after fetching a result.
- **ElevenLabs** retains requests by default. Zero-retention mode exists for
  eligible accounts; the app does not request it.
- **OpenRouter** forwards your request to whichever model provider serves the
  chosen model, and those providers differ in retention and training policy.
  OpenRouter's default data policy allows providers that store data. Cleanup
  requests to DeepSeek models are pinned to DeepSeek's own endpoint; other
  models use normal routing.

All three may process data outside your country. Each provider's own terms
and retention policy govern what happens to submitted audio, and **deleting a
note in this app does not delete anything at the provider.** If that matters
to you, use the provider's own dashboard, or configure retention on your
account.

## What stays on the device

- **Transcripts and note metadata** in the app's private storage.
- **Archived audio** — every successful recording is kept as a file in private
  app storage until you delete it.
- **API keys**, encrypted with AES-GCM under a key held in the Android
  Keystore.
- **Usage totals** — minutes, request counts, and cost per key.

## Android backup

Settings, note metadata, and archived audio **are included in Android's
encrypted cloud backup and device transfer**, so they survive a reinstall or a
move to a new phone. That means your recordings and transcripts are stored in
your own Google account backup, subject to Google's terms — this is an
Android platform feature, not a transfer to this project.

**API keys are deliberately excluded** from every backup and transfer set;
they have to be re-entered after a reinstall.

You can turn this off entirely in Android's own backup settings.

## The keyboard

The voice keyboard runs as an Android input method. Two things are worth
stating plainly:

- It reads the **system clipboard** when the input field changes, and — if you
  grant the optional image permission — lists recent **screenshots**, so it can
  offer both in its clipboard panel. Both are displayed locally and are never
  transmitted. Declining the image permission just leaves the panel showing
  text only.
- An input method technically *can* see what is typed into fields it serves.
  This one only reads the field content when you explicitly trigger a voice
  edit (see the table above). There is no keylogging and no background
  capture — the source is public, and `VoiceInputMethodService.kt` is where to
  verify that.

## Permissions

| Permission | Why |
| --- | --- |
| Microphone | Recording. Required. |
| Notifications | The recording runs in a notification you can stop from anywhere. |
| Internet / network state | Reaching the provider; the Wi-Fi-only option needs the network state. |
| Read media images | Optional, for the keyboard's screenshot panel only. |
| Boot completed | Restoring the optional quick-record notification after a reboot. |
| Vibrate | Haptic feedback. |

## Deleting your data

Deleting a note removes its transcript and its archived audio file. Clearing
notes removes all of them. Uninstalling the app removes the entire private
sandbox, including the encrypted keys — but not any Android backup that was
already made, and **not anything a provider has retained on their side**.

## Recording other people

The app records whatever the microphone hears, and it uploads that to a third
party. Granting the microphone permission is your consent — it is not the
consent of anyone else in the room.

In Germany, recording a non-public spoken conversation without authorization
is a criminal offence under § 201 StGB, and using or passing on such a
recording is covered as well. Comparable rules exist across the EU and
elsewhere. The same applies to audio you import: a forwarded voice message or
a downloaded recording is someone else's speech.

Record and import only what you are allowed to record and import. The app
deliberately has no hidden recording mode — recording always runs as a
visible foreground notification.

## Professional use

Using this app does not make your processing lawful by itself. If you handle
other people's personal data professionally — patients, clients, employees,
interviewees — then you are the controller: you need a legal basis, you may
need a data processing agreement with the provider you chose (all three offer
one), and international transfers are your call to assess. Special categories
such as health data carry additional requirements.

This project makes no claim that the app is "GDPR compliant"; compliance
depends on how you use it, not on the software.
