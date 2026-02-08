---
description: Spray-Paint Circular Progress Meter (Android)
---

# Spray-Paint Circular Progress Meter (Android)

## Purpose

Replace the standard circular playback progress indicator with a **spray-painted, animated progress ring** that looks like it’s being painted in real time as the track progresses.

This is a signature “Graffiti Theme” interaction: it should feel alive, tactile, and street-authentic while remaining **precise and performance-safe**.

---

## User Experience

### Visual behavior

* Progress advances around a circular path in sync with playback time.
* The leading edge looks like an active spray can:

  * soft overspray mist
  * uneven paint density
  * micro jitter in edge shape
* The painted region remains behind as a textured stroke (not a clean vector arc).
* Occasional subtle drips can appear (rarely) on slower passages, but **never distracting**.

### Interaction behavior

* Tapping the ring can toggle a “scrub mode” (optional; if already implemented elsewhere, integrate).
* In scrub mode:

  * the ring highlights
  * dragging rotates the position
  * the spray effect can preview lightly without fully repainting

### Theme behavior

* The spray meter is enabled only in **Graffiti Theme**.
* Other themes provide their own meter (e.g., Studio Dust analog dial).

---

## Design Requirements

### Shape

* Circular ring with a configurable:

  * radius
  * stroke width
  * padding

### Texture & Style

* Painted stroke should have:

  * uneven edges
  * occasional speckling
  * gradient paint (orange → magenta → purple)

### Timing

* Progress must be **frame-accurate** to playback time.
* Visual updates should feel smooth (60fps on modern devices).

### Subtlety

* Must not look like a fireworks show.
* The “spray” should read as authentic paint texture, not particle chaos.

---

## Android Implementation Options

### Option A (Recommended): Jetpack Compose + Canvas

* Use `Canvas` and custom drawing.
* Render progress arc segments with a custom brush effect.

### Option B: View-based custom drawing

* Use `View.onDraw()` with a cached bitmap for painted segments.

**Choose Option A if UI is Compose-first.**

---

## Architecture

```
SprayPaintProgressMeter
 ├── ProgressStateAdapter (maps playback position → 0..1)
 ├── SprayStrokeRenderer (draws textured arc)
 ├── SprayLeadingEdgeRenderer (mist + active spray)
 ├── PaintTextureCache (bitmaps/noise reuse)
 └── InteractionController (optional scrub support)
```

---

## Rendering Strategy

### Key idea

Avoid re-rendering an entire high-res texture every frame.

#### Use layered rendering:

1. **Base ring** (very subtle, dark outline)
2. **Painted arc** (cached bitmap layer updated periodically)
3. **Leading edge spray** (lightweight per-frame)
4. **Mist/overspray** (per-frame but minimal)

### Caching rules

* Painted arc layer updates when progress changes by a minimum delta (e.g., 0.25% or time-based).
* Leading edge updates every frame.
* Noise textures are pre-generated and reused.

---

## Spray Effect Details

### Painted arc texture

* Create a brush-like stroke using:

  * randomized alpha jitter
  * edge noise mask
  * speckle dots

### Leading edge “spray can” effect

* Draw a short “spray plume” tangent to arc direction.
* Add a cluster of tiny particles (10–30) around the plume.
* Use a soft blur or alpha falloff.

### Drips (rare)

* Trigger drip only when:

  * playback is paused
  * or tempo is slow
  * and only once every ~10–20 seconds max
* Drip length animates downward slightly.

---

## Color Strategy

### Brand palette

* Background: `#0B0E23`
* Paint gradient: Orange → Magenta → Purple

### Gradient mapping

* Map gradient along the arc:

  * start of arc = warm orange
  * midpoint = hot pink
  * end/edge = purple

---

## Playback Integration

### Inputs

* `durationMs`
* `positionMs`
* `isPlaying`

### Behavior

* When playing: continuous progress.
* When paused: progress static; optional gentle “paint settling” effect.
* When seeking: ring updates instantly, but spray effect should not “repaint entire history” aggressively.

---

## Accessibility

* Ensure sufficient contrast between ring and background.
* Provide a fallback progress indicator when:

  * reduced motion enabled
  * battery saver enabled
* Expose semantic progress to accessibility services.

---

## Performance & Quality

### Targets

* 60fps on Pixel-class devices
* < 2ms per frame spent in drawing

### Safeguards

* Degrade particle count on low-end devices.
* Disable blur if expensive.
* Use cached bitmaps for textures.

---

## Testing

### Visual QA

* Validate:

  * smoothness
  * no flicker
  * consistent stroke thickness
  * color accuracy

### Behavior QA

* Progress matches audio playback position within a tight tolerance.
* Scrubbing (if enabled) correctly seeks.

---

## Acceptance Criteria

* Looks like spray paint being applied in real time.
* Reads clearly as a progress indicator.
* Does not tank performance.
* Integrates cleanly into the Graffiti Theme.

---

## Implementation Notes for AI Agent

* Keep all spray logic inside a dedicated renderer.
* Parameterize everything (stroke width, particle count, jitter, colors).
* Add debug toggles to visualize layers.
* Comment extensively and keep code readable.

Build it like it’s going to be demoed in a “Modern Android UI” talk.
