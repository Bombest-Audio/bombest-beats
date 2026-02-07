# Bombest Beats (MusicPlayer Integration)

This Android app is based on the MusicPlayer codebase and is wired to the Bombest Beats backend. It replaces the original Bombest Beats Android client.

## Backend targets
- Primary: `https://bom.best/api/`
- Failover: `http://beats-aws.bom.best/api/`

## Features
- Fetch library from `/api/library`
- Stream tracks via `/api/stream/{id}`
- Simple shuffle/repeat controls with on-device MediaPlayer
- Visualizer on the playback screen

## Build
```bash
./gradlew assembleDebug
```

