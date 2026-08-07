# Handheld redesign — 2026-07-21

This document records the design decisions of the July 2026 redesign — the
reasoning behind layout, sizing, and interaction choices that the code alone
does not explain. The shipped UI is the reference; the mockups that drove the
redesign were working material and are not part of the repository.

## Home

- Fixed one-screen layout: Home has no vertical scroll container.
- The transcript editor owns the available space; model information is quiet at the top and Copy is in the lower-right thumb area.
- Playback was removed from Home. Archived audio belongs to Notes.
- Manual “save edit” was removed. Text edits persist automatically after a short debounce.
- The recorder tray is 84 dp high with balanced 10 dp horizontal / 6 dp vertical insets, a waveform on the left, and the primary control on the far right.
- Idle Record has a quiet 46 dp white surface with a 23 dp black microphone inside a 60 dp invisible touch target. During recording, Done occupies the same right-side anchor; Pause and Discard sit immediately to its left.
- Processing reserves the same right-side anchor and shows a compact activity indicator without letting the waveform expand underneath it.
- Transcript and recorder corners are 12 dp, avoiding the oversized pill look.
- Bottom navigation is icon-only, compact, and uses a small lime indicator instead of a large selected pill.

## Navigation and back behavior

- Home, Notes, and Settings are pages in one lazy horizontal pager.
- Left/right swipes and bottom navigation use the same pager state.
- A small page-history stack makes Android Back return to the previously visited app page.
- In Note Detail, Android Back first returns to the Notes list. It does not close the app.

## Notes and audio

- Notes is a dense flat list with separators, date/time, duration, transcript preview, and a full-row detail target. Playback stays in Note Detail rather than adding a large play control to every row.
- Note Detail mirrors Home: compact metadata header, lightly rounded transcript editor, compact audio tray, and bottom actions.
- Saved audio exposes a waveform, direct tap/drag seeking, ±10-second skip, Play/Pause, elapsed/total duration, and Android `CreateDocument` export as M4A.
- Every successful recording remains archived under private `files/audio_history/<entry-id>.m4a` storage until deleted.

## Widget

- Waveform is always left; Record/Done is always at the far-right anchor.
- Small active layout: Waveform, Discard, Done. Medium/large additionally show Pause/Resume.
- No duplicate Stop control.
- One-line Record remains visually compact inside a 56 dp touch target, so it is not clipped but remains easy to hit. Discard, Pause, and Done also use 56 dp touch areas.
- Dark mode follows the app theme and updates immediately after theme changes.
- The microphone is a filled black/charcoal silhouette without internal bars; it is centered with `centerInside` and cannot stretch.
- Every size exposes a short state label and duration. The waveform has a fixed layout height independent of widget width and occupies the available width from its first sample.

## Notification

- Active controls remain Pause/Resume, Discard, and Done.
- Recording uses Android's system chronometer, anchored to the true accumulated elapsed time. The OS animates it without rebuilding or reposting the notification every second.
- Paused and processing states show a static duration and update only when state changes.
- The notification body has no content action; only Pause/Resume, Discard, and Done react, so a stray tap cannot open the app.

## Efficiency decisions

- Mono AAC voice capture: 16 kHz, 64 kbps rather than 44.1 kHz / 128 kbps music settings. This keeps speech compact without an extra lossy local transcode before upload.
- Amplitude/UI samples run at 5 Hz.
- Widget waveform updates run at 600 ms, while all button/state changes remain immediate.
- Recording ticks partially update only widget waveform/status; PendingIntents and controls are not rebuilt per tick.
- Waveform history is bounded to 48 samples and widget bitmaps are 240×48 or 360×56.
- Idle waveform and Record halo no longer run infinite animations.

## Settings selectors

- Model and language use anchored dropdowns rather than modal sheets or full-screen pickers.
- The selected model is a compact 64 dp card. Dropdown rows use the app surface, 10 dp corners, a thin outline, provider/model hierarchy, hourly price, and a 48 dp minimum interaction height.
- Model descriptions are intentionally absent from the menu; they made scanning slower and forced oversized rows.
- Language starts with Auto, then device language and the selected model family's provider-published languages. Unknown future model IDs use the complete device ISO 639-1 catalog; unsupported persisted primary or fallback hints return to Auto.
- The model menu measures the selector card and uses exactly the same width. The language menu stays at 284 dp for longer native names.
