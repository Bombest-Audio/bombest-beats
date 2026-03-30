# Requirements: Bombest Beats — Play Store Release

**Defined:** 2026-03-30
**Core Value:** The Android app is available on the public Play Store — installable, signed, and working on a real Pixel device.

## v1 Requirements

### Signing

- [ ] **SIGN-01**: Release signing is configured in `build.gradle.kts` using the existing keystore file
- [ ] **SIGN-02**: Keystore credentials are loaded from environment variables (never committed to git)
- [ ] **SIGN-03**: Release AAB (Android App Bundle) builds successfully via `./gradlew bundleRelease`

### Sideload

- [ ] **SIDE-01**: Release APK builds successfully via `./gradlew assembleRelease`
- [ ] **SIDE-02**: Release APK installs and runs correctly on a Pixel device

### Store Assets

- [ ] **ASSET-01**: App icon meets Play Store requirements (512×512 PNG, ≤1MB)
- [ ] **ASSET-02**: Feature graphic produced (1024×500 PNG)
- [ ] **ASSET-03**: Phone screenshots produced (minimum 2, portrait, ≥320px wide)

### Play Listing

- [ ] **LIST-01**: Play Store listing created with app name, short description (80 chars), full description (4000 chars)
- [ ] **LIST-02**: App category set (Music & Audio)
- [ ] **LIST-03**: IARC content rating questionnaire completed
- [ ] **LIST-04**: Privacy policy published at a publicly accessible URL

### Submission

- [ ] **SUBM-01**: AAB uploaded to Play Console (internal test track or production)
- [ ] **SUBM-02**: App passes Play Console pre-launch report (no critical issues)
- [ ] **SUBM-03**: App published to public Play Store and installable by anyone with the link

## v2 Requirements

### Distribution

- **DIST-01**: Automated CI/CD uploads new AABs to Play Console on release tag
- **DIST-02**: Staged rollout percentage configured (e.g., 10% → 100%)

### Store Optimization

- **OPT-01**: Localized store listing (Spanish, French)
- **OPT-02**: Promotional video added to Play listing

## Out of Scope

| Feature | Reason |
|---------|--------|
| iOS App Store submission | Separate milestone — iOS app less complete |
| Web PWA distribution | Already deployed at bom.best/beats |
| Backend refactoring | Known tech debt, not blocking Play Store |
| Google Play Games / leaderboards | Not a games app |
| Automated CI/CD for Play uploads | Manual releases sufficient for personal app |

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

---
*Requirements defined: 2026-03-30*
*Last updated: 2026-03-30 after initial definition*
