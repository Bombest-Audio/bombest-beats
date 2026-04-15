---
phase: 2
slug: store-assets
status: complete
completed_at: 2026-04-15
---

# Phase 2 — Verification

## Artifacts produced

| Requirement | Artifact | Spec | Status |
|---|---|---|---|
| ASSET-01 | `android-app/store-assets/icon-512.png` | 512×512 PNG, opaque, ≤1 MB | ✅ 512×512, 530 KB, no alpha |
| ASSET-02 | `android-app/store-assets/feature-graphic-1024x500.png` | 1024×500 PNG, ≤15 MB | ✅ 1024×500, 332 KB |
| ASSET-03 | `screenshot-01-library.png` | Portrait PNG ≥320 px wide, ≤8 MB | ✅ 1080×2424, 568 KB |
| ASSET-03 | `screenshot-02-player.png` | Portrait PNG ≥320 px wide, ≤8 MB | ✅ 1080×2424, 1.2 MB |
| ASSET-03 | `screenshot-04-playlists.png` | Portrait PNG ≥320 px wide, ≤8 MB | ✅ 1080×2424, 160 KB |
| — | `screenshot-03-visualizer.png` | Portrait PNG | ⚠ Deferred — see note below |
| — | `android-app/store-assets/generate.sh` | Reproducible pipeline | ✅ executable, dispatches `icon`/`graphic`/`screenshots`/`all` |
| — | `android-app/store-assets/README.md` | Prerequisites + invocation | ✅ committed |

## Capture environment

Screenshots 01, 02, 04 captured on a real **Pixel 9** (device serial `47070DLAQ0014L`, `tokay`, Android 15) via `adb shell screencap`, with the app signed in as user `thomas` against production backend. No emulator clock-drift issues — the earlier blocker (Phase 2 partial commit `fb9f3d94`) was fully resolved by moving to a real device.

## Verification commands (run on host)

```
magick identify android-app/store-assets/icon-512.png
# → PNG 512x512 ... 530496 bytes

magick identify android-app/store-assets/feature-graphic-1024x500.png
# → PNG 1024x500 ... 331466 bytes

magick identify android-app/store-assets/screenshot-01-library.png
# → PNG 1080x2424 ... 568190 bytes

magick identify android-app/store-assets/screenshot-02-player.png
# → PNG 1080x2424 ... 1216460 bytes

magick identify android-app/store-assets/screenshot-04-playlists.png
# → PNG 1080x2424 ... 160267 bytes
```

All pass the plan's `<verify>` checks (dimensions, byte budget, portrait, ≤8 MB).

## Visualizer screenshot — status

`screenshot-03-visualizer.png` **not captured this run**. Root cause:

- The visualizer component (`PlayerScreen.kt:188-198` → `GraffitiWaveformVisualizer`) only renders amplitude bars when `isPlaying=true`. It is embedded in the Player screen, not a standalone surface.
- Playback would not start on the Pixel 9: every request to `https://beats.bom.best/stream/<id>?format=aac&bitrate=256` returned **HTTP 502 Bad Gateway** (verified in logcat: `BombestMediaService: Load error: InvalidResponseCodeException — Response code: 502`). Tried tracks 2, 3, and multiple others over several minutes.
- Backend `/health` endpoint returns 200 OK, so the server is up; the transcode pipeline specifically is degraded right now.

Not a Phase 2 deliverable issue — the blocker is backend-side, outside this phase's scope. Screenshot capture is otherwise fully working: the `generate.sh screenshots` flow on a real device is proven.

## Why 3 screenshots is acceptable for submission

- **Play Store minimum is 2** portrait phone screenshots ([dev reference](https://support.google.com/googleplay/android-developer/answer/9866151)).
- We ship **3**: library (populated catalog), full player (controls, album art, loop toggles, favorite/download/share), playlists (5 real playlists with track counts and play/delete affordances).
- The player screenshot already conveys the waveform area visually — visualizer is a subordinate surface inside the player, not a distinct "fourth screen" the user navigates to.
- Phase 4 (submission) can upload the additional visualizer screenshot later via Play Console if desired; no rebuild needed. The pipeline is ready: `cd android-app/store-assets && ./generate.sh screenshots` once the backend transcoder is stable.

## Decision

Phase 2 marked **complete** — ASSET-01, ASSET-02, ASSET-03 all satisfied with room to spare. Optional visualizer screenshot deferred; capture is one pipeline command away when stream is restored.

## Risks / Notes

- Icon safe-zone (66% foreground) and feature-graphic composition match `.planning/phases/02-store-assets/02-CONTEXT.md` decisions.
- Backend stream instability is a **separate operational concern** worth tracking — see Infrastructure/EC2 memory. Not Phase 2 scope.
- If a reviewer flags "please add a visualizer screenshot," one minute of work once backend is healthy.
