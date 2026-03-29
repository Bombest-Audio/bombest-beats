# Coding Conventions

**Analysis Date:** 2026-03-29

## Naming Patterns

**Files:**
- **Python (backend):** `snake_case.py` -- `upload_server.py`, `db_path.py`, `init_db.py`, `make_admin.py`
- **TypeScript (frontend):** Each component/view is a directory with `index.tsx` and `styles.css` -- e.g. `src/views/upload/index.tsx`, `src/components/header/index.tsx`
- **Kotlin (Android):** `PascalCase.kt` -- `MainViewModel.kt`, `MusicApi.kt`, `NetworkModule.kt`
- **CSS:** Co-located `styles.css` per component/view directory, using BEM-like naming -- `.upload__zone`, `.header__icon`
- **Services:** Named by domain in `src/services/` -- `beets.ts`, `upload.ts`, `collaboration.ts`
- **Redux:** `src/redux/types/index.ts`, `src/redux/actions/index.ts`, `src/redux/reducers/*.ts`

**Functions:**
- **Python:** `snake_case` for public functions (`upload_file`, `get_waveform`), underscore-prefixed for internal helpers (`_get_db`, `_db_retry`, `_scan_for_audio_and_images`)
- **TypeScript:** `camelCase` for functions and methods (`handleFiles`, `refreshLibrary`, `getStreamUrl`)
- **Kotlin:** `camelCase` for functions (`fetchLibrary`, `initializeController`, `playMedia`)

**Variables:**
- **Python:** `UPPER_SNAKE_CASE` for module-level constants (`UPLOAD_FOLDER`, `LIBRARY_DB`, `ALLOWED_EXTENSIONS`, `S3_BUCKET`), `snake_case` for local variables
- **TypeScript:** `UPPER_SNAKE_CASE` for constants (`UPLOAD_TIMEOUT_MS`, `MAX_FILE_SIZE_MB`, `S3_UPLOAD_CONCURRENCY`), `camelCase` for local variables and state
- **Kotlin:** `camelCase` for mutable state (`isPlaying`, `currentMediaItem`), `UPPER_SNAKE_CASE` for constants (`VISUALIZER_UPDATE_INTERVAL`)

**Types:**
- **TypeScript:** `PascalCase` for interfaces and types (`BeetsItem`, `WaveformData`, `UploadResult`, `HeaderProps`, `UploadProps`)
- **Kotlin:** `PascalCase` for data classes (`Track`, `LibraryResponse`, `WaveformResponse`, `DashboardResponse`)

**Redux Actions:**
- Action creators: `UPPER_SNAKE_CASE` function names (`ADD_SONGS`, `PLAY_SONG`, `SET_VIEW`)
- Action type constants: `T_` prefix (`T_ADD_SONGS`, `T_PLAY_SONG`, `T_SET_GRID`)

## Code Style

**Formatting:**
- No explicit formatter configured (no `.prettierrc`, `.editorconfig`, or `biome.json`)
- **Python:** 4-space indentation, no strict line length enforcement (some lines exceed 120 chars)
- **TypeScript/TSX:** 2-space indentation, single quotes for strings
- **Kotlin:** 4-space indentation, standard Kotlin style
- **CSS:** 2-space indentation, BEM-like class naming with `__` for elements and `--` for modifiers

**Linting:**
- **Frontend:** ESLint via `react-app` and `react-app/jest` presets (configured in `music-frontend/package.json` `eslintConfig` section). No standalone `.eslintrc` file.
- **TypeScript:** `strict: true` in `music-frontend/tsconfig.json`, plus `noUnusedLocals: true`, `noFallthroughCasesInSwitch: true`
- **Python:** No linter configuration (no `flake8`, `ruff`, `pylint`, or `mypy` config files)
- **Kotlin:** No ktlint or detekt configuration

## Import Organization

**Python (`beets-backend/upload_server.py`):**
1. Standard library (`os`, `json`, `uuid`, `subprocess`, `sqlite3`, `tempfile`, etc.)
2. Flask and extensions (`flask`, `flask_cors`, `flask_jwt_extended`)
3. Third-party (`bcrypt`, `yaml`, `boto3`)
4. Local modules (`from db_path import get_users_db_path`)
- Note: Imports are not cleanly separated by blank lines in all cases. Some `from mutagen` imports are done inline within functions.

**TypeScript (`music-frontend/`):**
1. React and hooks (`react`, `react-redux`)
2. Third-party icons/libraries (`react-icons/fa`)
3. Internal components/views (relative imports `../../components`, `../hooks`)
4. Services (relative imports `../../services/beets`, `../../services/upload`)
5. CSS import last (`./styles.css`)
- No path aliases configured (all relative imports)

**Kotlin (`android-app/`):**
1. Android/AndroidX framework (`androidx.lifecycle`, `androidx.compose`, `androidx.media3`)
2. Third-party (`retrofit2`, `com.squareup.moshi`, `okhttp3`)
3. Internal project (`com.bombest.music.*`)
- No import ordering enforced by tooling

## Error Handling

**Backend (Python/Flask):**
- Global 500 handler at `beets-backend/upload_server.py` line 122: catches all unhandled exceptions, returns JSON `{'error': message}`
- Route-level: `try/except` blocks wrapping core logic, returning `jsonify({'error': ...}), status_code`
- Specific exception types caught where meaningful: `subprocess.CalledProcessError`, `sqlite3.OperationalError`, `OSError`
- Broad `except Exception as e` used frequently as a catch-all with `print(f"...")` for logging
- Temp file cleanup in `finally` blocks (e.g. `upload_folder` route at line 756)
- Database retry helper `_db_retry()` with exponential backoff for SQLite busy/locked errors
- Pattern: return `jsonify({'error': 'descriptive message'}), HTTP_STATUS`

**Frontend (TypeScript):**
- Service methods use `try/catch` returning safe defaults (`null`, `false`, `[]`) on failure
- `console.error(...)` used for all error logging
- `BeetsService` methods: attempt JSON error extraction from response, throw `Error` with server message
- `UploadService`: retry logic with exponential backoff (`fetchWithRetry`), timeout via `AbortController`, HTML response detection
- Pattern for API calls:
```typescript
async methodName(): Promise<ReturnType> {
    try {
        const response = await fetch(url, options);
        if (!response.ok) return fallbackValue;
        return await response.json();
    } catch (e) {
        console.error('Failed to ...:', e);
        return fallbackValue;
    }
}
```

**Android (Kotlin):**
- `try/catch` blocks in coroutine scopes, logging via `android.util.Log.e(TAG, message, exception)`
- `MusicRepository.fetchLibrary()`: network failure falls back to local JSON cache file
- `NetworkModule`: automatic failover between primary and backup URLs on network errors
- `Result<T>` used for download operations (`DownloadManager`)
- Pattern: catch, log, return empty/default state -- never crash

## Common Patterns

**Backend API Response Format:**
- Success: `jsonify({...data...}), 200` or `201`
- Error: `jsonify({'error': 'Human-readable message'}), STATUS`
- Async jobs: Return `202` with `{'job_id': ..., 'status': 'processing'}`, poll via `/upload/status/<job_id>`

**Frontend Service Objects:**
- Services exported as singleton objects (`BeetsService`, `UploadService`, `CollaborationService`) with async methods
- Auth token retrieved from `localStorage.getItem('token')` at call time, passed as `Authorization: Bearer <token>` header
- `authHeaders()` helper function in `upload.ts` centralizes token attachment

**Frontend State Management:**
- Redux with classic action creators + switch-case reducers (not Redux Toolkit)
- Action types defined as `T_` prefixed string constants in `src/redux/types/index.ts`
- Action creators in `src/redux/actions/index.ts` return plain objects `{ type, payload }`
- Auth state managed via React Context (`src/context/auth.tsx`) with `useAuth()` hook
- Component-level state via `useState` hooks

**Frontend Component Pattern:**
- Functional components with destructured props
- Props defined as `type ComponentNameProps = { ... }` (not `interface`)
- Default exports per component file
- Barrel exports via `index.ts` files in `src/components/index.ts` and `src/views/index.ts`

**Backend Auth Decorator:**
- `@admin_required()` decorator wraps routes needing admin access (JWT + role check)
- `@jwt_required()` for any authenticated route
- Combined usage: `@app.route('/path', methods=['POST'])` then `@admin_required()` on next line

**Android Architecture:**
- Single `MainViewModel` (AndroidViewModel) holds all UI state as `mutableStateOf<T>` fields
- `NetworkModule` is a Kotlin `object` singleton providing Retrofit API instances
- Repository pattern: `MusicRepository` wraps API calls with caching
- Data classes with `@Json` annotations for Moshi serialization
- `viewModelScope.launch(Dispatchers.IO)` for network/disk operations

**CSS Theming:**
- CSS custom properties (`--bg-color`, `--color-primary`, `--accent-color`) set programmatically via `src/utils/index.ts` `setTheme()` function
- Dark theme is default, light theme togglable
- Glassmorphism effects with `backdrop-filter: blur()` and semi-transparent backgrounds

**SQLite Database Access (Backend):**
- `_get_db()` helper opens connections with WAL mode and busy timeout
- `_db_retry()` wraps operations that may hit SQLITE_BUSY/LOCKED
- Connections opened and closed per-request (no connection pooling)
- Parameterized queries used throughout (`cursor.execute("... WHERE id = ?", (id,))`)
- Two separate databases: `library.db` (beets-managed) and `users.db` (app-managed)

## Logging

**Framework:**
- **Python:** Mix of `print()` and `traceback.print_exc()`. No structured logging framework (`logging` module not used). Emoji prefixes in some messages: `"S3 Client initialized"`, `"S3 Upload Failed"`
- **TypeScript:** `console.error()`, `console.warn()`, `console.log()`, `console.trace()`
- **Kotlin:** `android.util.Log.d/w/e("MainViewModel", message)` with fully qualified class references

**Patterns:**
- Backend: `print(f"descriptive message: {variable}")` for operational events
- Frontend: `console.error('Failed to <action>:', e)` in catch blocks
- Android: `Log.d(TAG, message)` for debug, `Log.e(TAG, message, exception)` for errors

## Comments

**When to Comment:**
- Docstrings on Python functions using triple-quote format: `"""Brief description"""`
- Inline comments for non-obvious logic (e.g., WAV file handling workarounds, race condition explanations)
- `// eslint-disable-next-line` used to suppress specific warnings in React
- KDoc-style comments on some Kotlin classes/functions

**JSDoc/TSDoc:**
- Not consistently used. Some TSDoc on `UploadService` private methods (`/** ... */`)
- No JSDoc on React components or hooks

---

*Convention analysis: 2026-03-29*
