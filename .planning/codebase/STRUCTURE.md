# Codebase Structure

**Analysis Date:** 2026-03-29

## Directory Layout

```
bombest-beats/
├── beets-backend/          # Python/Flask backend (API, streaming, uploads, auth)
├── music-frontend/         # React/TypeScript web app (PWA, git submodule)
├── android-app/            # Kotlin/Jetpack Compose Android app
├── ios-app/                # SwiftUI iOS app (Tuist-managed)
├── scripts/                # Deployment and operational scripts
├── cloudfront-functions/   # CloudFront edge function for SPA routing
├── workers/                # Cloudflare Workers (beats-proxy, mostly empty)
├── docs/                   # Operational documentation (deploy, troubleshoot)
├── .github/workflows/      # CI/CD (pre-merge.yml)
├── .planning/codebase/     # Architecture analysis documents (this file)
├── docker-compose.yml      # Local dev: backend + nginx frontend
├── nginx-ec2.conf          # Production nginx reverse proxy config
├── Caddyfile               # Alternative reverse proxy config (Caddy)
├── cloudflared-config.yml  # Cloudflare Tunnel config
├── deploy-to-ec2.sh        # Main backend deployment script
├── deploy-aws.sh           # Docker build + push to Docker Hub
├── deploy-container-to-ec2.sh  # Deploy container via SSM
├── deploy-nginx-to-ec2.sh  # Deploy nginx config via SSM
├── setup-ec2-aws-cli.sh    # EC2 instance provisioning
├── setup-s3-simple.sh      # S3 bucket setup
├── sync-to-s3.sh           # Sync music files to S3
├── generate_assets.py      # App icon/asset generation
├── CLAUDE.md               # Claude Code project instructions
└── README.md               # Project overview
```

## Directory Purposes

**`beets-backend/`:**
- Purpose: Entire backend API - a single Flask application
- Contains: Python source, Dockerfile, config files, utility scripts
- Key files:
  - `upload_server.py` (3523 lines): Monolithic Flask app with ALL routes
  - `init_db.py`: Database schema initialization and migration
  - `db_path.py`: Resolves users.db path (env-aware)
  - `Dockerfile`: Python 3.12-slim, ffmpeg, beets import
  - `config.yaml` / `config.docker.yaml`: Beets and JWT configuration
  - `requirements.txt`: Python dependencies (28 packages)
  - `make_admin.py`: CLI script to promote a user to admin
  - `cleanup_all_tracks.py`, `find_and_remove_duplicate_tracks.py`, `fix_album.py`, `fix_all_albums.py`, `reimport_as_album.py`, `upload_album.py`: Maintenance/migration scripts
- Subdirectories:
  - `music/`: Local music files and `library.db` (Beets catalog)
  - `uploads/`: Temporary upload staging area
  - `waveforms/`: Cached waveform peak data files
  - `.well-known/`: WebAuthn/FIDO2 asset links

**`music-frontend/`:**
- Purpose: React PWA music player served via S3/CloudFront
- Contains: TypeScript React app (Create React App based), CSS, assets
- Note: This is a git submodule (has its own `.git` directory), originally forked from `AKAspanion/music-app`
- Key files:
  - `package.json`: React 17, Redux 4, TypeScript 4, react-scripts 4
  - `tsconfig.json`: TypeScript configuration
  - `public/`: Static assets, manifest.json, favicon
- Source organization (`src/`):
  - `app/index.tsx`: Root App component with routing logic, audio management, playback controls
  - `index.tsx`: Redux store creation, AuthProvider, service worker registration
  - `components/`: Reusable UI components (each with `index.tsx` + `styles.css`)
  - `views/`: Page-level components (home, track, playlist, upload, dashboard, auth, menu, now-playing, shared-playlist)
  - `services/`: API communication layer
  - `redux/`: State management (actions, reducers, types)
  - `hooks/`: Custom React hooks (useDuration, usePrevious, useResize, useScroll)
  - `context/`: React context providers (auth.tsx)
  - `utils/`: Shared utility functions
  - `assets/`: Static images/logos

**`android-app/`:**
- Purpose: Native Android music player
- Contains: Kotlin source, Gradle build files, resources
- Key files:
  - `app/build.gradle.kts`: Build config (compileSdk 34, minSdk 24, Compose, Media3)
  - `settings.gradle`: Project settings
  - `gradle.properties`: Build properties
- Source organization (`app/src/main/java/com/bombest/music/`):
  - `MainActivity.kt`: Entry point, Compose navigation, screen routing
  - `LoginActivity.kt`: Login screen (Activity-based, not Compose)
  - `data/`: Data layer
    - `api/MusicApi.kt`, `api/AuthApi.kt`, `api/PlaylistApi.kt`: Retrofit API interfaces
    - `model/Track.kt`: Track data model
    - `repository/AuthRepository.kt`, `repository/MusicRepository.kt`: Repository pattern
    - `NetworkModule.kt`: Retrofit/OkHttp setup with automatic failover
    - `AuthDataStore.kt`: Token persistence via DataStore
    - `DownloadManager.kt`: Offline download support
    - `FavoritesManager.kt`: Local favorites management
    - `MetricsManager.kt`: Play metrics batching
    - `PasskeyManager.kt`: WebAuthn/passkey auth
    - `S3Repository.kt`: Direct S3 upload support
    - `Track.kt`: Legacy track model (separate from `model/Track.kt`)
  - `ui/screens/`: Compose screens (LibraryScreen, PlayerScreen, PlaylistScreen, UploadScreen, DashboardScreen, AccountScreen, LoginScreen, RegisterScreen, PlayerBar)
  - `ui/components/`: Reusable Compose components (visualizers, progress indicators, haptics settings)
  - `ui/theme/`: Material 3 theming (colors, typography, icons, theme provider)
  - `ui/viewmodel/`: ViewModels (MainViewModel, AuthViewModel, PlaylistViewModel)
  - `service/BombestMediaService.kt`: Media3 MediaSessionService for background playback
  - `haptics/`: Haptic feedback engine (HapticGrooveEngine, HapticPatternLibrary, HapticPreferences, DeviceCapabilityResolver)
  - `visualizer/`: Audio visualizer rendering (GraffitiWaveform, MistRenderer, SplatterBurst, SprayStroke)
  - `utils/`: Utility classes (AudioVisualizer, SongTimer, SongsManager)
  - `model/TrackItem.kt`: Another track model variant

**`ios-app/`:**
- Purpose: Native iOS music player
- Contains: SwiftUI source, Tuist project configuration, Xcode project files
- Key files:
  - `Project.swift`: Tuist project definition (iOS 17.0+, bundle ID `best.bom.beats`)
  - `BombestBeats.entitlements`: App entitlements (associated domains for passkeys)
- Source organization (`Targets/BombestBeats/Sources/`):
  - `BombestApp.swift`: App entry point
  - `MainTabView.swift`: Root tab navigation
  - `Models/Models.swift`: Data models
  - `Services/`: API and platform services
    - `APIService.swift`: Singleton HTTP client (URLSession-based)
    - `AudioService.swift`: AVPlayer-based audio playback
    - `FileCacheService.swift`: Local file caching
    - `ImageCacheService.swift`: Image caching
    - `HapticsManager.swift`: Haptic feedback
    - `MetricsService.swift`: Play metrics
  - `ViewModels/`: MVVM view models (Auth, Dashboard, Library, PlaylistDetail, Search)
  - `Views/`: SwiftUI views (Dashboard, Library, Login, Player, Playlist, Search, Settings, etc.)
  - `Views/Components/`: Reusable components (CachedImage, GraffitiVisualizer, OscilloscopeVisualizer, SprayPaintProgress, VUMeterProgress)
  - `Theme/ThemeProvider.swift`: App theming

**`scripts/`:**
- Purpose: Deployment and operational automation
- Contains: Shell scripts for AWS deployments
- Key files:
  - `deploy-frontend.sh`: Build React app, sync to S3, invalidate CloudFront
  - `ec2-setup-nginx.sh`, `ec2-paste-nginx.sh`: Nginx configuration on EC2
  - `ec2-make-admin.sh`: Promote user to admin on EC2
  - `deploy-cloudfront-function.sh`: Deploy SPA rewrite function
  - `cloudfront-add-alias.sh`: Add alternate domain to CloudFront
  - `add-ssm-permissions.sh`: AWS SSM IAM setup

**`docs/`:**
- Purpose: Operational runbooks and troubleshooting guides
- Contains: Markdown documentation
- Key files: `architecture.md`, `deploy-backend.md`, `deploy-frontend.md`, `deploy-which-script.md`, `troubleshoot-404-upload.md`, `troubleshoot-500-playlists-upload.md`, `cloudfront-403-fix.md`

**`cloudfront-functions/`:**
- Purpose: CloudFront edge functions for SPA routing
- Key file: `beats-spa-rewrite.js` - Rewrites `/beats/*` (non-asset paths) to `/beats/index.html`

## Key File Locations

**Entry Points:**
- `beets-backend/upload_server.py`: Backend API server (Flask, port 8338)
- `music-frontend/src/index.tsx`: Web app bootstrap (Redux store, React render)
- `music-frontend/src/app/index.tsx`: Main App component (routing, audio, state)
- `android-app/app/src/main/java/com/bombest/music/MainActivity.kt`: Android entry
- `ios-app/Targets/BombestBeats/Sources/BombestApp.swift`: iOS entry

**Configuration:**
- `beets-backend/config.yaml`: Local Beets + JWT config
- `beets-backend/config.docker.yaml`: Docker Beets + JWT config
- `beets-backend/requirements.txt`: Python dependencies
- `music-frontend/package.json`: Node dependencies and scripts
- `music-frontend/tsconfig.json`: TypeScript compiler config
- `android-app/app/build.gradle.kts`: Android build (SDK versions, dependencies)
- `ios-app/Project.swift`: iOS build (Tuist, deployment target, entitlements)
- `docker-compose.yml`: Local dev services
- `beets-backend/Dockerfile`: Backend container image
- `nginx-ec2.conf`: Production reverse proxy
- `.github/workflows/pre-merge.yml`: CI pipeline

**API Client Layer:**
- `music-frontend/src/services/beets.ts`: Web API client (library, streaming, playlists, favorites, metrics)
- `music-frontend/src/services/upload.ts`: Web upload client (direct, presigned S3, retry logic)
- `music-frontend/src/services/collaboration.ts`: Web collaboration client (loops, lyrics, comments)
- `music-frontend/src/services/metrics-manager.ts`: Web play metrics batching
- `music-frontend/src/services/audio-session.ts`: Web Media Session API integration
- `music-frontend/src/services/data-store.ts`: Web IndexedDB persistence via localforage
- `android-app/.../data/api/MusicApi.kt`: Android Retrofit API interface
- `android-app/.../data/api/AuthApi.kt`: Android auth API interface
- `android-app/.../data/api/PlaylistApi.kt`: Android playlist API interface
- `android-app/.../data/NetworkModule.kt`: Android HTTP client with failover
- `ios-app/.../Services/APIService.swift`: iOS API client singleton

**State Management:**
- `music-frontend/src/redux/reducers/app.ts`: View state (current view)
- `music-frontend/src/redux/reducers/song.ts`: Track list
- `music-frontend/src/redux/reducers/playState.ts`: Playback state (index, playing)
- `music-frontend/src/redux/reducers/settings.ts`: User preferences (theme, shuffle, repeat)
- `music-frontend/src/redux/reducers/playlist.ts`: Playlist state
- `music-frontend/src/context/auth.tsx`: Auth context (user, token, login/logout)
- `android-app/.../ui/viewmodel/MainViewModel.kt`: Android main state
- `android-app/.../ui/viewmodel/AuthViewModel.kt`: Android auth state
- `android-app/.../ui/viewmodel/PlaylistViewModel.kt`: Android playlist state

**Database:**
- `beets-backend/init_db.py`: Schema definition and migration for users.db
- `beets-backend/db_path.py`: users.db path resolution
- `beets-backend/music/library.db`: Beets music catalog (auto-generated)

## Naming Conventions

**Files:**
- Backend Python: `snake_case.py` (e.g., `upload_server.py`, `init_db.py`, `db_path.py`)
- Frontend components: `kebab-case/` directories with `index.tsx` + `styles.css` (e.g., `components/loop-controller/index.tsx`)
- Frontend services: `kebab-case.ts` (e.g., `audio-session.ts`, `metrics-manager.ts`)
- Frontend views: `kebab-case/` directories with `index.tsx` + `styles.css` (e.g., `views/now-playing/index.tsx`)
- Android Kotlin: `PascalCase.kt` (e.g., `MusicApi.kt`, `MainViewModel.kt`, `LibraryScreen.kt`)
- iOS Swift: `PascalCase.swift` (e.g., `APIService.swift`, `LibraryView.swift`)

**Directories:**
- Backend: `snake_case` (e.g., `beets-backend/`)
- Frontend: `kebab-case` (e.g., `music-frontend/`, `now-playing/`)
- Android: `lowercase` packages following Java convention (e.g., `data/api/`, `ui/screens/`, `ui/viewmodel/`)
- iOS: `PascalCase` (e.g., `Sources/`, `Views/`, `ViewModels/`, `Services/`)

## Where to Add New Code

**New Backend API Endpoint:**
- Add route handler to `beets-backend/upload_server.py` (find the relevant section by feature area)
- If it needs auth: use `@jwt_required()` decorator or `@admin_required()` for admin-only
- If it needs a new database table: add schema to `beets-backend/init_db.py` and add migration logic

**New Web Frontend Feature:**
- New view/page: Create `music-frontend/src/views/{feature-name}/index.tsx` + `styles.css`, export from `music-frontend/src/views/index.ts`
- New component: Create `music-frontend/src/components/{component-name}/index.tsx` + `styles.css`
- New API method: Add to `music-frontend/src/services/beets.ts` (or create a new service file in `services/`)
- New Redux state: Add action type to `music-frontend/src/redux/types/index.ts`, create reducer in `music-frontend/src/redux/reducers/`, export from `music-frontend/src/redux/reducers/index.ts`
- New hook: Add to `music-frontend/src/hooks/`, export from `music-frontend/src/hooks/index.ts`

**New Android Feature:**
- New screen: Create `android-app/.../ui/screens/{FeatureName}Screen.kt`, add to `Screen` enum in `MainActivity.kt`
- New API endpoint: Add method to relevant interface in `android-app/.../data/api/`
- New ViewModel: Create `android-app/.../ui/viewmodel/{Feature}ViewModel.kt`
- New reusable component: Add to `android-app/.../ui/components/`
- New data model: Add to `android-app/.../data/model/`

**New iOS Feature:**
- New view: Create `ios-app/Targets/BombestBeats/Sources/Views/{FeatureName}View.swift`
- New ViewModel: Create `ios-app/Targets/BombestBeats/Sources/ViewModels/{Feature}ViewModel.swift`
- New service: Create `ios-app/Targets/BombestBeats/Sources/Services/{Feature}Service.swift`
- New component: Add to `ios-app/Targets/BombestBeats/Sources/Views/Components/`

**New Deployment Script:**
- Add to `scripts/` directory
- Follow existing pattern: `#!/usr/bin/env bash`, `set -e`, use `SCRIPT_DIR` for relative paths

## Special Directories

**`music-frontend/` (git submodule):**
- Purpose: Web frontend (originally forked from AKAspanion/music-app)
- Generated: No
- Committed: Yes (as submodule reference)
- Note: Has its own `.git` directory; changes must be committed in the submodule first

**`beets-backend/music/`:**
- Purpose: Local music files and Beets library database
- Generated: Partially (library.db is generated by `beet import`)
- Committed: Music files are committed; library.db should not be (generated)

**`beets-backend/uploads/`:**
- Purpose: Temporary staging for uploaded files
- Generated: Yes (created at runtime)
- Committed: No (empty directory)

**`beets-backend/waveforms/`:**
- Purpose: Cached waveform peak data JSON files
- Generated: Yes (created on first access per track)
- Committed: Some cached files present

**`music-frontend/build/`:**
- Purpose: Production build output
- Generated: Yes (by `npm run build`)
- Committed: Some build artifacts present (should be in .gitignore)

**`android-app/build/`, `android-app/.gradle/`:**
- Purpose: Gradle build cache and output
- Generated: Yes
- Committed: No (in .gitignore)

**`ios-app/Derived/`:**
- Purpose: Tuist-generated source files (asset accessors, bundle accessors)
- Generated: Yes (by `tuist generate`)
- Committed: Yes (Tuist convention)

**`musify-app/`, `spotube-app/`:**
- Purpose: Experimental/alternative app clients (Musify fork, Spotube fork)
- Generated: No
- Committed: These are git submodules pointing to forked repos, not actively maintained

---

*Structure analysis: 2026-03-29*
