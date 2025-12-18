# Bombest Beats

A cross-platform music streaming application with Android, iOS, and web clients connecting to a self-hosted Beets backend with AWS failover.

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
                    └────────────┬────────────┘
                                 │
            ┌────────────────────┼────────────────────┐
            ▼                                         ▼
┌─────────────────────┐                 ┌─────────────────────┐
│  Home Server        │                 │  AWS EC2 (Failover) │
│  (Cloudflare Tunnel)│                 │  beats-aws.bom.best │
│  bombest-beats      │                 │  44.249.110.172     │
└─────────────────────┘                 └─────────────────────┘
            │                                         │
            └───────────────┬─────────────────────────┘
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
| Primary URL | `https://beats.bom.best` |
| Failover URL | `https://beats-aws.bom.best` |
| EC2 Instance | `i-03ac11ce0a84a2625` (44.249.110.172) |
| S3 Bucket | `s3://bombest-beats-music` |
| Docker Image | `thomasphillips3/bombest-beats:latest` |
| Tunnel ID | `4a638fa7-cbe1-453c-b360-95c56d17eaca` |

## Quick Start

### Backend (Local)
```bash
cd beets-backend && ./venv/bin/python upload_server.py
```

### Cloudflare Tunnel
```bash
cloudflared tunnel --config cloudflared-config.yml run bombest-beats
```

### Deploy to AWS
```bash
./deploy-aws.sh  # Build & push Docker image
./deploy-ec2.sh  # Launch EC2 instance
./setup-s3.sh    # Sync music to S3
```

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
├── deploy-aws.sh         # Docker Hub push script
├── deploy-ec2.sh         # AWS EC2 deployment
├── setup-s3.sh           # S3 bucket & music sync
└── cloudflared-config.yml
```

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

## Development Notes

- **Passkeys**: Domain-specific. Re-register if URL changes.
- **Download Mode**: Both platforms support offline playback via local caching.
- **Background Playback**: WAKE_LOCK (Android) / AVAudioSession (iOS) enabled.

## Key Features

### 🎵 Playlist Management
- Create, delete, and browse custom playlists.
- Play an entire playlist from the list view or the detail screen.
- Drag-and-drop bucket for adding tracks to playlists (Android).

### 🏷️ Metadata Editing
- Batch edit ID3 tags (Title, Artist, Album) for multiple tracks.
- Changes are persisted to both the Beets database and the audio files.
- Long-press tracks in the library to enter selection mode.

### 📤 File Upload
- Supports MP3, WAV, FLAC, M4A, OGG formats.
- **Direct Upload (Tailscale)**: Bypass Cloudflare for large files (>30MB) to avoid 100s timeout.
- **Standard Upload**: Via Cloudflare tunnel for smaller files.
- Comprehensive logging for debugging upload issues (check `adb logcat | grep UploadScreen`).

## Troubleshooting

### Upload Issues

**Error 524 (Timeout):**
- Cloudflare has a 100-second timeout for uploads
- Enable "Direct Upload (Tailscale)" for large files
- Ensure Tailscale IP in UploadScreen.kt matches your server (`tailscale ip -4`)

**Error 530 (Origin Unreachable):**
- Check backend is running: `ps aux | grep upload_server.py`
- Verify Cloudflare tunnel: `cloudflared tunnel info bombest-beats`
- Check tunnel logs: `tail -f cloudflare.log`

**Connection Refused (Tailscale):**
- Verify correct Tailscale IP is hardcoded in `UploadScreen.kt`
- Check server is listening on `0.0.0.0:8338`
- Ensure phone and server are on same Tailscale network

