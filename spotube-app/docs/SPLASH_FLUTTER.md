# Flutter Animated Splash Screen

This document explains how the Bombest Beats animated splash screen works and how to customize it.

## Overview

The splash experience consists of two phases:

1. **Native Splash** (instant): System-level splash with bomb logo on `#0B0E23` background
2. **Flutter Animated Splash** (900-1200ms): Custom animation with spray reveal, fuse spark, and drip settle effects

## Architecture

```
Native Splash (0ms)
    ↓
Flutter Animated Splash (1100ms)
    ↓
Main App (RootAppRoute)
```

## Files

- `lib/ui/splash/bombest_splash_screen.dart` - Main splash widget with animations
- `lib/ui/splash/painters/spray_reveal_painter.dart` - Spray paint reveal effect
- `lib/ui/splash/painters/drip_painter.dart` - Drip settle animation
- `lib/ui/splash/painters/spark_painter.dart` - Fuse spark/starburst effect
- `lib/pages/splash/bombest_splash_route.dart` - Route wrapper with navigation logic
- `assets/images/bomb_logo.png` - Main logo asset
- `assets/images/grain.png` - Optional grain texture overlay
- `assets/images/spark.png` - Optional spark asset (currently using CustomPainter)

## Animation Timeline

The splash animation runs for **1100ms** total with the following sequence:

- **0-160ms**: Logo fade/scale in (0.92 → 1.02 → 1.0)
- **140-650ms**: Fuse spark pulses (scale/rotation + glow pulse)
- **250-850ms**: Spray reveal sweep (gradient wipe across logo)
- **600-950ms**: Drips settle (2-4px downward easing)
- **900-1100ms**: Fade out + route transition

## Updating Splash Assets

### Changing the Logo

1. Replace `assets/images/bomb_logo.png` with your new logo
2. Ensure it's square (recommended: 1024x1024px)
3. Run `flutter pub get` to refresh assets
4. The native splash will automatically use the new logo on next build

### Regenerating Native Splash

After updating assets or `pubspec.yaml` splash configuration:

```bash
dart run flutter_native_splash:create
```

This regenerates all platform-specific splash assets (Android, iOS, Web).

### Native Splash Configuration

Edit `pubspec.yaml`:

```yaml
flutter_native_splash:
  color: "#0B0E23"  # Background color
  image: assets/images/bomb_logo.png
  android_gravity: center
  android_12:
    image: assets/images/bomb_logo.png
    color: "#0B0E23"
    icon_background_color: "#0B0E23"
  fullscreen: true
```

## Adjusting Animation Timing

Edit `lib/ui/splash/bombest_splash_screen.dart`:

### Total Duration

Change the `AnimationController` duration:

```dart
_controller = AnimationController(
  duration: const Duration(milliseconds: 1100), // Change this
  vsync: this,
);
```

### Individual Animation Intervals

Modify the `Interval` values in each animation:

```dart
// Logo fade/scale: currently 0.0 to 0.145 (0-160ms)
_logoFadeScale = ...animate(
  CurvedAnimation(
    parent: _controller,
    curve: const Interval(0.0, 0.145, curve: Curves.easeOut), // Adjust here
  ),
);

// Spark: currently 0.127 to 0.591 (140-650ms)
_sparkProgress = CurvedAnimation(
  parent: _controller,
  curve: const Interval(0.127, 0.591, curve: Curves.easeInOut), // Adjust here
);
```

### Animation Curves

Change easing curves for different feel:

- `Curves.easeOut` - Fast start, slow end
- `Curves.easeInOut` - Smooth acceleration/deceleration
- `Curves.easeIn` - Slow start, fast end
- `Curves.elasticOut` - Bouncy effect
- `Curves.bounceOut` - Bounce effect

## Modifying Visual Effects

### Colors

Edit the gradient colors in `spray_reveal_painter.dart`:

```dart
final gradient = LinearGradient(
  colors: [
    const Color(0xFFFF6B35), // Orange - change this
    const Color(0xFFFF4081), // Magenta/Pink - change this
    const Color(0xFFC855FF), // Purple - change this
  ],
);
```

### Drip Count and Size

Edit `drip_painter.dart` to add/remove/modify drips:

```dart
final drips = [
  _DripInfo(
    startX: logoPosition.dx + logoSize.width * 0.3,
    startY: logoPosition.dy + logoSize.height * 0.85,
    width: 8.0,  // Change size
    height: 12.0, // Change size
    delay: 0.0,   // Change timing
  ),
  // Add more drips here...
];
```

### Spark Effect

Modify spark appearance in `spark_painter.dart`:

```dart
final rayCount = 12;        // Number of rays
final outerRadius = 30.0;   // Ray length
final innerRadius = 10.0;   // Inner radius
final strokeWidth = 2.5;    // Ray thickness
```

## Dev Mode Flags

The splash route includes debug mode handling in `bombest_splash_route.dart`:

```dart
if (kDebugMode && !kProfileMode) {
  // Option 1: Skip entirely
  // WidgetsBinding.instance.addPostFrameCallback((_) {
  //   context.router.pushReplacement(const RootAppRoute());
  // });
  // return const SizedBox.shrink();
  
  // Option 2: Shorten duration (modify splash screen widget)
}
```

To skip splash in debug mode, uncomment the skip code. To shorten it, modify the `AnimationController` duration conditionally.

## Performance Tips

The splash screen already includes:

- `RepaintBoundary` around animated layers
- Image precaching in `initState`
- `const` constructors where possible
- GPU-friendly transforms (scale, translate, opacity)

To further optimize:

1. Reduce logo size if too large (currently 280x280)
2. Disable grain overlay if not needed (set opacity to 0)
3. Simplify CustomPainter logic if experiencing jank
4. Use `kReleaseMode` checks to disable effects in debug

## Testing

### Checklist

- [ ] Native splash shows instantly (no white flash)
- [ ] Animation runs smoothly (60fps, no jank)
- [ ] Total time < 1.2s on modern devices
- [ ] Navigation to main app works correctly
- [ ] No back navigation to splash (uses `pushReplacement`)
- [ ] Dev mode flag works (if enabled)
- [ ] Works on Android 12+ and older
- [ ] Works on iOS
- [ ] PWA shows splash route correctly

### Testing on Device

```bash
# Build and install
flutter build apk --debug
adb install build/app/outputs/flutter-apk/app-stable-debug.apk

# Launch and observe splash timing
adb shell monkey -p com.bombest.spotube -c android.intent.category.LAUNCHER 1
```

## Troubleshooting

### Splash Stuck / Not Transitioning

- Check that `onComplete` callback is called
- Verify routing configuration in `routes.dart`
- Ensure `BombestSplashRoutePage` is marked as `initial: true`

### White Flash Before Splash

- Verify native splash assets are generated: `dart run flutter_native_splash:create`
- Check `pubspec.yaml` splash configuration
- Ensure `FlutterNativeSplash.preserve()` is called in `main()`

### Animation Jank

- Check device performance (use release mode for testing)
- Reduce logo size or simplify CustomPainter logic
- Ensure `RepaintBoundary` is wrapping animated layers
- Profile with Flutter DevTools

### Assets Not Loading

- Verify assets are listed in `pubspec.yaml`
- Run `flutter pub get`
- Check asset paths match exactly (case-sensitive)

## Customization Examples

### Faster Splash (600ms)

```dart
_controller = AnimationController(
  duration: const Duration(milliseconds: 600),
  vsync: this,
);
// Adjust all Interval values proportionally
```

### Different Background Color

```dart
// In bombest_splash_screen.dart
Container(
  color: const Color(0xFF1A1F28), // Change this
  ...
)
```

### Disable Grain Overlay

```dart
// Comment out or remove grain layer in Stack
// if (_grainImage != null)
//   Positioned.fill(...)
```

## References

- [flutter_native_splash package](https://pub.dev/packages/flutter_native_splash)
- [Flutter Animation Guide](https://docs.flutter.dev/development/ui/animations)
- [CustomPainter Documentation](https://api.flutter.dev/flutter/rendering/CustomPainter-class.html)

