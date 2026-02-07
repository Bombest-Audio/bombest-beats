# Espresso Test Framework for Bombest Beats

## Overview

This directory contains Espresso UI tests for the Bombest Beats Android app, following the Page Object Model (POM) pattern with method chaining and the Arrange-Act-Assert (AAA) pattern.

## Architecture

### Page Object Model

The test framework uses a Page Object Model where each screen/feature has a corresponding Page class:

- **BombestBeatsPage**: Main entry point, handles app launch and navigation
- **LibraryPage**: Library screen interactions (downloads, sync, track list)
- **PlayerPage**: Player screen interactions (play, pause, next, previous)

### Method Chaining

All Page methods return `this` (or the next appropriate Page object) to enable fluent method chaining:

```kotlin
launchBombestBeats()
    .ensureLibraryLoads()
    .tapSongAtIndex(2)
    .enterFullScreenPlayer()
    .tapNext()
    .verifyTrackAdvances()
```

### Arrange-Act-Assert Pattern

Tests follow the AAA pattern:
- **Arrange**: Set up test conditions (launch app, navigate to screen)
- **Act**: Perform actions (tap buttons, toggle switches)
- **Assert**: Verify expected outcomes (verify tracks visible, verify download complete)

## Test Structure

```
android/app/src/androidTest/kotlin/com/bombest/spotube/
├── pages/
│   ├── BombestBeatsPage.kt      # Main page object
│   ├── LibraryPage.kt           # Library interactions
│   └── PlayerPage.kt            # Player interactions
├── scenarios/
│   ├── LibraryLoadingScenarios.kt    # Phase 1 tests
│   ├── StreamingCacheScenarios.kt     # Phase 2 tests
│   ├── DownloadSyncScenarios.kt       # Phase 3 tests
│   └── PlayerScenarios.kt             # Player tests
└── utils/
    └── TestHelpers.kt           # Common utilities
```

## Running Tests

### Run all tests:
```bash
./gradlew connectedAndroidTest
```

### Run specific test class:
```bash
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bombest.spotube.scenarios.LibraryLoadingScenarios
```

### Run specific test method:
```bash
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bombest.spotube.scenarios.LibraryLoadingScenarios#testLibraryLoadsImmediatelyOnLaunch
```

## Flutter Integration Notes

Since Bombest Beats is a Flutter app, testing Flutter widgets with native Espresso requires special considerations:

1. **Accessibility Labels**: Flutter widgets should have semantic labels set for testability
2. **FlutterDriver**: Consider using FlutterDriver for widget-level testing
3. **Hybrid Approach**: Use Espresso for Android-specific features and FlutterDriver for Flutter UI

### Setting Accessibility Labels in Flutter

In your Flutter code, add semantic labels:

```dart
Semantics(
  label: 'Track List Item',
  child: TrackTile(...),
)
```

Or use the `Semantics` widget:

```dart
Semantics(
  label: 'Download All Switch',
  child: Switch(...),
)
```

## Test Coverage

### Phase 1: Immediate Loading & Provider Fixes
- ✅ Library loads within 2 seconds
- ✅ No blank screens on launch
- ✅ List persists through rotation
- ✅ Error states display correctly

### Phase 2: Streaming Cache System
- ✅ Tracks cache during playback
- ✅ Cached tracks play from local storage
- ✅ Cache respects user preference
- ✅ Cache size management

### Phase 3: Download & Sync System
- ✅ Downloads complete successfully
- ✅ Progress updates in real-time
- ✅ "Download all" syncs all tracks
- ✅ Failed downloads can be retried
- ✅ Downloads can be cancelled

## Implementation Status

**Current Status**: Framework structure created, placeholder implementations in place.

**Next Steps**:
1. Implement actual UI element locators based on Flutter widget structure
2. Add FlutterDriver integration if needed
3. Set accessibility labels in Flutter code
4. Implement helper methods in Page objects
5. Run tests and fix failures (TDD approach)

## Writing New Tests

### Example Test

```kotlin
@Test
fun testMyNewFeature() {
    BombestBeatsPage.launchBombestBeats()
        .ensureLibraryLoads()
        .navigateToLibrary()
        .performMyAction()
        .verifyMyExpectedResult()
}
```

### Best Practices

1. **Use Page Objects**: Never access UI elements directly in tests
2. **Method Chaining**: Return `this` or next Page object for fluent API
3. **Clear Assertions**: Use descriptive assertion messages
4. **Wait Conditions**: Use `waitForCondition` helper instead of fixed sleeps
5. **Test Isolation**: Each test should be independent and clean up after itself

## Troubleshooting

### Tests fail to find UI elements
- Ensure Flutter widgets have accessibility labels
- Check if FlutterDriver is needed for widget-level access
- Verify element IDs/selectors are correct

### Tests timeout
- Increase timeout values in `waitForCondition`
- Check if app is loading correctly
- Verify network connectivity for S3 access

### Flutter widgets not accessible
- Add `Semantics` widgets with labels
- Consider using FlutterDriver for complex Flutter UI
- Use UiAutomator for native Android elements

