---
phase: 08-get-the-ios-version-up-to-parity-with-android
plan: 02
subsystem: ios-networking-and-error-ui
tags: [ios, networking, failover, error-handling, loadstate, swiftui]
dependency_graph:
  requires: []
  provides: [ios-failover, ios-error-recovery, ios-loadstate]
  affects: [LibraryView, SearchView, PlaylistDetailView, APIService]
tech_stack:
  added: []
  patterns: [LoadState enum, 2-URL retry with cooldown, pattern-matched switch in SwiftUI views]
key_files:
  created: []
  modified:
    - ios-app/Targets/BombestBeats/Sources/Services/APIService.swift
    - ios-app/Targets/BombestBeats/Sources/Models/Models.swift
    - ios-app/Targets/BombestBeats/Sources/ViewModels/LibraryViewModel.swift
    - ios-app/Targets/BombestBeats/Sources/ViewModels/SearchViewModel.swift
    - ios-app/Targets/BombestBeats/Sources/ViewModels/PlaylistDetailViewModel.swift
    - ios-app/Targets/BombestBeats/Sources/Views/LibraryView.swift
    - ios-app/Targets/BombestBeats/Sources/Views/SearchView.swift
    - ios-app/Targets/BombestBeats/Sources/Views/PlaylistDetailView.swift
decisions:
  - "LoadState enum defined in Models.swift (not a separate file) — keeps type visible to all ViewModels without a new import"
  - "APIService resets currentURLIndex=0 on success — matches Android cooldown-reset behavior"
  - "SearchView uses if-case pattern matching instead of switch — avoids nesting the full results list inside a case"
metrics:
  duration: "~5 minutes"
  completed: "2026-04-22T05:51:28Z"
  tasks_completed: 3
  tasks_total: 3
  files_modified: 8
---

# Phase 08 Plan 02: iOS Failover and LoadState Error UI Summary

**One-liner:** 2-URL APIService failover (Cloudflare→EC2 direct, 60s cooldown) plus unified LoadState enum driving ProgressView/error-banner/Retry/ContentUnavailableView across Library, Search, and PlaylistDetail screens.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Add LoadState enum + rewrite APIService with 2-URL retry | 0af8d9b4 | Models.swift, APIService.swift |
| 2 | Upgrade ViewModels to LoadState with retry() | 47bb160b | LibraryViewModel, SearchViewModel, PlaylistDetailViewModel |
| 3 | Upgrade Views to 3-state LoadState UI | 3f93f46e | LibraryView, SearchView, PlaylistDetailView |

## What Was Built

### APIService — 2-URL failover
- `baseURLs` array: `["https://beats.bom.best", "https://beats-aws.bom.best"]`
- `failoverCooldown: TimeInterval = 60.0` — matches Android's `FAILOVER_COOLDOWN_MS = 60000`
- `request()` iterates URLs on network errors; throws immediately on `APIError` (auth/app errors never failover)
- Resets `currentURLIndex = 0` on any successful response

### LoadState enum (Models.swift)
```swift
enum LoadState {
    case idle
    case loading
    case loaded
    case failed(String)
    case empty
}
```

### ViewModels — unified LoadState
All three ViewModels now:
- Publish `@Published var loadState: LoadState = .idle`
- Set `.loading` at fetch start
- Transition to `.loaded`, `.empty`, or `.failed(message)` based on result
- Expose `func retry()` for view-driven retries

LibraryViewModel additionally falls back to disk cache on network failure — if cache has data, state becomes `.loaded`; otherwise `.failed`.

### Views — 3-state UI
- **LibraryView**: `switch viewModel.loadState` guard block above content sections; `.idle/.loading` → ProgressView, `.failed` → error banner + Retry, `.empty` → ContentUnavailableView, `.loaded` → EmptyView (content below renders normally)
- **SearchView**: `if case .loading` / `if case .failed` pattern matching; preserves existing search/results list structure below
- **PlaylistDetailView**: full `switch viewModel.loadState` replacing the old isLoading/tracks.isEmpty chain

All Retry buttons use `.tint(Color("NeonPurple"))` for consistent branding.

## Decisions Made

1. **LoadState in Models.swift** — avoids a new file and keeps it co-located with `Track`, `Playlist`, etc. All existing ViewModels import nothing new.
2. **APIService uses plain class mutation (not actor)** — `currentURLIndex` and `failoverTimestamp` are mutated on the `@MainActor` via `Task { @MainActor in ... }` at all call sites; adding actor isolation would break existing callers.
3. **SearchView uses if-case patterns** — the results list nests at the same level as the loading/error indicators; a switch would require the entire list to be inside a case body, adding unnecessary indentation.

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — all three screens are fully wired to their ViewModels with real LoadState transitions.

## Self-Check: PASSED
