---
plan_id: 02-store-assets
phase: 2
slug: store-assets
title: Produce Play Store icon, feature graphic, and 4 screenshots via reproducible pipeline
wave: 1
depends_on: []
requirements:
  - ASSET-01
  - ASSET-02
  - ASSET-03
autonomous: true
files_modified:
  - android-app/store-assets/generate.sh
  - android-app/store-assets/README.md
  - android-app/store-assets/icon-512.png
  - android-app/store-assets/feature-graphic-1024x500.png
  - android-app/store-assets/screenshot-01-library.png
  - android-app/store-assets/screenshot-02-player.png
  - android-app/store-assets/screenshot-03-visualizer.png
  - android-app/store-assets/screenshot-04-playlists.png
---

# Plan: Phase 2 — Store Assets

## Goal

Produce the three Play Store visual asset types (icon, feature graphic, screenshots) via a reproducible pipeline checked in at `android-app/store-assets/`. Every artifact meets Play Store format/size/resolution rules, is committed to git, and can be rebuilt idempotently by running `./generate.sh` from that directory.

## must_haves

1. `android-app/store-assets/generate.sh` is executable, idempotent, and produces all 6 artifacts (icon + feature graphic + 4 screenshots) when run with prerequisites available.
2. `android-app/store-assets/icon-512.png` exists, is exactly 512×512, ≤1 MB, opaque (no alpha), PNG format.
3. `android-app/store-assets/feature-graphic-1024x500.png` exists, is exactly 1024×500, PNG format, ≤15 MB.
4. Four portrait screenshots exist at the documented paths, each ≥320 px wide, portrait orientation, ≤8 MB, PNG format.
5. Screenshots showcase the four app surfaces the ROADMAP enumerates: library, player, visualizer, playlists.
6. `android-app/store-assets/README.md` documents prerequisites (ImageMagick, adb, a running emulator or connected device with the debug APK installed and logged in as the test user), invocation (`./generate.sh`), and where each artifact ends up.
7. `generate.sh` prints a clear, actionable error when prerequisites are missing (e.g., `magick` not on PATH, `adb devices` returns no device, foreground image not found).

## Success criteria from ROADMAP

Per `.planning/ROADMAP.md` Phase 2:

1. App icon is 512×512 PNG, ≤1MB, meets Play Store design requirements — Task 1.
2. Feature graphic (1024×500 PNG) created and optimized, visually represents the app — Task 2.
3. Minimum 2 phone screenshots (portrait, ≥320px wide, ≤8MB each) — Task 3 produces 4.
4. Screenshots showcase key features: library view, player, visualizer, playlists — Task 3.

---

## Task 1 — Render 512×512 icon from adaptive icon

<task id="T1" requirements="ASSET-01">

<action>
Add a shell function `build_icon` to `android-app/store-assets/generate.sh` that produces `icon-512.png` from the existing adaptive icon sources in `android-app/app/src/main/res/`.

Procedure:
1. Verify `ImageMagick` is available: `command -v magick || command -v convert` — error out with install guidance for macOS (`brew install imagemagick`) if neither is found. Prefer `magick` (IM7) but fall back to `convert` (IM6).
2. Source foreground: `android-app/app/src/main/res/drawable/ic_launcher_foreground_img.png`. Fail fast with a clear message if the file does not exist.
3. Background color: `#0B0E23` (matches `drawable/ic_launcher_background.xml`). Hard-code as a variable at the top of the script for easy change.
4. Render:
   - Create a 512×512 canvas filled with `#0B0E23` (opaque, no alpha channel).
   - Resize the foreground PNG to 66% of canvas width (≈338 px, preserving aspect ratio) with high-quality resampling (`-filter Lanczos`).
   - Composite the resized foreground centered on the canvas.
   - Strip alpha and flatten: output must be opaque PNG (Play Store rejects transparent icons).
   - Write to `android-app/store-assets/icon-512.png`.
5. Verify output: dimensions exactly 512×512, file size ≤1 MB, no alpha channel. Use `magick identify -format '%wx%h %[channels]'` and `stat` to check.

Example invocation (bash):

```bash
BG="#0B0E23"
FG="android-app/app/src/main/res/drawable/ic_launcher_foreground_img.png"
OUT="android-app/store-assets/icon-512.png"
magick -size 512x512 "xc:${BG}" \
  \( "$FG" -resize 338x338 \) \
  -gravity center -composite \
  -alpha off -background "$BG" -flatten \
  "$OUT"
```

(If only `convert` is available, swap `magick` for `convert`; same flags work.)
</action>

<read_first>
- android-app/app/src/main/res/drawable/ic_launcher_background.xml (confirm #0B0E23)
- android-app/app/src/main/res/drawable/ic_launcher_foreground_img.png (confirm exists, resolution adequate for 512×512)
</read_first>

<verify>
- `magick identify -format '%wx%h' android-app/store-assets/icon-512.png` prints `512x512`.
- `magick identify -format '%[channels]' …` prints a value containing `srgb` and NOT `rgba` (no alpha).
- `stat -f %z android-app/store-assets/icon-512.png` (macOS) prints a byte count ≤ 1048576.
</verify>

</task>

---

## Task 2 — Produce 1024×500 feature graphic

<task id="T2" requirements="ASSET-02" depends_on="T1">

<action>
Add a shell function `build_feature_graphic` to `generate.sh` that composes:
- Left third: the icon from Task 1, centered vertically.
- Center/right: wordmark "Bombest Beats" (96 px) stacked above tagline "Your personal library, streamed." (36 px), left-aligned.
- Background: `#0B0E23` with a thin 4 px accent stripe along the bottom using a brighter contrast color (derive from visualizer palette — use `#6F42C1` purple accent).

Procedure:
1. Start from a 1024×500 canvas filled with `#0B0E23`.
2. Composite the 512×512 icon resized to 380×380 at position `(60, 60)` (vertical center: 60+380=440, leaves 60 px bottom margin on the 500 px canvas — visually balanced).
3. Add the wordmark text: "Bombest Beats" at position `(500, 200)` in bold sans-serif (use ImageMagick's built-in font — `-font Helvetica-Bold` or `-font Arial-Bold` on macOS; fall back to `-font Helvetica` if bold unavailable). Size 96 px. Fill color `#FFFFFF`.
4. Add the tagline: "Your personal library, streamed." at position `(500, 300)` size 36 px, fill `#CCCCCC`.
5. Draw a 4 px accent stripe at the bottom: fill `#6F42C1` from `(0, 496)` to `(1024, 500)`.
6. Write to `android-app/store-assets/feature-graphic-1024x500.png` as opaque PNG.

Example invocation sketch:

```bash
ICON="android-app/store-assets/icon-512.png"
OUT="android-app/store-assets/feature-graphic-1024x500.png"
magick -size 1024x500 "xc:#0B0E23" \
  \( "$ICON" -resize 380x380 \) -geometry +60+60 -composite \
  -font Helvetica-Bold -pointsize 96 -fill "#FFFFFF" \
    -annotate +500+250 "Bombest Beats" \
  -font Helvetica -pointsize 36 -fill "#CCCCCC" \
    -annotate +500+320 "Your personal library, streamed." \
  -fill "#6F42C1" -draw "rectangle 0,496 1024,500" \
  -alpha off -background "#0B0E23" -flatten \
  "$OUT"
```

If the host lacks Helvetica, probe for available fonts with `magick -list font` and pick a sans-serif bold fallback (Arial-Bold, DejaVu-Sans-Bold); surface the chosen font in a log line so reproductions are clear.
</action>

<read_first>
- android-app/store-assets/icon-512.png (produced in Task 1)
</read_first>

<verify>
- `magick identify -format '%wx%h' android-app/store-assets/feature-graphic-1024x500.png` prints `1024x500`.
- Visual inspection: `open android-app/store-assets/feature-graphic-1024x500.png` (macOS) — icon left, "Bombest Beats" and tagline right, purple stripe bottom, dark navy field.
- File size ≤ 15 MB (Play Store hard limit).
</verify>

</task>

---

## Task 3 — Capture 4 portrait screenshots via adb

<task id="T3" requirements="ASSET-03">

<action>
Add a shell function `capture_screenshots` to `generate.sh` that drives a connected Android device/emulator to the four target screens and captures a screenshot on each.

Prerequisites documented in README.md:
- An Android emulator running a Pixel 7/8 profile, OR a real Pixel device connected via USB with debugging enabled.
- The debug APK installed: `(cd android-app && ./gradlew installDebug)`.
- The device is logged in as the test user (the app persists credentials in DataStore, so a one-time manual login is sufficient).

Procedure:
1. Verify `adb` is available: `command -v adb` — error out with install guidance otherwise.
2. Verify exactly one device/emulator is connected: `adb devices | tail -n +2 | grep -c "device$"` must equal 1. If zero → "No device. Start an emulator or connect a Pixel." If >1 → "Multiple devices; set `ANDROID_SERIAL` to disambiguate."
3. Launch the app: `adb shell am start -n com.bombest.music/.MainActivity`. Sleep 4 s for app startup.
4. For each of the four target screens, navigate via `adb shell input` taps and capture. Because UI layouts may shift, navigation should be driven by the same page-object selectors as the E2E tests (resource IDs and content descriptions). Rather than hardcoding coordinates, prefer `adb shell uiautomator dump` + parsing, OR launch a lightweight instrumentation that deep-links.
5. Simpler approach — use deep links / broadcast intents where they exist, otherwise accept manual navigation the first run with a pause prompt between screens:
   ```bash
   for screen in library player visualizer playlists; do
     echo "Navigate to $screen screen, then press Enter…"
     read -r _
     adb shell screencap -p /sdcard/bombest-$screen.png
     adb pull /sdcard/bombest-$screen.png android-app/store-assets/screenshot-$(printf %02d $i)-$screen.png
     adb shell rm /sdcard/bombest-$screen.png
   done
   ```
   The automated (best-effort) path uses `adb shell input tap` with coordinates derived from each page object's known positions; on flake, fall back to the manual prompt.
6. Validate each output:
   - Dimensions ≥ 320 px wide, height > width (portrait).
   - File size ≤ 8 MB.
   - PNG format.

Output paths:
- `android-app/store-assets/screenshot-01-library.png`
- `android-app/store-assets/screenshot-02-player.png`
- `android-app/store-assets/screenshot-03-visualizer.png`
- `android-app/store-assets/screenshot-04-playlists.png`

Optimization: if any screenshot exceeds 8 MB (unlikely from a phone screencap but possible on tablet-sized emulators), pipe through `magick` with `-strip -quality 90` to reduce.
</action>

<read_first>
- android-app/app/src/androidTest/java/com/bombest/music/pages/LibraryPage.kt
- android-app/app/src/androidTest/java/com/bombest/music/pages/PlayerPage.kt
- android-app/app/src/androidTest/java/com/bombest/music/pages/PlaylistsPage.kt
- android-app/app/src/main/java/com/bombest/music/ui/screens/LibraryScreen.kt
- android-app/app/src/main/java/com/bombest/music/ui/screens/PlayerScreen.kt
- android-app/app/src/main/java/com/bombest/music/ui/screens/PlaylistScreen.kt
(to discover deep-link intents or nav shortcuts the app already supports; if any exist, prefer them over tap coordinates for reproducibility)
</read_first>

<verify>
- All four screenshot files exist at the documented paths.
- `magick identify -format '%wx%h\n' android-app/store-assets/screenshot-*.png` — every line shows width ≥ 320 and height > width.
- `stat -f %z …` (macOS) or `stat -c %s …` (linux) — every size ≤ 8388608 bytes.
- Content check: visually inspect each PNG matches the screen implied by its filename (library list, player controls, visualizer, playlist view).
</verify>

</task>

---

## Task 4 — Pipeline orchestration + documentation

<task id="T4" requirements="ASSET-01,ASSET-02,ASSET-03" depends_on="T1,T2,T3">

<action>
1. Finalize `android-app/store-assets/generate.sh`:
   - Shebang `#!/usr/bin/env bash`; `set -euo pipefail`.
   - Change into the repo root (`cd "$(git rev-parse --show-toplevel)"`) so paths are stable regardless of invocation directory.
   - Dispatch on first argument: `all` (default), `icon`, `graphic`, `screenshots`.
   - Call `build_icon`, `build_feature_graphic`, `capture_screenshots` in order for `all`.
   - `chmod +x` the script.
2. Write `android-app/store-assets/README.md`:
   - Why this directory exists (Play Store asset production pipeline, not shipped in APK).
   - Prerequisites checklist.
   - Invocation: `cd android-app/store-assets && ./generate.sh` for full rebuild; per-asset subcommands.
   - Asset list with Play Store target (icon → "Graphics > Icon"; feature graphic → "Graphics > Feature graphic"; screenshots → "Graphics > Phone screenshots").
   - Edit-and-regenerate instructions (change `BG` constant to recolor, swap `FG` to swap foreground, etc.).
3. Do not commit intermediate files (like pulled `/sdcard/` paths); the script cleans up.
4. Ensure PNG outputs are regeneratable byte-equivalent when inputs unchanged (ImageMagick is deterministic by default when text layout is involved; note any known non-determinism in the README).
</action>

<read_first>
- (None additional — T1–T3 produce the functions this task wires together)
</read_first>

<verify>
- `bash -n android-app/store-assets/generate.sh` (syntax check) passes.
- `android-app/store-assets/generate.sh --help` (or no args) prints usage.
- Running `./generate.sh icon`, `./generate.sh graphic`, `./generate.sh screenshots` each produce their respective artifact(s).
- `README.md` includes explicit prerequisite checklist and Play Console upload-target mapping.
</verify>

</task>

---

## Verification

- **ASSET-01**: `magick identify android-app/store-assets/icon-512.png` shows `512x512 … PNG …` and file ≤1 MB and no alpha channel.
- **ASSET-02**: `magick identify android-app/store-assets/feature-graphic-1024x500.png` shows `1024x500 … PNG …` and file ≤15 MB.
- **ASSET-03**: all four screenshots exist, portrait, ≥320 px wide, ≤8 MB.
- **Pipeline**: `./generate.sh` runs clean on a system with the documented prerequisites; produces identical dimensions/sizes on repeat runs.
- **Manual**: user opens each PNG and confirms visual fit for Play Store.

## Risks / Notes

1. **Screenshot capture is the only step that needs a running device.** If an emulator isn't available when autonomous execution reaches this phase, Task 3 will fail with a clear prerequisite error — the executor should surface this as a blocker asking the user to start an emulator, not silently skip.
2. **Font availability varies.** Helvetica-Bold is present on macOS but not all Linux systems. The script probes `magick -list font` and falls back; document the exact font used so the user can verify aesthetic.
3. **Foreground image size.** `ic_launcher_foreground_img.png` is 1.2 MB; whether its native resolution supports a 512×512 render depends on its source. If upscaling produces blurry results, document that a higher-resolution source PNG should be dropped in at that path.
4. **Play Store icon safe zone.** Play renders the 512×512 icon masked by a circle at thumbnail size. The 66% scale leaves safe padding, but if the user wants a full-bleed look they can change `338` to `512` and re-render.

## Post-Plan

- Artifacts are checked in; Phase 4 (Submission) will upload them to Play Console.
- If a visual revision is requested later, it's one parameter change + `./generate.sh` away — no manual Photoshop.
