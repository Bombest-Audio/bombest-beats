# Release signing (Bombest Beats Android)

Release AAB/APK builds use a keystore configured **only** via environment variables (never committed).

## Required environment variables

| Variable | Description |
|----------|-------------|
| `BOMBEST_RELEASE_STORE_FILE` | Absolute path to your `.jks` or `.keystore` file (must exist on disk) |
| `BOMBEST_RELEASE_STORE_PASSWORD` | Keystore password |
| `BOMBEST_RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `BOMBEST_RELEASE_KEY_PASSWORD` | Private key password (often same as store password) |

## Example (placeholders only)

```bash
export BOMBEST_RELEASE_STORE_FILE="$HOME/keys/bombest-upload.jks"
export BOMBEST_RELEASE_STORE_PASSWORD="your-store-password"
export BOMBEST_RELEASE_KEY_ALIAS="bombest"
export BOMBEST_RELEASE_KEY_PASSWORD="your-key-password"
```

Then from this directory (`android-app/`):

```bash
./gradlew bundleRelease
./gradlew assembleRelease
```

## Output locations

After a successful build (paths may vary slightly by AGP version):

- **AAB:** `app/build/outputs/bundle/release/app-release.aab`
- **APK:** `app/build/outputs/apk/release/app-release.apk`

## Sideload on a device (Pixel)

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Adjust the APK filename if Gradle uses a different output name.

## Smoke checklist (physical device)

- [ ] App installs without errors
- [ ] Opens to login / library
- [ ] Stream and play a track
- [ ] Open player screen; optional: visualizer, haptics

Android Auto full verification is optional for this minimal smoke pass.

## If release tasks fail immediately

If you see `Release signing not configured`, either export all four variables and ensure the keystore file exists, or you are running `bundleRelease` / `assembleRelease` without a keystore (debug builds use `./gradlew assembleDebug` and do not need these variables).
