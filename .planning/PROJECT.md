# Bombest Beats

## What This Is

Bombest Beats is a personal music streaming app for Android (and web/iOS) that streams from an S3-backed library. It has passkey auth, playlist management, haptic groove feedback, custom visualizers (Graffiti/Oscilloscope), and background playback via Media3. The current milestone is shipping the Android app to the Google Play Store.

## Core Value

The Android app is available on the public Play Store — installable, signed, and working on a real Pixel device.

## Requirements

### Validated

- ✓ Streams music from S3 library via `/stream/<id>` with Range request support — existing
- ✓ JWT + passkey (WebAuthn) authentication — existing
- ✓ Playlist management: create, edit, delete, reorder, share via token — existing
- ✓ Media3/ExoPlayer background playback with Android Auto support — existing
- ✓ Haptic Groove Engine (kick/snare/hi-hat frequency mapping) — existing
- ✓ Custom visualizers: Graffiti (spray paint), Oscilloscope — existing
- ✓ Download/offline caching (1GB LRU streaming + persistent downloads) — existing
- ✓ Admin upload functionality with S3 presigned URL pipeline — existing
- ✓ Metrics tracking with batched play events — existing
- ✓ A-B loop with beat-grid snapping — existing
- ✓ Automatic failover (Cloudflare → direct EC2) — existing
- ✓ CI builds `assembleDebug` successfully — existing

### Active

- [ ] Release signing configured via existing keystore in `build.gradle.kts`
- [ ] Release AAB (Android App Bundle) built successfully
- [ ] Sideload-ready APK installed on Pixel device
- [ ] Play Store listing created (name, description, category, content rating)
- [ ] Store assets produced (icon, feature graphic, phone screenshots)
- [ ] Privacy policy published at accessible URL
- [ ] App uploaded to Play Console and passes pre-launch review
- [ ] App published to public Play Store

### Out of Scope

- iOS App Store submission — separate milestone, iOS app less complete
- Web PWA distribution — already deployed at bom.best/beats
- Backend refactoring — known tech debt, not blocking Play Store
- Automated CI/CD for Play Store uploads — manual releases sufficient for now
- Google Play Games / leaderboards — not a games app

## Context

- Android app: `android-app/` — Kotlin 1.9.22, Jetpack Compose, compile/targetSdk 34, minSdk 24
- User has an existing release keystore (.jks/.keystore file) — needs to be wired into signing config
- User has a Google Play developer account ($25 one-time fee already paid) — no app listing yet
- App targets Pixel devices (developed on Pixel hardware)
- App is a personal music library app, not a general-purpose streaming service
- Backend runs at `beats.bom.best` (Cloudflare → EC2 → Flask → S3)
- The app requires a login with invite code — not truly open to anyone without an account
- Store listing must accurately represent this (invite-gated or personal use)

## Constraints

- **Security**: Keystore password/credentials must NEVER be committed to git — use local env vars or CI secrets
- **Play Policy**: App must have a privacy policy URL before publishing
- **Target SDK**: Must target API 33+ for new app submissions (currently targeting 34 ✓)
- **Signing**: Release builds must be signed with the same keystore permanently — losing it means losing the ability to update the app
- **Content Rating**: Music streaming app needs IARC content rating questionnaire

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| AAB over APK for Play Store | Google requires AAB for new apps since Aug 2021 | — Pending |
| Manual releases (no CI/CD) | Simple workflow, personal app, infrequent updates | — Pending |
| Public listing over internal-only | User wants fully public Play Store presence | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-03-29 after initialization*
