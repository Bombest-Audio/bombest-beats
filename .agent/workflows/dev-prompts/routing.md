---
description: System-Aware Audio Routing UI (Android)
---

# System-Aware Audio Routing UI (Android)

## Purpose

Implement a polished **System-Aware Audio Routing UI** that:

* Detects the active audio output route (speaker, wired headset, Bluetooth device, USB DAC, cast target)
* Displays routing status in the UI (Now Playing + Mini Player)
* Allows quick switching where possible (and gracefully falls back to system picker when not)

This feature highlights Android’s openness and is a strong “Android-native” differentiator vs Spotify-like clones.

---

## User Experience Goals

### Always know where audio is going

* Show current route label next to playback controls:

  * `Phone speaker`
  * `Wired headphones`
  * `Bluetooth: Pixel Buds Pro`
  * `USB DAC: FiiO KA3`
  * `Cast: Living Room TV`

### One-tap route switching

* Tapping the route label opens a **Route Picker** bottom sheet.
* The sheet lists available outputs with:

  * icon
  * device name
  * connection type
  * status (connected / available)

### Smart behavior

* If a device disconnects mid-playback, update UI instantly.
* If switching is not allowed programmatically, open system UI.

---

## Key Requirements

### Where to surface routing

1. **Now Playing screen**

   * Route chip/button near play controls
2. **Persistent Mini Player**

   * Small route indicator (icon + short name)
3. **Settings**

   * Advanced routing info + troubleshooting

### Route change feedback

* UI shows a brief toast/snackbar:

  * “Switched to Bluetooth: Pixel Buds Pro”
  * “USB audio connected”

---

## Android APIs & Concepts

### Audio routing detection

* `AudioManager`
* `AudioDeviceCallback`
* `AudioDeviceInfo`

### Media routing / output switching

Depending on your player stack:

* If using ExoPlayer / Media3: integrate with Media3 routing where applicable.
* Use Android’s media routing components if available in your stack.

### Bluetooth

* Device names come from `AudioDeviceInfo` or Bluetooth APIs.

### USB Audio

* Detect USB output devices via `AudioDeviceInfo.TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`

### Cast

* If cast is supported, integrate with MediaRouter / Cast SDK.
* If not supported, route UI should omit cast entries.

---

## Architecture

```
AudioRouteController
 ├── RouteStateStore
 │    ├── activeRoute
 │    ├── availableRoutes
 │    └── lastChangeTimestamp
 ├── AudioDeviceMonitor (AudioDeviceCallback)
 ├── RoutePickerCoordinator
 └── RouteSwitcher (best-effort switching)

UI
 ├── RouteChip (Now Playing)
 ├── MiniRouteIndicator (Mini Player)
 └── RoutePickerBottomSheet
```

---

## Data Model

### Route

* `id` (stable)
* `name` (user friendly)
* `type` (SPEAKER | WIRED | BLUETOOTH | USB | CAST)
* `icon`
* `isActive`
* `isAvailable`
* `canSwitchDirectly` (boolean)

---

## Detection Workflow

### Step 1 — Initial scan

On app start / playback start:

* Query `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`
* Build available route list
* Determine active route

### Step 2 — Listen for changes

Register `AudioDeviceCallback`:

* update routes on add/remove
* update active route when changes occur

### Step 3 — Update UI state

Push route updates to `RouteStateStore` which the UI observes.

---

## Switching Workflow

### Primary approach: direct switching (best-effort)

If your playback stack supports explicit routing:

* expose a method to set output route

### Fallback approach: system output picker

When direct switching is not supported:

* open Android’s system route/output selector UI

Rules:

* If route switching is not allowed (security/OS), never fake it.
* Always prefer honest UX.

---

## UI: Route Picker Bottom Sheet

### Contents

* Header: “Audio output”
* Active route card pinned to top
* List of available routes

Each item shows:

* icon
* device name
* connection type label

### Interactions

* Tap a route:

  * if `canSwitchDirectly` → switch + close sheet
  * else → open system picker

### Empty states

* If only speaker available:

  * show speaker as active
  * hide list divider

---

## Playback Integration

### Route-aware UI updates

* Mini player subtitle (optional): “Playing on Pixel Buds Pro”
* Now Playing: route chip always visible

### Error handling

If switching fails:

* show snack: “Couldn’t switch output. Open system audio settings?”

---

## Permissions & Privacy

* Do not request unnecessary permissions.
* Device names are OK to display locally.
* Do not upload device names to servers.

---

## Performance

* Device callback events are low frequency.
* Use debouncing if rapid add/remove events occur.
* Keep UI updates efficient.

---

## Testing Plan

### Manual test matrix

* Phone speaker
* Wired headphones
* Bluetooth earbuds
* USB DAC
* Bluetooth disconnect while playing

### Automated tests

* Route list mapping unit tests
* UI state rendering tests

---

## Acceptance Criteria

* UI always accurately reflects active output.
* Route picker opens quickly and looks polished.
* Switching is best-effort but never misleading.
* Works across common device types (speaker, wired, BT, USB).
* Does not break playback.

---

## Future Enhancements

* Per-route EQ presets
* Auto-switch rules (e.g., prefer BT)
* Route change haptic feedback

---

## Notes for AI Agents

* Keep route logic isolated in a controller.
* UI should observe a single route state store.
* Provide fallbacks where direct route switching isn’t possible.
* Comment OS/version behaviors.

Build it like a Pixel feature — clean, honest, instantly useful.
