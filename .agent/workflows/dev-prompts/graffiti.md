---
description: Graffiti Waveform Visualizer — Workflow (Android)
---

# Graffiti Waveform Visualizer — Workflow (Android)

## Purpose

Implement a **Graffiti Waveform Visualizer** that looks like graffiti being spray-painted in real time as the waveform dances. This visualizer should feel street-authentic, energetic, and organic, while remaining performant and readable.

This is designed to be a signature feature that showcases Android’s modern UI stack and pairs perfectly with:

* Graffiti Theme
* Spray-Paint Progress Meter
* RuntimeShader / RenderEffect FX Engine
* Haptic Groove Engine

---

## User Experience Goals

### What it should look like

* The waveform is not a clean vector line.
* Each peak feels like:

  * a quick spray stroke
  * a burst of mist
  * paint splatter on strong transients
* The waveform should feel **alive** and **hand-made**, not symmetrical or sterile.
* The visualizer should remain tasteful and not chaotic.

### What it should feel like

* Audio energy drives paint behavior:

  * louder = thicker spray + brighter paint
  * quieter = thinner strokes + lingering mist
* Replays should have subtle randomness so the animation never looks identical.

---

## Inputs & Dependencies

### Required inputs

* `bands` or `fft` data from your existing audio visualizer pipeline
* `amplitudeRms` (or an equivalent energy measure)
* `isPlaying`
* `timestamp` / frame time

### Optional inputs (nice to have)

* transient markers (kick/snare hits)
* BPM estimate

### Dependencies

* Graffiti Theme palette
* FX Engine presets (recommended):

  * `SprayDiffusion`
  * `WallGrain`
  * `MistBloom` (subtle)

---

## Rendering Approach

### Preferred: Jetpack Compose Canvas

* Build the visualizer as a dedicated composable:

  * `GraffitiWaveformVisualizer()`
* Render on `Canvas` using:

  * layered strokes
  * textured alpha
  * controlled particle spray

### Fallback: View-based rendering

* Only if the app is not Compose-first.

---

## Core Concept: Layered Visualizer

Render as layers (cheap → expensive):

1. **Base silhouette**

   * a dark, subtle underlying waveform “shadow” for readability
2. **Spray stroke layer**

   * main waveform stroke with uneven edges
3. **Mist layer (leading edge)**

   * low-opacity spray cloud around the most active parts
4. **Splatter bursts (rare)**

   * triggered by transients
5. **Texture overlay**

   * wall grain + subtle noise

Keep 1–2 always on, and degrade the rest based on performance settings.

---

## Workflow Steps

### Step 1 — Define the Visualizer Component Contract

Create a small API so the visualizer is easy to swap per theme.

**Inputs**

* `visualizerData`: (FFT/bands)
* `playbackState`: (isPlaying, position)
* `intensity`: user setting (Low/Med/High)
* `themePalette`

**Outputs**

* No state output; pure rendering.

Acceptance check:

* The composable can be dropped into Now Playing screen without new dependencies.

---

### Step 2 — Normalize Audio Data

Convert raw FFT/band values into stable 0..1 values.

Rules:

* Apply smoothing (EMA) to avoid jitter.
* Apply gain curve:

  * emphasize mid/high bands for spray detail
  * use low bands for thickness/weight

Acceptance check:

* Waveform movement is responsive but not shaky.

---

### Step 3 — Build the Spray Stroke Renderer

Render a waveform that looks painted.

Techniques:

* Draw multiple strokes per frame:

  * one main stroke
  * 1–2 offset “ghost strokes” with lower alpha
* Use edge jitter:

  * vary stroke width slightly per segment
  * randomize alpha per segment

Color:

* paint gradient orange → magenta → purple
* map gradient along X axis OR by energy

Acceptance check:

* Even with all particles disabled, the waveform still reads as graffiti.

---

### Step 4 — Add Mist / Overspray

Add a soft mist around the most energetic parts.

Rules:

* Mist only appears when amplitude exceeds a threshold.
* Mist should be subtle and not cover the entire screen.
* Use small particle clusters near peaks.

Acceptance check:

* Mist enhances peaks; it doesn’t create a fog.

---

### Step 5 — Add Splatter Bursts (Transient-driven)

On strong transients:

* spawn a short-lived splatter burst
* 5–15 droplets max
* fade out in < 300ms

Trigger sources:

* transient markers if available
* otherwise, detect sudden RMS deltas

Acceptance check:

* Splatter happens rarely enough to feel special.

---

### Step 6 — Texture Overlay (Wall Grain)

Apply a subtle wall grain overlay.

Implementation options:

* static bitmap noise
* or FX Engine `WallGrain` shader (preferred)

Rules:

* keep opacity low
* do not reduce waveform readability

Acceptance check:

* Waveform remains legible.

---

### Step 7 — FX Engine Integration

If FX engine exists:

* wrap visualizer output with:

  * `SprayDiffusion` (soft bleed)
  * optional `MistBloom` (subtle)

Fallback:

* if FX engine off or unsupported, keep visualizer clean.

Acceptance check:

* The visualizer still looks good without shaders.

---

### Step 8 — Performance Budgeting

Visualizer must scale across devices.

Controls:

* `qualityTier`: Low/Med/High
* auto-detect tier based on device

Degrade order:

1. reduce particle counts
2. disable splatter
3. disable mist
4. reduce stroke segments
5. disable diffusion shader

Acceptance check:

* 60fps on Pixel-class
* no jank on midrange

---

### Step 9 — Accessibility / Settings

Respect user preferences:

* Reduce motion → turn off mist/splatter
* Battery saver → drop to low tier

Add settings:

* Visualizer style: `Graffiti` (default in Graffiti theme)
* Intensity: Low/Med/High

Acceptance check:

* The app remains comfortable for motion-sensitive users.

---

## Suggested File/Module Layout

```
visualizer/
  GraffitiWaveformVisualizer.kt
  renderer/
    SprayStrokeRenderer.kt
    MistRenderer.kt
    SplatterBurstSystem.kt
  model/
    VisualizerFrame.kt
    VisualizerSmoother.kt
  perf/
    VisualizerQualityTier.kt
```

---

## Testing & QA Workflow

### Visual QA checklist

* Does it read as graffiti?
* Does it match the app palette?
* Does it remain legible on small screens?
* Does it look good when paused?

### Performance QA checklist

* Pixel device: 60fps
* Midrange Android: stab
