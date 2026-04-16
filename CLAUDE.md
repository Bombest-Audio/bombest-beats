# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bombest Beats is a cross-platform music streaming app with a Python/Flask backend (Beets library management + S3 streaming), React/TypeScript web frontend, Android app (Kotlin/Jetpack Compose), and iOS app (SwiftUI/Tuist). The backend runs in Docker on AWS EC2 behind Cloudflare-proxied DNS (`beats.bom.best`), with music stored in S3 and the web frontend served via S3/CloudFront (`bom.best/beats`).

## Build & Run Commands

### Backend (Python/Flask)
```bash
cd beets-backend && ./venv/bin/python upload_server.py    # Local dev → port 8338
pip install -r beets-backend/requirements.txt              # Install deps (Python 3.12, CI uses 3.11)
python -c "import upload_server; print('OK')"              # CI sanity check
docker build --platform linux/amd64 -t bombest-beats:latest ./beets-backend  # Docker build
```

### Frontend (React/TypeScript)
```bash
cd music-frontend && npm ci && npm start                   # Local dev → port 3000
NODE_OPTIONS=--openssl-legacy-provider npm run build        # Production build (--openssl-legacy-provider required)
# Full prod build with env vars:
PUBLIC_URL=/beats GENERATE_SOURCEMAP=false REACT_APP_API_BASE=https://beats.bom.best NODE_OPTIONS=--openssl-legacy-provider npm run build
```

### Android (Kotlin/Gradle)
```bash
cd android-app && ./gradlew assembleDebug                  # Debug build (CI: --no-daemon)
```

### iOS (SwiftUI/Tuist)
```bash
cd ios-app && tuist generate                               # Generate Xcode project, then build in Xcode
```

### Docker Compose (local dev)
```bash
docker-compose up                                          # Backend (8338) + nginx frontend (8080)
```

### Deployment
```bash
./deploy-to-ec2.sh [REGION]                                # Full backend: build + push Docker Hub + update EC2 via SSM
CLOUDFRONT_DIST_ID=E1RBYOEP5K0UI3 ./scripts/deploy-frontend.sh  # Frontend: build + S3 sync + CloudFront invalidation
```

## Architecture

### Network flow
```
Clients (Android, iOS, Web) → Cloudflare DNS → EC2 nginx (port 80) → Flask (port 8338) → S3
```

### Backend (`beets-backend/upload_server.py`)
Single Flask file serving as the entire API. Key responsibilities:
- **Library/streaming**: `/library`, `/stream/<id>`, `/track/<id>/art`, `/waveform/<id>`
- **Upload**: `/upload` (direct, ≤500MB), `/upload/presign` (S3 presigned URLs for larger files), `/upload/process` (finalize)
- **Auth**: JWT tokens via `/auth/login` (password or passkey). Roles: `user` (default), `admin` (upload/edit)
- **Playlists**: CRUD + sharing via token

**Databases** (both SQLite):
- `library.db` — Beets-managed music library (auto-created on import)
- `users.db` — Users, playlists, playlist_tracks, loops, shares. Location resolved by `db_path.py` (`$DATA_DIR/users.db` in Docker, `music/users.db` locally)

### Frontend (`music-frontend/`)
React 17 + TypeScript 4 with Redux state management. Key services:
- `src/services/beets.ts` — API client; auto-detects dev vs prod backend via `REACT_APP_API_BASE`
- `src/services/upload.ts` — Upload with presigned S3 URLs, batching, retry (3 attempts), parallel uploads (3 concurrent)

### Mobile apps
- **Android** (`android-app/`): Compose UI, Media3/ExoPlayer playback, Retrofit API, presigned S3 uploads, passkey auth. compileSdk 34, minSdk 24, Kotlin 1.9.22.
- **iOS** (`ios-app/`): SwiftUI, AVPlayer, iOS 17.0+. Tuist-managed project.

## CI/CD

PR tests triggered by comments (members/owners/collaborators only):
- `🚀` — Run all tests + auto-merge to main
- `:run-tests:` — Run all tests, no merge
- `:run-test: android|frontend|backend` — Single suite

Tests: Android `assembleDebug`, backend import check, frontend `npm run build`.

## Key Environment Variables

| Variable | Used by | Purpose |
|----------|---------|---------|
| `REACT_APP_API_BASE` | Frontend | Backend URL override (default: auto-detect) |
| `PUBLIC_URL` | Frontend | Path prefix (`/beats` for S3 deploy) |
| `S3_BUCKET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `S3_REGION` | Backend | S3 music storage |
| `DATA_DIR` | Backend | Directory for `users.db` (Docker: `/app/data`) |
| `FLASK_ENV` | Backend | `development` gates localhost CORS origins |

## Infrastructure

| Resource | Value |
|----------|-------|
| API | `https://beats.bom.best/` (Cloudflare → EC2) |
| Web app | `https://bom.best/beats/` (CloudFront → S3) |
| S3 music | `bombest-beats-music` (us-west-2) |
| S3 web | `bombest-beats-web` (us-east-1) |
| CloudFront | `E1RBYOEP5K0UI3` |
| Docker image | `tomdabomb2u/bombest-beats:latest` |
| EC2 data dir | `/data/beets` (bind-mounted as `/app/music`) |
