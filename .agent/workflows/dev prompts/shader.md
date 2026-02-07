---
description: RuntimeShader / RenderEffect FX Engine (Android GPU FX)
---

# RuntimeShader / RenderEffect FX Engine (Android GPU FX)

## Purpose

Create a reusable **GPU-accelerated visual effects engine** that can apply modern Android graphics effects to album art, backgrounds, the mini player, and visualizers.

This feature showcases cutting-edge Android rendering:

* `RenderEffect` for blur / chainable effects
* `RuntimeShader` (AGSL) for custom film grain, tape warble, bloom-ish glow, spray diffusion, etc.

Goal: make the app feel *premium*, *alive*, and distinctly Android.

---

## Key Outcomes

* A single FX system that themes can opt into.
* Real-time effects that react to:

  * playback state
  * amplitude/energy
  * user settings (motion reduction, battery)
* Safe performance: degrade gracefully.

---

## UX Use Cases

### 1) Album Art Backdrop (Now Playing)

* Blur the album art behind controls.
* Add subtle grain.
* Apply gentle vignette.
* Optional tape wobble in Studio Dust theme.

### 2) Mini Player

* Subtle glow edge when playing.
* Small reactive shimmer on strong beats.

### 3) Graffiti Theme Visuals

* Spray diffusion/soft bleed on paint strokes.
* Grit texture overlay.

### 4) Visualizer Enhancements

* Glow + slight blur for oscilloscope line.
* Noise texture to avoid harsh digital edges.

---

## Platform Requirements

### API Levels

* `RenderEffect` is available from API 31 (Android 12).
* Provide fallbacks for older devices:

  * no blur
  * bitmap-based grain (static)

### Hardware

* Must detect and adapt to GPU performance.
* Avoid heavy fragment shader work on low-end devices.

---

## Architecture

```
FxEngine
 ├── FxPresetRegistry
 ├── FxPipeline
 │    ├── RenderEffectChain
 │    └── ShaderPasses (RuntimeShader)
 ├── FxStateAdapter (maps playback/theme → params)
 ├── CapabilityResolver
 ├── PerformanceBudgeter
 └── UserSettings (reduce motion, battery saver)

FxTargets
 ├── AlbumArtBackdropTarget
 ├── MiniPlayerTarget
 ├── VisualizerTarget
 └── ThemeOverlayTarget
```

---

## Effects Catalog (Initial Presets)

### Studio Dust Presets

1. **TapeGlow**

   * subtle warm glow on highlights
   * low opacity
2. **FilmGrain**

   * animated grain (very subtle)
3. **TapeWarble**

   * mild UV distortion that slowly shifts
4. **Vignette**

   * soft dark edges

### Graffiti Presets

1. **SprayDiffusion**

   * soft edge bleed
2. **WallGrain**

   * gritty texture overlay
3. **MistBloom (subtle)**

   * lightweight blur+brighten for paint mist

---

## Technical Implementation (Compose)

### Target rendering pattern

* Apply effects using Compose graphics layers:

  * `Modifier.graphicsLayer { renderEffect = ... }`
* For shader passes:

  * Use `RuntimeShader` with AGSL
  * Feed uniforms: time, intensity, seed, etc.

### Effect application points

* Album art background layer
* Visualizer canvas output layer
* Optional overlay layers

---

## RuntimeShader (AGSL) Requirements

### Uniforms to standardize

* `u_time` (seconds)
* `u_intensity` (0..1)
* `u_resolution` (vec2)
* `u_seed` (float)
* `u_colorTint` (vec4)

### Shader rules

* Keep ALU low.
* Prefer simple noise (hash-based) over expensive perlin.
* No huge loops.

---

## RenderEffect Chain Requirements

### Common chain

* blur → color filter → compositing

### Fallback

If `RenderEffect` unsupported:

* disable blur
* use static gradient overlay

---

## Performance & Degradation

### PerformanceBudgeter

* Decide per device & runtime:

  * particle count (if any)
  * shader frequency
  * blur radius
  * whether shaders run at all

### Rules

* Battery saver ON → disable animated shaders
* Reduce motion ON → disable warble, reduce animation rates
* Low-end GPU → keep only static overlays

### Targets

* 60fps on Pixel-class devices
* No more than ~2–4ms total GPU overhead for FX

---

## Playback Reactivity

### Inputs

* `isPlaying`
* `amplitudeRms`
* `transientHits` (optional)

### Mappings

* Strong transients briefly increase glow.
* When paused, effects “settle” (reduce intensity).

---

## Developer Ergonomics

### API

Provide a clean API so features can request presets.

Example interface:

* `FxEngine.applyPreset(target, preset, state)`
* `FxEngine.updateState(state)`

Presets must be:

* named
* parameterized
* testable

---

## Testing Strategy

### Unit tests

* CapabilityResolver decisions
* PerformanceBudgeter policies
* Preset selection per theme

### Visual QA

* Pixel phone test pass
* low-end Android test pass
* verify no banding, no flicker

---

## Acceptance Criteria

* Effects improve perceived quality without harming usability.
* Blur and grain look intentional, not cheap.
* Graffiti diffusion reads as paint.
* Engine degrades gracefully on older devices.
* Clean theme integration.

---

## Future Extensions

* Per-track “mood” FX mapping
* Palette extraction from album art to drive tint
* Optional OpenGL/Vulkan path (not required)

---

## Implementation Notes for AI Agent

* Build the engine as a reusable module.
* Keep shaders small and documented.
* Ship with a small set of presets first.
* Avoid introducing heavy dependencies.
* Comment tradeoffs and fallback logic.

Build this like it’s going into an Android Dev Summit demo.
