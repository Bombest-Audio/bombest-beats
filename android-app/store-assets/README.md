# Play Store Assets

Reproducible pipeline that produces the visual assets Google Play Console requires when publishing `com.bombest.music`. All artifacts are committed here so the Phase 4 submission flow can upload them without re-running the pipeline; `generate.sh` is the source of truth for regeneration.

## Artifacts

| File | Target in Play Console | Spec |
|------|------------------------|------|
| `icon-512.png` | Graphics → Icon | 512 × 512 PNG, ≤ 1 MB, opaque |
| `feature-graphic-1024x500.png` | Graphics → Feature graphic | 1024 × 500 PNG, ≤ 15 MB |
| `screenshot-01-library.png` | Graphics → Phone screenshots | ≥ 320 px wide, portrait, ≤ 8 MB |
| `screenshot-02-player.png` | Graphics → Phone screenshots | ≥ 320 px wide, portrait, ≤ 8 MB |
| `screenshot-03-visualizer.png` | Graphics → Phone screenshots | ≥ 320 px wide, portrait, ≤ 8 MB |
| `screenshot-04-playlists.png` | Graphics → Phone screenshots | ≥ 320 px wide, portrait, ≤ 8 MB |

## Prerequisites

- **ImageMagick 7** (`magick` on PATH). Install with `brew install imagemagick` on macOS.
- **Android platform-tools** (`adb`). Auto-detected at `~/Library/Android/sdk/platform-tools/adb` if not on PATH.
- For screenshots only: a running Android emulator or a Pixel connected over USB with debugging enabled, with the app installed (`cd android-app && ./gradlew installDebug`) and the test user signed in (the app persists credentials in DataStore, so a one-time manual login is enough).

## Usage

```sh
cd android-app/store-assets

./generate.sh           # icon + feature graphic + screenshots
./generate.sh icon      # Task 1 only
./generate.sh graphic   # Task 2 only
./generate.sh screenshots  # Task 3 only — needs a running device
```

Screenshot capture is interactive by design: the script launches the app and pauses before each screencap so you can navigate to the target screen (library → player → visualizer → playlists). This sidesteps the fragility of tap-coordinate automation across Compose layout changes. One `Enter` per screen.

## Changing the design

All visual decisions are parameters at the top of `generate.sh`:

- `BG_COLOR` — canvas/background color (default `#0B0E23`, matches `ic_launcher_background.xml`).
- `ACCENT_COLOR` — feature-graphic accent stripe (default `#6F42C1`).
- Wordmark / tagline — see the `build_feature_graphic` function; edit the literal strings and run `./generate.sh graphic`.
- Foreground — `FG` points at `ic_launcher_foreground_img.png`. Replace or re-export that PNG with a higher-resolution version if the 512 × 512 icon looks soft.

## Why it lives here

- The Android app already owns its launcher icon (`android-app/app/src/main/res/drawable/ic_launcher_*`), so store assets naturally live under `android-app/`.
- Artifacts are committed so the Play Console upload in Phase 4 is a pure file-picker flow — no rebuild required to submit.
- `generate.sh` stays the source of truth: if you ever want to refresh the listing, edit constants and rebuild — no Photoshop workflow to remember.

## Known caveats

- Fonts vary by host. The script probes `magick -list font` and picks `Helvetica-Bold` on macOS; on Linux it falls back to `DejaVu-Sans-Bold`. The chosen font prints in the log line so you can verify aesthetic after regeneration.
- If `ic_launcher_foreground_img.png` upscales poorly to 512 × 512, the icon will look soft. Drop a higher-resolution PNG at that path and rerun `./generate.sh icon`.
- Play Store renders the 512 × 512 icon masked by a circle at small sizes. The 66% foreground scale leaves safe padding; change `338x338` in `build_icon` to go full-bleed.
