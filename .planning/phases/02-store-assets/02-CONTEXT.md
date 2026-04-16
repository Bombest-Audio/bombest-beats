# Phase 2: Store Assets - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Produce the three visual assets required by the Play Store listing — app icon (512×512), feature graphic (1024×500), and portrait phone screenshots (min 2, recommend 4) — and the reproducible pipeline that generates them. Phase ends when PNG artifacts exist at `android-app/store-assets/` meeting Play Store format/size/resolution requirements, and a single `generate.sh` can rebuild them.

**Not in scope:** Uploading to Play Console (Phase 4), writing store descriptions (Phase 3), content rating (Phase 3), privacy policy URL (Phase 3).

</domain>

<decisions>
## Implementation Decisions

### Icon Production
- Source the 512×512 Play Store icon from the existing in-app adaptive icon (`drawable/ic_launcher_background.xml` → `#0B0E23` solid + `drawable/ic_launcher_foreground_img.png`) — reuse the existing design for store/device consistency.
- Background color: `#0B0E23` (matches adaptive icon).
- Foreground scale: center at ~66% of canvas (standard safe-zone padding; Play Store renders icons in a masked circle so corner content is clipped).
- Output: `android-app/store-assets/icon-512.png` — committed, reproducible via script. No transparency (Play requires opaque PNG).

### Feature Graphic
- Composition: icon (left) + wordmark "Bombest Beats" (center/right) + tagline (right), on `#0B0E23` background with a thin visualizer-accent stripe for texture.
- Tagline: **"Your personal library, streamed."** — reflects the invite-gated / personal nature documented in PROJECT.md.
- Typography: system sans-serif — 96 px wordmark, 36 px tagline. Readable at Play Store thumbnail dimensions.
- Production: ImageMagick script (`gen-feature-graphic.sh` or consolidated `generate.sh`). Reproducible, diff-reviewable.
- Output: `android-app/store-assets/feature-graphic-1024x500.png`.

### Screenshots
- Count: **4** — one each for Library, Player, Visualizer, Playlists (matches ROADMAP Phase 2 success criterion #4 exactly).
- Capture method: Android emulator + `adb shell screencap`. Scriptable, reproducible, and it uses the same test-user account (`thomas`) that the E2E tests use.
- No device frames, no text captions — Play Store adds its own device-frame chrome, additional framing is redundant and sometimes rejected.
- Use the real test account with actual library content — genuine representation of the app.
- Output: `android-app/store-assets/screenshot-01-library.png` … `04-playlists.png` (portrait ≥320 px wide, ≤8 MB each).

### Asset Pipeline
- Location: `android-app/store-assets/` — colocated with the app, clear ownership.
- Naming: content-hinted filenames with dimensions/order.
- Script: a single `android-app/store-assets/generate.sh` entry point that produces the icon + feature graphic via ImageMagick and orchestrates screenshot capture via adb. Idempotent.
- Commit the generated PNG artifacts to git so Phase 4 (Play Console upload) can proceed without re-running the pipeline. `generate.sh` remains the source of truth for regeneration.

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `android-app/app/src/main/res/drawable/ic_launcher_foreground_img.png` — 1.2 MB foreground bitmap; high enough resolution to render 512×512.
- `android-app/app/src/main/res/drawable/ic_launcher_background.xml` — solid `#0B0E23`.
- `android-app/app/src/main/res/mipmap-*/ic_launcher*.png` — existing launcher icons (device-side reference only; Play Store needs its own 512×512).
- `android-app/visualizer_screenshot.png` — 1.2 MB reference from earlier work (not a store screenshot, but useful for feature-graphic accent inspiration).
- E2E test scaffold committed in `android-app/app/src/androidTest/java/com/bombest/music/{base,pages,flows}/` — page objects can drive the emulator to deterministic screens for reproducible screenshot capture.

### Established Patterns
- App identity colors are in `ic_launcher_background.xml` (navy `#0B0E23`). No `colors.xml` to cross-reference — inline the hex.
- Release signing + release AAB already land via Phase 1 (`bundleRelease`, `assembleRelease`). Screenshots can be captured from a `debug` or `release` install; `debug` is simpler and has the same UI.
- E2E tests have a `BaseE2ETest` with UIAutomator + coordinate-click fallbacks — the same infra can drive screenshot capture by navigating to a target screen, then issuing `adb shell screencap`.

### Integration Points
- `android-app/store-assets/generate.sh` is new — no existing location for store artifacts. Phase 4 (submission) will reference the committed PNG paths when uploading to Play Console.
- `.gitignore` already carries `*.png` under "Audio files" — no, that's `*.wav`/`*.mp3`/etc., not PNG. Safe to commit PNGs under `store-assets/`.

</code_context>

<specifics>
## Specific Ideas

- Use the `ImageMagick` `convert` command (or `magick` on IM7) — already available locally on macOS via Homebrew. No external design tools required.
- Screenshots should reflect: a populated library (many tracks), the player with a real track loaded, a visualizer running, and a playlist with tracks. The E2E test fixtures implicitly depend on this same state (login as `thomas`).
- Tagline "Your personal library, streamed." — 33 chars — fits comfortably at 36 px for 1024×500 graphic.

</specifics>

<deferred>
## Deferred Ideas

- Promotional video for Play listing — v2 requirement OPT-02, not v1.
- Localized store assets (Spanish/French variants) — v2 requirement OPT-01.
- Per-language feature graphic variants — defer until localization phase.
- Dark/light mode screenshot variants — app is dark-mode only today; no variants needed.

</deferred>
