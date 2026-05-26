# Technology Stack

**Analysis Date:** 2026-03-29

## Languages

**Primary:**
- Python 3.12 - Backend API (`beets-backend/upload_server.py`, 3523 lines). CI uses Python 3.11.
- TypeScript 4.x - Web frontend (`music-frontend/src/`)
- Kotlin 1.9.22 - Android app (`android-app/`), JVM target 17
- Swift (SwiftUI) - iOS app (`ios-app/`), iOS 17.0+

**Secondary:**
- JavaScript - CloudFront function (`cloudfront-functions/beats-spa-rewrite.js`), Workbox service worker config
- Bash - Deployment scripts (`deploy-to-ec2.sh`, `deploy-aws.sh`, `scripts/deploy-frontend.sh`)
- Groovy/Kotlin DSL - Android Gradle build files (`android-app/build.gradle`, `android-app/app/build.gradle.kts`)

## Runtime

**Backend:**
- Python 3.12 (Docker: `python:3.12-slim`). CI tests with 3.11.
- System dependency: `ffmpeg` (waveform generation, audio conversion)
- System dependency: `build-essential` (compiling native Python packages like `bcrypt`)

**Frontend:**
- Node.js 20 (CI `setup-node@v4` with `node-version: "20"`)
- Requires `NODE_OPTIONS=--openssl-legacy-provider` due to legacy `react-scripts` 4.x OpenSSL compatibility

**Android:**
- JDK 17 (Temurin distribution in CI)
- Android compileSdk 34, minSdk 24, targetSdk 34

**iOS:**
- Tuist project generator (`ios-app/Project.swift`)
- iOS deployment target 17.0
- Xcode build (no CI automation)

## Frameworks

**Backend Core:**
- Flask 3.1.2 - HTTP framework (`beets-backend/upload_server.py`)
- Flask-CORS 6.0.1 - Cross-origin request handling
- Flask-JWT-Extended 4.7.1 - JWT authentication
- Beets 2.5.1 - Music library management (import, tagging, metadata)
- Boto3 >=1.34.0 - AWS S3 client for music storage and presigned URLs
- Webauthn >=2.0.0 - FIDO2/passkey authentication

**Frontend Core:**
- React 17.0.2 - UI framework (`music-frontend/`)
- Redux 4.0.5 + React-Redux 7.2.3 - State management
- react-scripts 4.0.3 - Build tooling (Create React App)
- Workbox 5.1.3 - PWA/service worker (offline support, caching)

**Android Core:**
- Jetpack Compose (BOM 2024.02.00) - UI framework
- Material 3 - Design system
- Media3/ExoPlayer 1.2.1 - Audio playback
- Retrofit 2.9.0 + Moshi 1.15.0 - HTTP client + JSON parsing
- OkHttp 4.12.0 - HTTP transport
- Coil 2.5.0 - Image loading
- Navigation Compose 2.7.6 - Screen navigation
- Credentials API 1.2.2 - Passkey authentication

**iOS Core:**
- SwiftUI - UI framework
- URLSession - HTTP client (native Foundation)
- AVPlayer - Audio playback (native)
- Tuist - Project generation (`ios-app/Project.swift`)
- No third-party dependencies (all Apple frameworks)

**Audio Processing (Backend):**
- librosa >=0.10.0 - Beat detection, BPM analysis
- mutagen 1.47.0 - Audio metadata reading/writing
- mediafile 0.13.0 - Media file metadata (Beets dependency)
- numpy >=1.24.4 - Numerical computing (librosa dependency)
- ffmpeg (system) - Audio format conversion, waveform generation

**Testing:**
- Jest (via react-scripts) - Frontend unit tests
- JUnit 4.13.2 - Android unit tests
- Espresso 3.5.1 - Android instrumentation tests
- Compose UI Test JUnit4 - Compose UI tests
- No backend test framework detected

**Build/Dev:**
- Docker - Backend containerization (`beets-backend/Dockerfile`)
- Docker Compose 3.8 - Local dev orchestration (`docker-compose.yml`)
- Gradle 8.14.1 (wrapper) - Android builds, AGP 8.2.0
- npm - Frontend package management
- gh-pages 3.1.0 - Frontend deployment (dev dependency)

## Key Dependencies

**Critical (Backend):**
- `Flask==3.1.2` - Entire API runs on this (`beets-backend/upload_server.py`)
- `beets==2.5.1` - Music library import/tagging. Plugins: `fetchart`, `embedart`, `scrub` (`beets-backend/config.yaml`)
- `boto3>=1.34.0` - S3 music storage, presigned URL generation
- `bcrypt==5.0.0` - Password hashing for user auth
- `PyJWT==2.10.1` - JWT token creation/validation (used via Flask-JWT-Extended)
- `webauthn>=2.0.0` - Passkey/FIDO2 credential registration and login
- `librosa>=0.10.0` - BPM detection and beat tracking

**Critical (Frontend):**
- `react==17.0.2` - Pinned to v17 (not v18). Hooks-based, no concurrent features.
- `redux==4.0.5` / `react-redux==7.2.3` - Global state (legacy Redux, not Redux Toolkit)
- `wavesurfer.js==7.12.1` - Waveform visualization in player
- `framer-motion==6.5.1` - UI animations
- `localforage==1.9.0` - Client-side storage (IndexedDB/WebSQL wrapper)

**Critical (Android):**
- `media3-exoplayer:1.2.1` - Audio streaming with OkHttp data source
- `retrofit2:2.9.0` - API client for all backend communication
- `moshi-kotlin:1.15.0` - JSON serialization
- `credentials:1.2.2` - Passkey authentication via Android Credential Manager

**Infrastructure:**
- `Werkzeug==3.1.4` - WSGI server (Flask's underlying server)
- `musicbrainzngs==0.7.1` - MusicBrainz API for metadata lookup (Beets plugin)
- `jellyfish==1.2.1` - String matching for duplicate detection
- `jsmediatags==3.9.5` - Client-side audio tag reading (frontend)

## Configuration

**Backend Configuration:**
- `beets-backend/config.yaml` - Local dev Beets config (library path, plugins, web port, email SMTP)
- `beets-backend/config.docker.yaml` - Docker Beets config (paths adjusted for container)
- Environment variables for S3, JWT, CORS (see env vars table below)
- `beets-backend/db_path.py` - Resolves `users.db` location via `DATA_DIR` env var

**Frontend Configuration:**
- `music-frontend/package.json` - Build scripts, ESLint config (`react-app` extends)
- `music-frontend/.env.example` - Environment variable template
- `REACT_APP_API_BASE` - Runtime API base URL override
- `PUBLIC_URL` - Path prefix for S3 deployment (`/beats`)

**Android Configuration:**
- `android-app/app/build.gradle.kts` - Dependencies, SDK versions, Compose compiler
- `android-app/build.gradle.kts` - Plugin versions (AGP 8.2.0, Kotlin 1.9.22)
- `android-app/gradle/wrapper/gradle-wrapper.properties` - Gradle 8.14.1
- API base URL hardcoded: `https://beats.bom.best` (in Retrofit setup)

**iOS Configuration:**
- `ios-app/Project.swift` - Tuist project definition (bundle ID, entitlements, deployment target)
- API base URL hardcoded: `https://beats.bom.best` (`ios-app/Targets/BombestBeats/Sources/Services/APIService.swift`)

**Environment Variables:**

| Variable | Used By | Purpose |
|----------|---------|---------|
| `REACT_APP_API_BASE` | Frontend | Backend URL override (default: auto-detect) |
| `PUBLIC_URL` | Frontend | Path prefix (`/beats` for S3 deploy) |
| `S3_BUCKET` | Backend | S3 bucket name for music files |
| `AWS_ACCESS_KEY_ID` | Backend | AWS credentials for S3 |
| `AWS_SECRET_ACCESS_KEY` | Backend | AWS credentials for S3 |
| `S3_REGION` | Backend | S3 region (default: `auto`) |
| `S3_ENDPOINT` | Backend | Custom S3 endpoint (R2/MinIO) |
| `DATA_DIR` | Backend | Directory for `users.db` (Docker: `/app/data`) |
| `FLASK_ENV` | Backend | `development` enables localhost CORS origins |
| `FLASK_DEBUG` | Backend | `1` enables localhost CORS origins |
| `DOCKER_USERNAME` | Deploy | Docker Hub username (default: `thomasphillips3`) |
| `CLOUDFRONT_DIST_ID` | Deploy | CloudFront distribution for cache invalidation |
| `FRONTEND_BUCKET` | Deploy | S3 bucket for web frontend (default: `bombest-beats-web`) |

**Build Configuration:**
- `beets-backend/Dockerfile` - Python 3.12-slim, ffmpeg, pip install, Beets import
- `docker-compose.yml` - Backend (port 8338) + nginx:alpine frontend (port 8080)
- `nginx_beats.conf` - Local dev nginx: frontend at `/beats/`, API proxy at `/beats/api/`
- `nginx-ec2.conf` - Production nginx: reverse proxy port 80 to Flask 8338 with CORS headers

## Platform Requirements

**Development:**
- Docker Desktop (backend containerized builds)
- Node.js 20+ with npm (frontend)
- Python 3.11+ with pip/venv (backend local dev)
- JDK 17 (Android builds)
- Xcode + Tuist (iOS builds, macOS only)
- ffmpeg (waveform generation if running backend locally)

**Production:**
- AWS EC2 (t3.micro free tier) running Docker
- AWS S3 (`bombest-beats-music` in us-west-2) for music file storage
- AWS S3 (`bombest-beats-web` in us-east-1) for web frontend static files
- AWS CloudFront (`E1RBYOEP5K0UI3`) for web frontend CDN
- Cloudflare DNS proxy for `beats.bom.best` (API) and `bom.best` (web)
- Docker Hub (`thomasphillips3/bombest-beats:latest`) for image distribution
- nginx on EC2 as reverse proxy (port 80 -> Flask 8338)

---

*Stack analysis: 2026-03-29*
