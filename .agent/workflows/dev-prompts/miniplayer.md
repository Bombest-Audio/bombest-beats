---
description: Persistent Mini-Player
---

Persistent Mini Player (Bottom Bar) — Android

Purpose

Add a persistent mini player anchored to the bottom of the screen, visible across the entire app, so users can control playback from anywhere.

This should feel Spotify-like:
	•	Always available
	•	Non-intrusive
	•	Smooth transitions into the full Now Playing screen

⸻

User Experience

Always-visible behavior
	•	Appears when:
	•	there is an active track loaded OR playback has started
	•	Hides when:
	•	no track/queue exists
	•	user explicitly stops playback and clears queue (if supported)

Mini player contents
	•	Left: album art thumbnail (rounded)
	•	Center:
	•	Track title (1 line, ellipsized)
	•	Artist name or context subtitle (playlist name) (optional)
	•	Right:
	•	Play/Pause toggle
	•	Next button (optional, but recommended)

Interaction
	•	Tap anywhere on the mini player → expands to Now Playing screen
	•	Swipe left/right (optional) → skip track / go back (gesture-based)
	•	Long-press (optional) → opens quick actions sheet:
	•	Add to favorites
	•	Add to playlist
	•	Play next
	•	Add to queue

Visual style
	•	Uses current theme:
	•	Graffiti: subtle texture + gradient accent line