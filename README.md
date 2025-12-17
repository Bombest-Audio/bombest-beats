# Bombest Beats

A cross-platform music streaming application with Android, iOS, and web clients connecting to a self-hosted Beets backend.

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
                    │   Cloudflare Tunnel     │
                    │   beats.bom.best        │
                    └────────────┬────────────┘
                                 │
                    ┌────────────┴────────────┐
                    │   Backend (Flask/Beets) │
                    │   Port 8338             │
                    └─────────────────────────┘
```

## Quick Start

### Backend
```bash
cd beets-backend
./venv/bin/python upload_server.py
```

### Cloudflare Tunnel
```bash
cloudflared tunnel --config cloudflared-config.yml run bombest-beats
```

### Android
```bash
cd android-app
./gradlew assembleDebug
```

### iOS
```bash
cd ios-app
tuist generate
# Open BombestBeats.xcworkspace in Xcode
```

## Project Structure

```
bombest-beats/
├── android-app/          # Android (Kotlin/Jetpack Compose)
├── ios-app/              # iOS (SwiftUI)
├── beets-backend/        # Python/Flask backend
├── music-frontend/       # React web app
└── cloudflared-config.yml
```

## Key Configuration

| Item | Value |
|------|-------|
| Backend URL | `https://beats.bom.best` |
| Tunnel ID | `4a638fa7-cbe1-453c-b360-95c56d17eaca` |
| iOS Bundle ID | `best.bom.beats` |
| iOS Team ID | `8C4A8V568P` |
| Passkey RP ID | `beats.bom.best` |

## Theme System

Two themes are implemented:

| Theme | Progress Style | Visualizer |
|-------|----------------|------------|
| Graffiti | Spray Paint | Spray Paint Bars |
| Studio Dust | VU Meter | Oscilloscope |

## Development Notes

- **Passkeys**: Registered passkeys are domain-specific. If URL changes, re-register.
- **Album Art**: Currently no art in database. Placeholder images are used.
- **Background Playback**: WAKE_LOCK permission enabled for Android.

## See Also

- [architecture.md](./architecture.md) - Detailed system architecture
- [.agent/workflows/](../.agent/workflows/) - Development workflows
