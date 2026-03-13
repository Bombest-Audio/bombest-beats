# Bombest Beats (MusicPlayer Integration)

This Android app is based on the MusicPlayer codebase and is wired to the Bombest Beats backend. It replaces the original Bombest Beats Android client.

## Backend targets
- Primary: `https://beats.bom.best/`
- Failover: `https://beats-aws.bom.best/`

## Features
- Fetch library from `/library` (relative to API base)
- Stream tracks via `/stream/{id}` (relative to API base)
- Simple shuffle/repeat controls with on-device MediaPlayer
- Visualizer on the playback screen

## Build
```bash
./gradlew assembleDebug
```

