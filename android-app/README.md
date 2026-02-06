# Bombest Beats (MusicPlayer Integration)

This branch replaces the original Android client with the MusicPlayer codebase and wires it to the Bombest Beats backend.

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

