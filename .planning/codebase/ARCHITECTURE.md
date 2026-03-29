# Architecture

**Analysis Date:** 2026-03-29

## Pattern Overview

**Overall:** Monorepo with a monolithic backend API and three independent client applications (web, Android, iOS) communicating over REST/JSON.

**Key Characteristics:**
- Single Flask process (`beets-backend/upload_server.py`, 3523 lines) serves the entire API surface
- All clients share the same REST API contract, no shared type definitions or API specification
- Two SQLite databases: `library.db` (Beets-managed music catalog) and `users.db` (application data)
- Music files stored in AWS S3; backend proxies streaming and serves presigned upload URLs
- Clients are fully decoupled from each other -- no shared code between web, Android, and iOS

## Layers

**Reverse Proxy (nginx on EC2):**
- Purpose: Terminate HTTP on port 80, proxy to Flask on 8338, handle CORS preflight for error responses
- Location: `nginx-ec2.conf`
- Contains: CORS origin map, proxy_pass rules, error page handlers for 413/502/503/504
- Depends on: Flask backend running on localhost:8338
- Used by: All clients via Cloudflare DNS (beats.bom.best)

**Backend API (Flask):**
- Purpose: Serve library data, stream audio, handle uploads, auth, playlists, metrics, collaboration
- Location: `beets-backend/upload_server.py` (monolithic), `beets-backend/db_path.py`, `beets-backend/init_db.py`
- Contains: All route handlers, database queries, S3 interactions, JWT auth, WebAuthn passkey logic
- Depends on: SQLite (library.db, users.db), AWS S3, Beets CLI (for import), ffmpeg (waveform generation), librosa (beat detection)
- Used by: All client applications

**Web Frontend (React):**
- Purpose: PWA music player for browsers
- Location: `music-frontend/src/`
- Contains: React components, Redux state, API service layer, service worker for offline support
- Depends on: Backend API via `src/services/beets.ts` and `src/services/upload.ts`
- Used by: End users via browser (served from S3/CloudFront at `bom.best/beats/`)

**Android App (Kotlin/Compose):**
- Purpose: Native Android music player
- Location: `android-app/app/src/main/java/com/bombest/music/`
- Contains: Compose UI, ViewModels, Retrofit API layer, Media3 playback service, haptics engine
- Depends on: Backend API via `data/api/MusicApi.kt`, `data/NetworkModule.kt`
- Used by: Android users

**iOS App (SwiftUI):**
- Purpose: Native iOS music player
- Location: `ios-app/Targets/BombestBeats/Sources/`
- Contains: SwiftUI views, ViewModels, APIService, AudioService, AVPlayer-based playback
- Depends on: Backend API via `Services/APIService.swift`
- Used by: iOS users

**CDN/Edge (CloudFront + Cloudflare):**
- Purpose: Serve web frontend static assets; proxy API requests to EC2
- Location: `cloudfront-functions/beats-spa-rewrite.js`, `cloudflared-config.yml`
- Contains: SPA rewrite function (redirects /beats/* to /beats/index.html for client-side routing)
- Depends on: S3 bucket `bombest-beats-web` (frontend), EC2 instance (API)

## Data Flow

**Audio Streaming (Client to Speaker):**

1. Client requests `GET /library` to fetch track list with IDs, titles, artists, paths
2. Client requests `GET /stream/<track_id>` to begin playback
3. Backend resolves track ID to file path via `library.db`, checks S3 first, falls back to local filesystem
4. Backend streams audio bytes with `Content-Type` header (e.g., `audio/mpeg`), supports HTTP range requests
5. Client audio engine (HTML5 Audio / ExoPlayer / AVPlayer) handles buffering and playback

**File Upload (Two Paths):**

*Direct upload (small files, <= 100 MB via Cloudflare):*
1. Client POSTs file to `POST /upload` with multipart form data
2. Backend saves to `uploads/`, runs `beet import` CLI to catalog, generates waveform
3. Returns new track metadata

*Presigned S3 upload (large files, bypasses Cloudflare/Flask body limits):*
1. Client POSTs file metadata to `POST /upload/presign` to get presigned S3 PUT URLs
2. Client uploads directly to S3 using presigned URLs (parallel, 3 concurrent)
3. Client calls `POST /upload/process` to trigger server-side import from S3

**Authentication:**

1. Client sends `POST /auth/login` with `{username, password}` or `POST /auth/register` with invite code
2. Backend verifies credentials against `users.db`, returns non-expiring JWT
3. Client stores JWT in localStorage (web) / DataStore (Android) / UserDefaults (iOS)
4. Subsequent requests include `Authorization: Bearer <token>` header
5. Passkey/WebAuthn flow available as alternative: challenge-response via `/auth/passkey/*` endpoints

**State Management:**

- **Web:** Redux store with reducers for `app` (view state), `songs` (track list), `playState` (current track/playing), `settings` (theme/shuffle/repeat), `playlists`. Persisted to IndexedDB via `localforage` through `src/services/data-store.ts`.
- **Android:** `MainViewModel` holds library state, playback state, favorites. `AuthViewModel` and `PlaylistViewModel` for their domains. State flows via Compose `collectAsState`.
- **iOS:** MVVM with `LibraryViewModel`, `AuthViewModel`, `DashboardViewModel`, `PlaylistDetailViewModel`, `SearchViewModel`. Published properties drive SwiftUI.

## Key Abstractions

**Track/Item:**
- Purpose: Represents a music track in the library
- Backend schema: `items` table in `library.db` (Beets-managed, columns: id, title, artist, album, album_id, path, bpm, length, etc.)
- Frontend type: `BeetsItem` in `music-frontend/src/services/beets.ts`
- Android type: `Track` in `android-app/app/src/main/java/com/bombest/music/data/api/MusicApi.kt` and `android-app/app/src/main/java/com/bombest/music/data/model/Track.kt`
- iOS type: Models in `ios-app/Targets/BombestBeats/Sources/Models/Models.swift`
- Pattern: Each client defines its own track model independently; no shared schema

**API Client:**
- Purpose: Centralized HTTP communication with the backend
- Web: `BeetsService` object in `music-frontend/src/services/beets.ts` (fetch-based, no library)
- Android: `MusicApi` Retrofit interface in `android-app/.../data/api/MusicApi.kt`, `AuthApi` in `android-app/.../data/api/AuthApi.kt`, `PlaylistApi` in `android-app/.../data/api/PlaylistApi.kt`
- iOS: `APIService` singleton in `ios-app/.../Services/APIService.swift` (URLSession-based)
- Pattern: Each client implements its own HTTP layer with different error handling, retry, and failover strategies

**Audio Playback:**
- Purpose: Play audio streams from backend
- Web: HTML5 `<audio>` element with `crossOrigin="anonymous"`, controlled in `music-frontend/src/app/index.tsx`
- Android: Media3 ExoPlayer via `BombestMediaService` in `android-app/.../service/BombestMediaService.kt`
- iOS: AVPlayer via `AudioService` in `ios-app/.../Services/AudioService.swift`
- Pattern: Each platform uses its native audio stack; Media Session API (web) and MediaSession (Android) for system integration

## Entry Points

**Backend:**
- Location: `beets-backend/upload_server.py` (line 3518: `if __name__ == '__main__':`)
- Triggers: `python upload_server.py` or Docker CMD
- Responsibilities: Initializes users.db and track_artwork table, starts Flask on 0.0.0.0:8338

**Web Frontend:**
- Location: `music-frontend/src/index.tsx`
- Triggers: Browser loading `index.html`
- Responsibilities: Creates Redux store (hydrated from IndexedDB), wraps app in AuthProvider and Redux Provider, registers service worker

**Android:**
- Location: `android-app/app/src/main/java/com/bombest/music/MainActivity.kt`
- Triggers: App launch, resolves `Screen` enum for navigation
- Responsibilities: Sets up Compose content, handles navigation between Library/Playlists/Dashboard/Upload/Account screens

**iOS:**
- Location: `ios-app/Targets/BombestBeats/Sources/BombestApp.swift`
- Triggers: App launch
- Responsibilities: Creates SwiftUI app with `MainTabView` as root

## Error Handling

**Strategy:** Each layer handles errors independently with no global error boundary or unified error type.

**Patterns:**
- Backend: Try/except around route handlers, returns JSON `{'error': message}` with appropriate HTTP status codes. Global 500 handler at `beets-backend/upload_server.py:122` returns JSON (not HTML).
- Web frontend: Per-method try/catch in `BeetsService`, returns null/false/empty array on failure, logs to console. No user-facing error toasts for API failures beyond network errors.
- Android: Retrofit exceptions caught in ViewModels, errors surfaced via StateFlow. `NetworkModule` has automatic failover between primary (`beats.bom.best`) and fallback (`beats-aws.bom.best`) URLs.
- iOS: `APIError` sealed type in `APIService.swift` with cases for `invalidURL`, `networkError`, `serverError`, `decodingError`, `unauthorized`.

## Cross-Cutting Concerns

**Logging:**
- Backend: Mix of `print()` and `traceback.print_exc()` -- no structured logging framework
- Android: `android.util.Log` with TAG pattern
- iOS: `print()` statements
- Web: `console.error()`/`console.log()`

**Validation:**
- Backend: Inline validation in each route handler (check required fields, file size, format)
- Web upload: `validateFiles()` in `music-frontend/src/services/upload.ts` checks file size, filename length before upload
- No shared validation schema between frontend and backend

**Authentication:**
- JWT-based with non-expiring tokens (`JWT_ACCESS_TOKEN_EXPIRES = False` in `beets-backend/upload_server.py:41`)
- `admin_required()` decorator for admin-only routes (upload, delete, batch operations)
- WebAuthn/Passkey support as secondary auth mechanism
- Role system: `user` (default), `admin` (upload/edit privileges)

**CORS:**
- Handled at two levels: Flask-CORS middleware (`beets-backend/upload_server.py:44-52`) and nginx (`nginx-ec2.conf:3-10`)
- Allowed origins: `bom.best`, `www.bom.best`, `beats.bom.best`, `beats-app.bom.best`, CloudFront distribution URL
- localhost origins only allowed when `FLASK_ENV=development`

## API Route Summary

All routes defined in `beets-backend/upload_server.py`:

| Route | Methods | Auth | Purpose |
|-------|---------|------|---------|
| `/library` | GET | None | List all tracks |
| `/stream/<id>` | GET | None | Stream audio file |
| `/track/<id>/art` | GET | None | Track artwork |
| `/track/<id>/canvas` | GET | None | Track canvas (animated art) |
| `/track/<id>/beats` | GET | None | Beat/bar detection data |
| `/waveform/<id>` | GET | None | Waveform peak data |
| `/album/<id>/art` | GET | None | Album artwork |
| `/upload` | POST | Admin | Direct file upload |
| `/upload/folder` | POST | Admin | Folder/zip upload |
| `/upload/presign` | POST | Admin | Get S3 presigned URLs |
| `/upload/process` | POST | Admin | Process S3-uploaded files |
| `/upload/status/<job_id>` | GET | Admin | Check async import job status |
| `/track/<id>` | PUT | None | Update track metadata |
| `/track/<id>` | DELETE | Admin | Delete track |
| `/tracks/batch` | PUT/DELETE | None/Admin | Batch update/delete tracks |
| `/tracks/reorder` | PUT | None | Reorder track positions |
| `/duplicates` | DELETE | Admin | Remove duplicate tracks |
| `/auth/login` | POST | None | Login (username/password) |
| `/auth/register` | POST | None | Register (requires invite code) |
| `/auth/me` | GET | JWT | Get current user info |
| `/auth/passkey/*` | Various | JWT | WebAuthn passkey management |
| `/playlists` | GET/POST | None/JWT | List/create playlists |
| `/playlists/<id>` | PUT/DELETE | JWT | Update/delete playlist |
| `/playlists/<id>/tracks` | GET/POST/DELETE | None/JWT | Manage playlist tracks |
| `/playlists/<id>/share` | POST/DELETE | JWT | Share/unshare playlist |
| `/playlists/<id>/art` | GET/PUT/POST | None/JWT | Playlist artwork |
| `/shared/<token>` | GET | None | View shared playlist |
| `/favorites` | GET/POST/DELETE | JWT | Manage favorites |
| `/tracks/<id>/loops` | GET/POST | JWT | Loop points |
| `/tracks/<id>/lyrics` | GET/POST | JWT | Lyrics |
| `/tracks/<id>/comments` | GET/POST | JWT | Comments |
| `/metrics/play` | POST | Optional | Record play event |
| `/metrics/batch` | POST | JWT | Batch record plays |
| `/metrics/dashboard` | GET | JWT | Dashboard analytics |
| `/notify-interest` | POST | JWT | Email notification for track interest |

## Database Schema

**`library.db` (Beets-managed, auto-created on import):**
- `items` - Core track catalog: id, title, artist, album, album_id, path, bpm, length, track, etc.
- `albums` - Album groupings with artwork
- `track_artwork` - Custom per-track art and canvas files (added by app, not Beets)

**`users.db` (Application data, initialized by `beets-backend/init_db.py`):**
- `users` - id, username, password_hash, role, email, invite_code, created_at
- `playlists` - id, name, user_id, is_system, is_synced, sort_mode, description, art_path, is_public, share_token
- `playlist_tracks` - playlist_id, track_id, position, added_at
- `loops` / `loop_points` - Loop markers for tracks
- `lyrics` - Track lyrics
- `comments` - Track comments
- `invites` - Invite codes for registration
- `passkey_credentials` - WebAuthn credential storage
- `plays` - Play count metrics (track_id, user_id, played_at)
- `favorites` - User favorites

---

*Architecture analysis: 2026-03-29*
