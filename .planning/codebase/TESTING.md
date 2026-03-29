# Testing Patterns

**Analysis Date:** 2026-03-29

## Test Framework

**Frontend (React/TypeScript):**
- Runner: Jest (via `react-scripts test`, bundled with Create React App)
- Assertion Library: `@testing-library/jest-dom` (configured in `music-frontend/src/setupTests.ts`)
- Testing utilities installed: `@testing-library/react@^11.1.0`, `@testing-library/user-event@^12.1.10`, `@types/jest@^26.0.15`
- Config: No standalone `jest.config.*` file -- Jest configuration managed by `react-scripts`
- ESLint: `react-app/jest` preset enabled in `music-frontend/package.json`

**Android (Kotlin):**
- Unit tests: JUnit 4 (`junit:junit:4.13.2`) in `android-app/app/build.gradle.kts`
- Instrumented tests: AndroidX Test (`androidx.test.ext:junit:1.1.5`, `androidx.test.espresso:espresso-core:3.5.1`)
- Compose UI tests: `androidx.compose.ui:ui-test-junit4` (via Compose BOM)
- Test runner: `androidx.test.runner.AndroidJUnitRunner` (set in `android-app/app/build.gradle.kts` line 18)

**Backend (Python):**
- No test framework configured. No `pytest.ini`, `setup.cfg`, `conftest.py`, or `pyproject.toml` with test configuration.
- No test files exist in `beets-backend/`.

**Run Commands:**
```bash
# Frontend
cd music-frontend && npm test              # Run Jest tests (interactive watch mode)
cd music-frontend && npm run build         # Build verification (used as CI test)

# Android
cd android-app && ./gradlew assembleDebug  # Compile check (used as CI test)
cd android-app && ./gradlew test           # Run unit tests (not used in CI)

# Backend
cd beets-backend && python -c "import upload_server; print('OK')"  # Import check (CI test)
```

## Test File Organization

**Frontend:**
- Location: `music-frontend/src/setupTests.ts` -- Jest DOM setup file (only test-related file)
- No actual test files (`.test.tsx`, `.spec.ts`) exist in the project source tree
- Pattern: Co-located test files would go alongside source files (CRA convention)

**Android:**
- Unit tests: `android-app/app/src/test/java/com/bombest/music/ExampleUnitTest.kt`
- Instrumented tests: `android-app/app/src/androidTest/java/com/bombest/music/ExampleInstrumentedTest.kt`
- Both are scaffold-only placeholder tests from project creation

**Backend:**
- No test directory or test files exist

## Test Structure

**Android Unit Test (placeholder):**
```kotlin
// android-app/app/src/test/java/com/bombest/music/ExampleUnitTest.kt
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        Assert.assertEquals(4, (2 + 2).toLong())
    }
}
```

**Android Instrumented Test (placeholder):**
```kotlin
// android-app/app/src/androidTest/java/com/bombest/music/ExampleInstrumentedTest.kt
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        Assert.assertEquals("com.bombest.music", appContext.packageName)
    }
}
```

**Patterns:**
- No setup/teardown patterns established
- No assertion patterns beyond default JUnit `Assert.assertEquals`
- No mocking frameworks configured or used

## Mocking

**Framework:** None configured

**What Would Need Mocking (for future tests):**
- Backend: Flask test client, SQLite in-memory databases, S3 client (`boto3`), subprocess calls to `beet`
- Frontend: `fetch` API, `localStorage`, `Audio` element, Redux store
- Android: Retrofit API, `MediaBrowser`, coroutine dispatchers

## Fixtures and Factories

**Test Data:** None established

**What Would Be Needed:**
- Backend: Sample audio files, pre-populated SQLite databases, mock S3 responses
- Frontend: Mock API responses for library, playlists, upload results
- Android: Mock `Track` objects, `MediaItem` builders

## Coverage

**Requirements:** None enforced

**Current State:**
- No coverage reports generated
- No coverage thresholds configured
- No coverage tooling set up

## Test Types

**Unit Tests:**
- Android: Placeholder only (`ExampleUnitTest.kt`). No real unit tests.
- Frontend: Setup file exists but no test files written.
- Backend: None.

**Integration Tests:**
- None across any platform.

**E2E Tests:**
- Not used. No Cypress, Playwright, Selenium, or equivalent configured.

**CI "Tests" (actual verification in `.github/workflows/pre-merge.yml`):**
- **Android:** `./gradlew assembleDebug --no-daemon` -- verifies the project compiles
- **Backend:** `pip install -r requirements.txt && python -c "import upload_server; print('OK')"` -- verifies dependencies install and the main module imports without error
- **Frontend:** `npm ci && npm run build` -- verifies the project builds without TypeScript/compilation errors

## CI Integration

**Trigger:** Comment-triggered on PRs (not automatic on push/PR creation)
- `rocket emoji` comment: Run all tests + auto-merge to main
- `:run-tests:` comment: Run all three test suites
- `:run-test: android|frontend|backend` comment: Run a single suite
- Only runs for comments from MEMBER, OWNER, or COLLABORATOR

**Workflow:** `.github/workflows/pre-merge.yml`
- Runner: `ubuntu-latest`
- JDK 17 for Android, Python 3.11 for backend, Node 20 for frontend
- No test result reporting, no coverage upload, no artifact storage

**Auto-merge:** When triggered by `rocket emoji` and all "tests" pass, the PR is auto-merged via `gh pr merge --merge`

## Common Patterns

**Error Path Verification:**
- The codebase has no automated error path testing
- Error handling is verified only through manual testing and production monitoring

**Async Testing:**
- No patterns established (no async tests written)

**Error Testing:**
- No patterns established

## Testing Gaps

**Backend (`beets-backend/`):**
- What's not tested: Every endpoint, all database operations, S3 integration, file upload processing, auth/JWT flow, presigned URL generation, duplicate detection, metadata tagging, waveform generation, beat detection
- Files: `beets-backend/upload_server.py` (1500+ lines, zero test coverage)
- Risk: Any code change could break API contracts, database migrations, or file processing without detection
- Priority: **High** -- this is the core API serving all clients

**Frontend (`music-frontend/`):**
- What's not tested: All React components, Redux reducers/actions, service modules, auth context, upload flow, playlist management
- Files: All files in `music-frontend/src/`
- Risk: UI regressions, broken API integrations, state management bugs
- Priority: **Medium** -- TypeScript strict mode catches type errors at build time, but logic bugs go undetected

**Android (`android-app/`):**
- What's not tested: ViewModel logic, repository caching, network failover, media playback, upload flow, playlist management, haptic engine, visualizer
- Files: All files in `android-app/app/src/main/java/com/bombest/music/`
- Risk: Playback bugs, data loss, network handling regressions
- Priority: **Medium** -- compile check catches syntax/type errors but not logic bugs

**Cross-Platform:**
- No API contract tests between frontend/Android and backend
- No integration tests verifying the full upload->import->stream flow
- No performance tests for waveform generation, beat detection, or library queries with large datasets

**Security:**
- No tests for auth bypass, JWT token validation, SQL injection (parameterized queries are used but not verified), path traversal (zip extraction has manual protection but no test), file type validation bypass
- Priority: **High** -- security-critical paths have no automated verification

---

*Testing analysis: 2026-03-29*
