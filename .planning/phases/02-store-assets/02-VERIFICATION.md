---
phase: 2
slug: store-assets
status: partial
completed_at: 2026-04-15
---

# Phase 2 — Verification

## Artifacts produced

| Requirement | Artifact | Spec | Status |
|---|---|---|---|
| ASSET-01 | `android-app/store-assets/icon-512.png` | 512×512 PNG, opaque, ≤1 MB | ✅ 512×512, 530 KB, no alpha |
| ASSET-02 | `android-app/store-assets/feature-graphic-1024x500.png` | 1024×500 PNG, ≤15 MB | ✅ 1024×500, 332 KB |
| — | `android-app/store-assets/generate.sh` | Reproducible pipeline | ✅ executable, dispatches `icon`/`graphic`/`screenshots`/`all` |
| — | `android-app/store-assets/README.md` | Prerequisites + invocation | ✅ committed |
| ASSET-03 | `screenshot-01..04.png` | 4 portrait PNGs ≥320 px wide, ≤8 MB | ⏸ Deferred — see blocker below |

## Verification commands (run on host)

```
magick identify android-app/store-assets/icon-512.png
# → PNG 512x512 ... 530496 bytes

magick identify android-app/store-assets/feature-graphic-1024x500.png
# → PNG 1024x500 ... 331466 bytes
```

Both pass the plan's `<verify>` checks (dimensions, byte budget, opaque PNG).

## Blocker — screenshot capture

Attempted `./generate.sh screenshots` against emulator `verses_pixel` (Pixel 7 profile, API 34). Outcome:

1. Emulator booted, APK installed via `adb install`.
2. `.LoginActivity` launched (MainActivity is not exported — corrected launch target).
3. Login form filled with `thomas` / `coolbean` via `adb shell input` + TAB.
4. Sign In tap landed (confirmed via UI-dump bounds `[474,1326][606,1379]`).
5. Backend request failed twice:
   - First: `Unable to resolve host "beats-aws.bom.best": No address associated with hostname` — DNS unreachable from emulator (both primary `beats.bom.best` and failover `beats-aws.bom.best` failed; emulator cannot run `nslookup`/`wget`, `ping` rejects with `Network unreachable` for ICMP).
   - Second (after wifi toggle + data clear): `Chain validation failed` — TLS failure. Root cause is the emulator clock drift: `adb shell date` returns `Sun Apr 12` on Apr 15 host. Emulator builds don't permit `adb root` or manual `date` set; `auto_time`/`auto_time_zone=1` did not re-sync.

Can't log in → can't reach Library/Player/Visualizer/Playlists → can't capture representative screenshots on this emulator.

## Path to close

Run on a real device (or fresh emulator without clock drift) once app is installed and signed in:

```sh
cd android-app/store-assets
./generate.sh screenshots   # Interactive: navigate to each screen, press Enter
```

The pipeline is already deterministic — the only missing inputs are the four screencaps. Phase 4 (Play Console upload) will need them before submission; Phase 3 (listing content) does not.

## Decision

Phase 2 is committed as **partial**: icon + feature graphic + pipeline + docs land now (all deterministic, no device required). Screenshot capture is staged for manual run on a working device before Phase 4 submission. Recording here so Phase 4 has the blocker in front of it.

## Risks / Notes

- Per the plan's own Risks section: "Screenshot capture is the only step that needs a running device. If an emulator isn't available… the executor should surface this as a blocker asking the user." Surfaced.
- Icon safe-zone (66% foreground) and feature-graphic composition match `.planning/phases/02-store-assets/02-CONTEXT.md` decisions.
