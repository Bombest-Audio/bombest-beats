---
phase: 08-get-the-ios-version-up-to-parity-with-android
plan: 03
subsystem: ios-favorites
tags: [ios, swiftui, favorites, userdefaults, observable]
dependency_graph:
  requires: []
  provides: [IOS-FAVORITES]
  affects: [TrackRow, LibraryView]
tech_stack:
  added: []
  patterns: [ObservableObject singleton, @ObservedObject reactive binding, UserDefaults Set<Int> persistence]
key_files:
  created:
    - ios-app/Targets/BombestBeats/Sources/Services/FavoritesManager.swift
  modified:
    - ios-app/Targets/BombestBeats/Sources/Views/TrackRow.swift
    - ios-app/Targets/BombestBeats/Sources/Views/LibraryView.swift
decisions:
  - "@ObservedObject in TrackRow (not isFavorited() call) so heart icon re-renders reactively on toggle"
  - "heart button nested inside row Button uses .buttonStyle(.plain) to prevent tap event propagation to row action"
  - "favorited count shown as caption under All Songs header — visible context without cluttering the list"
metrics:
  duration: "~8 minutes"
  completed: "2026-04-22"
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 2
---

# Phase 08 Plan 03: iOS Favorites Summary

**One-liner:** FavoritesManager singleton with UserDefaults Set<Int> persistence, heart toggle in TrackRow with @ObservedObject reactivity, favorited count indicator in LibraryView All Songs header.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | Create FavoritesManager.swift singleton | `35ae99f4` | FavoritesManager.swift (new) |
| 2 | Add heart toggle to TrackRow and favorited indicator to LibraryView | `6a4cd784` | TrackRow.swift, LibraryView.swift |

## What Was Built

### FavoritesManager.swift
- `ObservableObject` singleton (`static let shared`) following the same `private init()` pattern as `HapticsManager`
- `@Published private(set) var favoriteIds: Set<Int>` — published so `@ObservedObject` in views triggers re-render; `private(set)` enforces mutation only through `toggle()`
- Loads from `UserDefaults.standard.array(forKey:)` on init; calls `persist()` on every toggle
- Mirrors Android `FavoritesManager.kt` API shape (`toggle`/`isFavorited`) per D-18

### TrackRow.swift
- Added `@ObservedObject private var favorites = FavoritesManager.shared` — critical for reactive re-render
- Replaced `Image(systemName: "ellipsis")` with a `Button` containing `heart.fill`/`heart` icon
- Heart renders in `NeonPurple` when favorited, `.gray` when not
- Heart `Button` uses `.buttonStyle(.plain)` to prevent the tap from propagating to the parent row `Button` (standard SwiftUI nested button pattern)

### LibraryView.swift
- Added `@ObservedObject private var favorites = FavoritesManager.shared`
- "All Songs" header now shows `"\(favorites.favoriteIds.count) favorited"` as a NeonPurple caption when favorites count is non-zero

## Key Decisions

1. `@ObservedObject` in TrackRow rather than a synchronous `isFavorited()` call: the research flagged this explicitly — without `@ObservedObject`, the heart icon won't re-render when toggled (e.g., from another screen). `@Published` on `favoriteIds` + `@ObservedObject` in TrackRow provides automatic re-render.

2. Nested heart `Button` with `.buttonStyle(.plain)`: the TrackRow outer `Button` handles row tap. Nesting another `Button` inside requires `.buttonStyle(.plain)` on the inner button to prevent SwiftUI from intercepting both tap targets simultaneously.

3. `@AppStorage` NOT used: `@AppStorage` handles single `String`/`Bool`/`Int` values, not `Set<Int>`. Manual `UserDefaults.standard.array(forKey:) as? [Int]` + `Array(favoriteIds)` on write is the correct approach.

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — FavoritesManager fully wired. Heart button reads/writes live UserDefaults state on every interaction.

## Self-Check: PASSED

- [x] `ios-app/Targets/BombestBeats/Sources/Services/FavoritesManager.swift` — exists
- [x] `static let shared = FavoritesManager()` — present at line 7
- [x] `@Published private(set) var favoriteIds: Set<Int>` — present at line 11
- [x] `@ObservedObject private var favorites = FavoritesManager.shared` in TrackRow — present at line 7
- [x] `heart.fill` in TrackRow — present at line 39
- [x] `FavoritesManager` in LibraryView — present at line 6
- [x] Commit `35ae99f4` — Task 1
- [x] Commit `6a4cd784` — Task 2
