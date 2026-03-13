# Bombest Beats

A cross-platform music streaming application with Android, iOS, and web clients connecting to a Beets backend hosted on AWS EC2.

## Architecture Overview

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Android App   │     │    iOS App      │     │   Web Frontend  │
│   (Kotlin)      │     │   (SwiftUI)     │     │   (React/TS)    │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                    ┌────────────┴────────────┐
                    │   Cloudflare (Proxied)  │
                    │   beats.bom.best        │
                    │   beats-aws.bom.best     │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────┐
                    │   AWS EC2           │
                    │   bombest-beats     │
                    └─────────────┬───────┘
                                  │
                                  ▼
                    ┌─────────────────────┐
                    │   AWS S3 Storage    │
                    │   bombest-beats-    │
                    │   music             │
                    └─────────────────────┘
```

## Infrastructure

| Component | Value |
|-----------|-------|
| API URL | `https://beats.bom.best/` |
| Failover URL | `https://beats-aws.bom.best/` |
| S3 Bucket | `s3://bombest-beats-music` |
| Docker Image | `tomdabomb2u/bombest-beats:latest` |

Full EC2, S3, tunnel, and admin setup: see [docs/architecture.md](docs/architecture.md).

## Quick Start

### Backend (Local)
```bash
cd beets-backend && ./venv/bin/python upload_server.py
```

### Deploy to AWS
- **Backend:** `./deploy-to-ec2.sh [REGION]` — build image, push to Docker Hub, update container on EC2 (requires existing EC2 instance with tag `Name=bombest-beats`).
- **Frontend:** `./scripts/deploy-frontend.sh` — build and deploy bom.best/beats to S3 (`bombest-beats-web`) and CloudFront. Set `CLOUDFRONT_DIST_ID=E1RBYOEP5K0UI3` for cache invalidation.
- **First-time:** run `./deploy-aws.sh`, then create EC2 via `./setup-ec2-aws-cli.sh`; see [docs/architecture.md](docs/architecture.md).

### Android
```bash
cd android-app && ./gradlew assembleDebug
```

### iOS
```bash
cd ios-app && tuist generate
# Open BombestBeats.xcworkspace in Xcode
```

## Project Structure

```
bombest-beats/
├── android-app/          # Android (Kotlin/Jetpack Compose)
├── ios-app/              # iOS (SwiftUI)
├── beets-backend/        # Python/Flask backend + Dockerfile
├── music-frontend/       # React web app
├── scripts/              # e.g. ec2-make-admin.sh
├── deploy-to-ec2.sh      # One-command deploy (build, push, update EC2)
├── deploy-aws.sh         # Docker Hub push script
├── deploy-container-to-ec2.sh
├── setup-ec2-aws-cli.sh
├── setup-s3.sh           # S3 bucket & music sync
├── docs/architecture.md
├── docs/deploy-which-script.md
├── deploy.sh             # deprecated (home server)
├── deploy_docker.sh      # deprecated (home server)
└── cloudflared-config.yml  # deprecated (home server tunnel)
```

## Documentation

- [docs/architecture.md](docs/architecture.md) — Deployment, S3 sync, EC2 admin
- [docs/deploy-which-script.md](docs/deploy-which-script.md) — Deploy script reference
- [docs/deploy-backend.md](docs/deploy-backend.md) — Backend deploy, nginx CORS, SSM permissions, manual fallback
- [docs/deploy-frontend.md](docs/deploy-frontend.md) — Frontend deploy (S3/CloudFront)

## Client Connection (Static Endpoints)

- API base (primary): `https://beats.bom.best/`
- API base (failover): `https://beats-aws.bom.best/`
- DNS hostnames served via Cloudflare; traffic routes to EC2 backend

These endpoints are stable; mobile and web clients can use them as defaults. Clients may optionally override the API base via configuration (e.g. `REACT_APP_API_BASE`) for local or dev testing. Tunnel IDs, credential files, and other infrastructure-specific details are managed via deployment configuration and internal documentation and may change without affecting these public URLs.

## Theme System

| Theme | Progress Style | Visualizer |
|-------|----------------|------------|
| Graffiti | Spray Paint | Spray Paint Bars |
| Studio Dust | VU Meter | Oscilloscope |

## Monthly Cost

| Service | Estimated Cost |
|---------|---------------|
| EC2 t3.micro | ~$8/mo (or free tier) |
| S3 (1.3GB) | ~$0.03/mo |
| **Total** | **~$8/mo** |

## Pre-merge checks

Comment on a PR to run tests (only members/owners/collaborators can trigger). After the first run, add the "Pre-merge tests" status check to branch protection for `main` in **Settings → Branches**.

- **🚀** — Run all tests (Android, backend, frontend) and merge into **main** on success (PR must target `main`).
- **`:run-tests:`** — Run all tests without merging (any PR).
- **`:run-test: android`** — Run only the Android build.
- **`:run-test: frontend`** — Run only the frontend build.
- **`:run-test: backend`** — Run only the backend sanity check.

## Development Notes

- **Passkeys**: Domain-specific. Re-register if URL changes.
- **Download Mode**: Both platforms support offline playback via local caching.
- **Background Playback**: WAKE_LOCK (Android) / AVAudioSession (iOS) enabled.

