# External Integrations

**Analysis Date:** 2026-03-29

## APIs & External Services

**MusicBrainz:**
- Purpose: Music metadata lookup during Beets import (artist, album, track info)
- SDK: `musicbrainzngs==0.7.1` (`beets-backend/requirements.txt`)
- Auth: None required (rate-limited public API)
- Used by: Beets `import` command during upload processing (`beets-backend/upload_server.py`)

**Beets Web API:**
- Purpose: Alternative music library query interface (runs on port 8337)
- Config: `beets-backend/config.yaml` (web plugin, host `0.0.0.0`, port 8337)
- Used by: Frontend `BEETS_BASE` for `/stats` endpoint (`music-frontend/src/services/beets.ts`, line 19)
- Note: Secondary to the main Flask API on port 8338; most endpoints use Flask directly

**SMTP Email (Gmail):**
- Purpose: Send notification emails when users express interest in tracks
- Config: `beets-backend/config.yaml` (smtp_server, smtp_port, sender_email, password)
- Endpoint: `POST /notify-interest` (`beets-backend/upload_server.py`, line 3188)
- Auth: Gmail app password stored in `config.yaml`
- Note: Sends via `smtplib.SMTP` with TLS (port 587)

## Data Storage

**SQLite - Music Library (`library.db`):**
- Location: `beets-backend/music/library.db` (local), `/app/music/library.db` (Docker)
- Client: Direct `sqlite3` module, Beets ORM for imports
- Purpose: Beets-managed music catalog (tracks, albums, metadata)
- Custom table: `track_artwork` (added by `upload_server.py`, line 132)
- Concurrency: WAL mode + busy timeout + threading lock + retry helper (`beets-backend/upload_server.py`, lines 154-176)
- Schema: Managed by Beets; extended with `track_artwork` table

**SQLite - Users Database (`users.db`):**
- Location: Resolved by `beets-backend/db_path.py` - `$DATA_DIR/users.db` or `music/users.db`
- Client: Direct `sqlite3` module
- Purpose: User accounts, playlists, playlist tracks, loops, lyrics, comments, passkey credentials
- Schema: Defined in `beets-backend/init_db.py` with migration support (ALTER TABLE for new columns)
- Tables:
  - `users` - id, username, password_hash, role, email, invite_code
  - `playlists` - id, name, user_id, is_system, is_synced, sort_mode, description, art_path, is_public, share_token
  - `playlist_tracks` - playlist_id, track_id, position
  - `loops` - id, track_id, start_time, end_time, name
  - `lyrics` - id, track_id, content
  - `comments` - id, track_id, user_id, content
  - `passkey_credentials` - user_id, credential_id, public_key, sign_count

**AWS S3 - Music File Storage:**
- Bucket: `bombest-beats-music` (us-west-2)
- Client: `boto3` S3 client with `s3v4` signature (`beets-backend/upload_server.py`, lines 54-74)
- Purpose: Persistent music file storage, presigned URL uploads for large files
- Env vars: `S3_BUCKET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `S3_REGION`
- Optional endpoint override: `S3_ENDPOINT` (for Cloudflare R2 or MinIO compatibility)
- Features:
  - Presigned PUT URLs for direct browser-to-S3 uploads (`/upload/presign`)
  - Server-side processing after S3 upload (`/upload/process`)
  - Async job processing with status polling (`/upload/status/<job_id>`)

**AWS S3 - Web Frontend:**
- Bucket: `bombest-beats-web` (us-east-1)
- Purpose: Static hosting for React build output
- Deploy: `scripts/deploy-frontend.sh` syncs `music-frontend/build/` to `s3://bombest-beats-web/beats/`
- Served via CloudFront

**Local Filesystem:**
- `uploads/` - Temporary upload staging directory (`beets-backend/upload_server.py`, line 102)
- `waveforms/` - Cached waveform JSON files per track ID (`beets-backend/upload_server.py`, line 104)
- `music/` - Local music files and library.db (`beets-backend/upload_server.py`, line 103)
- `music/playlist_art/` - Playlist cover art images (`beets-backend/upload_server.py`, line 106)
- `music/track_art/` - Per-track cover art and canvas media (`beets-backend/upload_server.py`, line 114)

**Client-Side Storage (Frontend):**
- `localStorage` - JWT token storage (`token` key) (`music-frontend/src/services/beets.ts`)
- `localforage` (IndexedDB) - Offline music data cache (`music-frontend/src/services/data-store.ts`)
- Workbox service worker - PWA asset caching (`music-frontend/package.json` workbox dependencies)

**Client-Side Storage (Android):**
- DataStore Preferences - App settings (`androidx.datastore:datastore-preferences:1.0.0`)
- UserDefaults equivalent for auth token storage

**Client-Side Storage (iOS):**
- `UserDefaults` - Auth token storage (prototype; Keychain recommended for production) (`ios-app/Targets/BombestBeats/Sources/Services/APIService.swift`, line 17)

## Authentication & Authorization

**Password Auth (Primary):**
- Endpoint: `POST /auth/login` (`beets-backend/upload_server.py`)
- Hashing: bcrypt with salt (`bcrypt==5.0.0`)
- Tokens: JWT via Flask-JWT-Extended, non-expiring (`JWT_ACCESS_TOKEN_EXPIRES = False`)
- Secret: `jwt_secret` from `config.yaml` (fallback: `'dev-secret-key'`)
- Roles: `user` (default), `admin` (upload, edit, delete permissions)
- Admin check: `admin_required()` decorator (`beets-backend/upload_server.py`, line 78)

**Passkey/WebAuthn Auth (Secondary):**
- Library: `webauthn>=2.0.0` (`beets-backend/requirements.txt`)
- Registration: `POST /auth/passkey/register/options` + `POST /auth/passkey/register/verify`
- Login: Passkey login endpoints (WebAuthn assertion flow)
- Storage: `passkey_credentials` table in `users.db`
- Challenge storage: In-memory dict `passkey_challenges` (not persistent; production should use Redis)
- RP ID: Derived dynamically from request origin (supports localhost and production)
- Android: Uses `androidx.credentials` Credential Manager API
- iOS: Uses FaceID (`NSFaceIDUsageDescription` in `Project.swift`)

**Authorization Model:**
- Two roles: `user` and `admin`
- Admin-only operations: upload, track deletion, batch operations, duplicate removal
- JWT token passed via `Authorization: Bearer <token>` header
- Frontend stores token in `localStorage.getItem('token')`
- No token refresh mechanism (tokens never expire)

## CDN & DNS

**Cloudflare:**
- DNS proxy for `beats.bom.best` (A record to EC2 public IP)
- DNS for `bom.best` / `www.bom.best`
- Free-tier body size limit: 100 MB (drives presigned upload fallback in `music-frontend/src/services/upload.ts`, line 13)
- Historical: Cloudflare Tunnel config exists (`cloudflared-config.yml`) but is deprecated

**AWS CloudFront:**
- Distribution: `E1RBYOEP5K0UI3`
- Origin: `bombest-beats-web` S3 bucket
- Serves: `https://bom.best/beats/` and `https://d37qdccady5d3d.cloudfront.net`
- CloudFront Function: `beats-spa-rewrite` (`cloudfront-functions/beats-spa-rewrite.js`) - rewrites SPA routes to `/beats/index.html`
- Cache invalidation: `scripts/deploy-frontend.sh` invalidates `/beats/*` on deploy

## Monitoring & Observability

**Error Tracking:**
- None (no Sentry, Datadog, or similar integration detected)

**Logging:**
- Backend: `print()` statements throughout `upload_server.py` (not using `logging` module)
- Flask 500 handler returns JSON error responses (`beets-backend/upload_server.py`, line 122)
- Server logs to stdout (Docker captures)

**Metrics:**
- Custom play tracking: `POST /metrics/play`, `GET /metrics/dashboard` (`beets-backend/upload_server.py`)
- Dashboard stats: total plays, top tracks, daily plays, per-user breakdown
- Frontend metrics service: `music-frontend/src/services/metrics-manager.ts`
- Android batch play recording: `POST /metrics/batch` (`android-app/.../MusicApi.kt`)
- iOS metrics: `ios-app/Targets/BombestBeats/Sources/Services/MetricsService.swift`

**Health Checks:**
- No dedicated health check endpoint detected
- CI "sanity check": `python -c "import upload_server; print('OK')"` (import test only)

## CI/CD & Deployment

**CI Pipeline:**
- GitHub Actions: `.github/workflows/pre-merge.yml`
- Trigger: PR comment-based (`rocket emoji`, `:run-tests:`, `:run-test: android|frontend|backend`)
- Restricted to: MEMBER, OWNER, COLLABORATOR comment authors
- Tests: Android `assembleDebug`, backend Python import check, frontend `npm run build`
- Auto-merge: `rocket emoji` comment triggers merge to main after all tests pass

**Backend Deployment:**
1. `deploy-aws.sh` - Build `linux/amd64` Docker image, push to Docker Hub (`tomdabomb2u/bombest-beats:latest`)
2. `deploy-container-to-ec2.sh` - Update EC2 container via AWS SSM
3. `deploy-nginx-to-ec2.sh` - Deploy nginx config to EC2
4. Orchestrated by `deploy-to-ec2.sh` (runs all three steps)

**Frontend Deployment:**
- `scripts/deploy-frontend.sh` - Build with `PUBLIC_URL=/beats`, sync to S3, invalidate CloudFront
- Build env: `REACT_APP_API_BASE=https://beats.bom.best`, `GENERATE_SOURCEMAP=false`

**Docker:**
- Image: `python:3.12-slim` base
- Build steps: Install ffmpeg + build-essential, pip install, copy code, Beets import (`beets-backend/Dockerfile`)
- Docker Hub: `tomdabomb2u/bombest-beats:latest`
- EC2 runtime: `docker run` with `-v /data/beets:/app/music` volume mount

**No iOS CI/CD** - Builds require Xcode on macOS, managed locally via Tuist.

## Webhooks & Callbacks

**Incoming:**
- None detected

**Outgoing:**
- Email notification on track interest: `POST /notify-interest` triggers SMTP send to configured recipient (`beets-backend/upload_server.py`, line 3188)

---

*Integration audit: 2026-03-29*
