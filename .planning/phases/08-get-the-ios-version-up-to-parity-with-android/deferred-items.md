# Deferred Items — Phase 08

## Pre-existing Build Errors (out of scope for Plan 04)

**Discovered during:** Plan 04, Task 1 (build verification)

- `ios-app/Targets/BombestBeats/Sources/Views/LibraryView.swift:6`: `cannot find 'FavoritesManager' in scope`
- `ios-app/Targets/BombestBeats/Sources/Views/TrackRow.swift:7`: `cannot find 'FavoritesManager' in scope`

**Status:** These are pre-existing errors from Plan 03 FavoritesManager work. Unrelated to Plan 04 (PlayerView A-B loop). Needs resolution before App Store submission.
