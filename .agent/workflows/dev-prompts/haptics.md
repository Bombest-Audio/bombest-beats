---
description: Haptic Groove Engine (Android)
---

Haptic Groove Engine (Android)

Purpose

Create a musically expressive, beat-synced haptics system that lets users feel the groove. This feature showcases Android’s advanced haptic stack (especially Pixel devices) and turns vibration into a rhythmic instrument rather than a notification gimmick.

This is a signature Android-only feature designed to attract Google/Pixel attention.

⸻

User Experience

What the user feels
	•	Kick drums → deep, soft thump
	•	Snares / claps → sharp, short tap
	•	Hi-hats / shakers → ultra-light micro-ticks
	•	Bass drops → brief low-frequency rumble

Haptics are:
	•	Subtle by default
	•	Musically timed (never random)
	•	Never overpowering or battery-draining

User controls
	•	Toggle: Feel the Beat
	•	Intensity slider: Low / Medium / High
	•	Optional per-device warning (first enable)

⸻

Android APIs Used (Key Selling Point)
	•	VibratorManager
	•	Vibrator
	•	VibrationEffect
	•	VibrationEffect.Composition
	•	Device capability detection via Vibrator.hasAmplitudeControl()

Pixel devices should automatically unlock richer patterns.

⸻

Architecture Overview

Components

HapticGrooveEngine
 ├── HapticPatternLibrary
 ├── AudioAnalysisBridge
 ├── HapticScheduler
 ├── DeviceCapabilityResolver
 └── UserPreferenceController


⸻

Audio → Haptics Mapping

Input Signals

The engine should not re-decode audio. Instead, hook into existing playback analysis:
	•	RMS / amplitude
	•	Beat / transient markers (if available)
	•	Frequency band energy (low / mid / high)

If beat detection is unavailable:
	•	Fallback to amplitude + envelope follower

⸻

Haptic Pattern Library

Define reusable vibration motifs:

Pattern	Description
KICK_THUMP	Low-frequency, medium duration
SNARE_TAP	Sharp, fast pulse
HAT_TICK	Very short micro pulse
BASS_RUMBLE	Low-amplitude sustained vibration

Patterns should be composable using VibrationEffect.Composition.

⸻

Scheduling Strategy

Timing
	•	Haptics must be frame-aligned with playback
	•	Use audio clock timestamps, not UI frame timing
	•	Avoid UI thread usage

Rules
	•	Never overlap patterns aggressively
	•	Drop low-priority haptics if system is busy
	•	Automatically disable during calls / alarms

⸻

Battery & Performance Safeguards
	•	Disable when screen is off (optional setting)
	•	Reduce intensity on low battery
	•	Cap vibration frequency
	•	Automatically pause if device overheats

⸻

Accessibility Considerations
	•	Feature must work independently of visuals
	•	Useful for visually impaired users
	•	Respect system haptics accessibility settings

⸻

Edge Cases
	•	Bluetooth headphones connected → still allow haptics
	•	Silent / Do Not Disturb → respect system rules
	•	Tablet devices → scale intensity appropriately

⸻

Testing Strategy

Manual
	•	Pixel phone (primary)
	•	Non-Pixel Android phone
	•	Wired headphones
	•	Bluetooth headphones

Automated
	•	Pattern generation unit tests
	•	Preference persistence tests

⸻

Acceptance Criteria
	•	Haptics feel rhythmically correct
	•	No noticeable audio delay
	•	No crashes on unsupported devices
	•	Clean opt-in UX

⸻

Why Google Cares
	•	Uses advanced Android haptics APIs
	•	Music-driven, not notification-driven
	•	Pixel hardware showcase
	•	Accessibility positive

This feature should feel like something Android was meant to do.

⸻

Future Extensions
	•	Haptics tied to BPM
	•	Theme-specific haptic styles
	•	Haptic-only playback mode
	•	Developer haptic presets

⸻

Implementation Notes for AI Agent
	•	Keep logic modular
	•	Avoid hard-coding patterns
	•	Comment extensively
	•	Favor readability over cleverness

Build this as if it will be demoed on a Pixel stage.
