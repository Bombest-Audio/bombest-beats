# Roadmap: Bombest Beats — Play Store Release

**Milestone:** Play Store Release
**Goal:** Android app published on Google Play Store, installable by anyone
**Created:** 2026-03-30
**Granularity:** Coarse

## Phases

- [ ] **Phase 1: Release Build** - Configure signing, build release AAB/APK, sideload test
- [ ] **Phase 2: Store Assets** - Create and optimize app icon, feature graphic, screenshots
- [ ] **Phase 3: Play Store Listing** - Create listing, complete content rating, add privacy policy
- [ ] **Phase 4: Submission & Publication** - Upload, pass pre-launch review, publish

## Phase Details

### Phase 1: Release Build
**Goal**: Release-signed app builds successfully and runs on a real Pixel device
**Depends on**: Nothing (foundational)
**Requirements**: SIGN-01, SIGN-02, SIGN-03, SIDE-01, SIDE-02
**Success criteria** (what must be TRUE):
  1. Release AAB builds via `./gradlew bundleRelease` with no errors
  2. Release APK builds via `./gradlew assembleRelease` with no errors
  3. APK is signed with the production keystore (verified via `zipalign` and manifest)
  4. Keystore password and alias are loaded from environment variables (never hardcoded in build files)
  5. Sideloaded APK installs cleanly on Pixel device and runs without crashes
  6. All app features work on Pixel: streaming, auth, playback, visualizers, haptics

**Plans**: [`.planning/phases/01-release-build/01-PLAN.md`](phases/01-release-build/01-PLAN.md)

### Phase 2: Store Assets
**Goal**: Visual assets produced and optimized for Play Store listing
**Depends on**: Phase 1
**Requirements**: ASSET-01, ASSET-02, ASSET-03
**Success criteria** (what must be TRUE):
  1. App icon is 512×512 PNG, ≤1MB, meets Play Store design requirements
  2. Feature graphic (1024×500 PNG) created and optimized, visually represents the app
  3. Minimum 2 phone screenshots captured (portrait orientation, ≥320px wide, ≤8MB each)
  4. Screenshots showcase key features: library view, player, visualizer, playlists

**Plans**: TBD

### Phase 3: Play Store Listing
**Goal**: Complete Play Store listing created and ready for submission
**Depends on**: Phase 2
**Requirements**: LIST-01, LIST-02, LIST-03, LIST-04
**Success criteria** (what must be TRUE):
  1. App name, short description (≤80 chars), full description (≤4000 chars) entered in Play Console
  2. App category set to "Music & Audio"
  3. IARC content rating questionnaire completed and rating assigned
  4. Privacy policy published at publicly accessible URL and linked in Play Console
  5. Store assets (icon, feature graphic, screenshots) uploaded to listing

**Plans**: TBD

### Phase 4: Submission & Publication
**Goal**: App submitted to Play Store, passes review, and is publicly available
**Depends on**: Phase 3
**Requirements**: SUBM-01, SUBM-02, SUBM-03
**Success criteria** (what must be TRUE):
  1. AAB uploaded to Play Console production track (or internal test track for first review)
  2. Pre-launch report generated with no critical issues blocking publication
  3. App approved and published to public Play Store
  4. App is discoverable and installable by anyone with the Play Store link

**Plans**: TBD

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| SIGN-01 | Phase 1 | Pending |
| SIGN-02 | Phase 1 | Pending |
| SIGN-03 | Phase 1 | Pending |
| SIDE-01 | Phase 1 | Pending |
| SIDE-02 | Phase 1 | Pending |
| ASSET-01 | Phase 2 | Pending |
| ASSET-02 | Phase 2 | Pending |
| ASSET-03 | Phase 2 | Pending |
| LIST-01 | Phase 3 | Pending |
| LIST-02 | Phase 3 | Pending |
| LIST-03 | Phase 3 | Pending |
| LIST-04 | Phase 3 | Pending |
| SUBM-01 | Phase 4 | Pending |
| SUBM-02 | Phase 4 | Pending |
| SUBM-03 | Phase 4 | Pending |

**Coverage:**
- v1 requirements: 15 total
- Mapped to phases: 15
- Unmapped: 0 ✓

## Progress

| Phase | Status | Completed |
|-------|--------|-----------|
| 1. Release Build | Not started | — |
| 2. Store Assets | Not started | — |
| 3. Play Store Listing | Not started | — |
| 4. Submission & Publication | Not started | — |

---

*Roadmap created: 2026-03-30*
