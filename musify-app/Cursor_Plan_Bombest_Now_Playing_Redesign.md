# Cursor Plan — Bombest Beats Full Screen Player Redesign (Graffiti Theme / Android-first)

> **File purpose:** This is a Cursor/AI-agent execution plan for redesigning the **Full Screen Now Playing** player to a premium, Android-native, graffiti-forward experience using modern Android technologies.

---

## 0) Mission

Redesign the **Full Screen Player** so it feels like:
- a fresh graffiti piece coming alive on a wall
- a living instrument, not a control panel
- unmistakably Android-first (not a Spotify clone)

**Non-negotiable:** The **graffiti bomb logo** is the hero kinetic object.

---

## 1) Constraints & Principles

### Visual identity (strict)
- Base: deep navy/asphalt black
- Accents: **orange → magenta → violet**
- Texture: subtle grain, mist, spray diffusion
- No generic glassmorphism, no glossy plastic UI

### UX hierarchy
1) Art / visual energy  
2) Track identity  
3) Transport controls  
4) Secondary actions

### Motion philosophy
- State-driven, continuous, rhythmic
- No fake “loading screen theater”
- Respect reduced motion + battery saver

### Performance / quality bar
- 60fps on Pixel-class devices
- Degrade gracefully on low-end devices
- No jank while playing + while dragging/scrubbing

---

## 2) Tech Targets (Android Flex)

Use:
- Jetpack Compose + Material 3 (tokens + motion)
- Canvas drawing (CustomDrawScope)
- RuntimeShader / RenderEffect (API 31+), fallbacks for older devices
- Haptics (capability-aware, opt-in)
- Media3 playback state integration (single source of truth)

---

## 3) Deliverables

### A) UX + UI
- New **Now Playing** layout
- Persistent mini player compatibility (does not conflict)
- Clean control hierarchy and one-hand reach

### B) Motion + Visual FX
- Spray-paint circular progress meter integration (if available) or placeholder
- Graffiti waveform/energy visualizer integration point
- Optional: subtle shader-based grain/glow for hero region

### C) Engineering
- Composables + state architecture
- Feature flags for experimental effects
- Tests for state + rendering boundaries
- Documentation and tuning knobs

---

## 4) Screen Redesign Spec (What to Build)

### 4.1 Layout structure
Implement a single `NowPlayingScreen()` composable composed of:

- **Top bar**
  - Back/Collapse
  - Track menu (•••)
  - Optional route chip (audio output)

- **Hero region**
  - Large circular album art (bomb logo is default)
  - Graffiti ring “frame” that can host progress spray
  - Subtle animated glow / mist on play

- **Track metadata**
  - Title (strong weight)
  - Artist (muted)
  - Optional vibe chips (future)

- **Transport row**
  - Shuffle / Prev / PlayPause (hero) / Next / Repeat
  - Large touch targets, tight visual density

- **Secondary actions**
  - Like/favorite
  - Add to playlist
  - Share
  - Queue

### 4.2 Progress UI (spray paint)
Replace current seek bar behavior with:
- Circular progress around album art
- Progress “paints on” while playing (imperfections welcome)
- On scrub: ring becomes more defined + haptic ticks

Fallback:
- If spray shader disabled, show clean ring with gradient stroke

### 4.3 Visualizer
- Keep it tasteful: energy-driven line or mist pulses
- Should never fight the hero art
- Respect reduced motion

---

## 5) Architecture & State

### 5.1 Single playback source of truth
Create/confirm a central playback store (e.g. `PlaybackStateStore`):
- track
- position
- duration
- isPlaying
- shuffle/repeat
- queue snapshot

`NowPlayingScreen` must be purely reactive to this state.

### 5.2 Feature flags
Add `LabsFlags` (or equivalent):
- enableShaders
- enableSprayProgress
- enableVisualizer
- enableHapticsScrub

These gate expensive features and help safe rollout.

---

## 6) Implementation Plan (Cursor Tasks)

### Task 1 — Create new Now Playing UI package
- `ui/nowplaying/NowPlayingScreen.kt`
- `ui/nowplaying/components/*`

### Task 2 — Implement layout + typography
- Use Material 3 typography as baseline
- Add theme tokens for:
  - background
  - accent gradient
  - icon alpha levels
- Ensure correct insets (status/nav bars)

### Task 3 — Create hero album art component
- `GraffitiHeroArt()`:
  - circular album art
  - ring frame
  - optional subtle glow layer

### Task 4 — Implement circular progress ring
- `SprayProgressRing()`:
  - takes position/duration
  - renders arc using Canvas
  - adds jitter/noise for spray feel (parameterized)
  - supports scrub preview

### Task 5 — Scrubbing interactions
- Drag gesture on ring:
  - converts angle → time
  - preview position label
  - commit seek on release
  - optional haptic tick cadence

### Task 6 — Optional shader pass (API 31+)
- If enabled, apply RenderEffect / RuntimeShader to:
  - subtle grain overlay
  - gentle diffusion at spray edge
Fallback:
- disable shader and use static overlay

### Task 7 — Motion polish
- Use `AnimatedVisibility`, `animateFloatAsState`, `spring/tween`
- Play/Pause press: scale + subtle glow burst
- Pause: visuals settle

### Task 8 — Accessibility
- Content descriptions
- Large touch targets
- Reduce motion setting honored
- Contrast checks

### Task 9 — Performance
- Avoid allocations per frame
- Cache paths/segments where possible
- Budget particle/mist effects
- Disable effects when in battery saver (if accessible)

### Task 10 — Tests + QA hooks
- Unit tests for:
  - angle/time mapping
  - progress calculations
- Debug overlay flag:
  - show tiers
  - show scrub position
  - show feature flags active

---

## 7) Acceptance Criteria

- Launching Now Playing shows:
  - hero art, readable metadata, transport controls
- Circular progress works and scrubs reliably
- Animations are smooth and not distracting
- Feature flags correctly disable expensive effects
- Works on API 26+; shaders only on API 31+ with fallback
- No jank in normal playback

---

## 8) “Wow” Enhancers (Optional, behind flags)

- Audio output route chip + picker
- Haptic groove pulses on beats (opt-in)
- On-device vibe detection chips (opt-in)
- Live wallpaper tie-in (future)

---

## 9) Output Required from Cursor

At end, provide:
- Files changed/added list
- Build status
- Screenshots (if available)
- Notes on how to tweak:
  - spray intensity
  - ring thickness
  - animation timings
  - performance tier thresholds

---

**This redesign should feel like a Pixel feature reel moment.**
