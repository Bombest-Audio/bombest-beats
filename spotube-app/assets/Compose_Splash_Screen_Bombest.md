# Compose-Only Splash Screen (Bombest Graffiti Bomb)

## Purpose
Implement a **modern Android splash screen** for a **Compose-only** app using:
1. The official **Android SplashScreen API** (Android 12+, backward compatible)
2. An optional **Compose Intro Splash** for brand animation (graffiti bomb vibe)

This approach is Google-approved, fast, and avoids fake loading screens.

---

## What This Delivers
- Immediate branded splash (no white flash)
- Clean transition into Compose UI
- Optional short Compose animation layer
- Easy to disable during development

---

## 1. Dependency

Add to `app/build.gradle`:

```gradle
dependencies {
    implementation "androidx.core:core-splashscreen:1.0.1"
}
```

---

## 2. Splash Theme

Create or update `res/values/themes.xml`:

```xml
<style name="Theme.Bombest.Splash" parent="Theme.SplashScreen">
    <item name="windowSplashScreenBackground">#0B0E23</item>
    <item name="windowSplashScreenAnimatedIcon">@drawable/ic_bomb_splash</item>
    <item name="windowSplashScreenAnimationDuration">650</item>
    <item name="postSplashScreenTheme">@style/Theme.Bombest</item>
</style>
```

Notes:
- Keep the duration short.
- The icon can be static or an AnimatedVectorDrawable later.

---

## 3. AndroidManifest

Set the splash theme on your launcher activity:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:theme="@style/Theme.Bombest.Splash">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

---

## 4. Splash Icon Asset

Add the bomb logo as a drawable.

**Recommended (fastest):**
- Export a 1024×1024 PNG or WebP
- Place it in `res/drawable-nodpi/ic_bomb_splash.webp`

This avoids scaling artifacts and works perfectly with the SplashScreen API.

---

## 5. MainActivity (Compose-Only)

Use the SplashScreen API and control exit animation.

```kotlin
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        var appReady = false

        splashScreen.setKeepOnScreenCondition {
            !appReady
        }

        splashScreen.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .scaleX(0.98f)
                .scaleY(0.98f)
                .setDuration(220)
                .withEndAction { provider.remove() }
                .start()
        }

        setContent {
            LaunchedEffect(Unit) {
                delay(250) // Replace with real readiness work
                appReady = true
            }

            AppRoot()
        }
    }
}
```

---

## 6. Optional Compose Intro Splash (Brand Animation)

This runs *after* the system splash and before the main UI.

```kotlin
@Composable
fun AppRoot() {
    var showIntro by remember { mutableStateOf(true) }

    if (showIntro) {
        IntroSplash { showIntro = false }
    } else {
        MainNav()
    }
}

@Composable
fun IntroSplash(onDone: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.96f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(180))
        scale.animateTo(1f, tween(220))
        delay(450)
        alpha.animateTo(0f, tween(160))
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha.value
                this.scaleX = scale.value
                this.scaleY = scale.value
            },
        contentAlignment = Alignment.Center
    ) {
        BombestLogo()
    }
}
```

Guidelines:
- Keep under ~700ms total
- No blocking work here
- Visual flair only

---

## 7. Dev Toggle (Recommended)

Add a debug flag to skip the intro splash:

```kotlin
val showIntro = !BuildConfig.DEBUG
```

This keeps iteration fast.

---

## Acceptance Checklist

- [ ] App launches with no white flash
- [ ] Bomb logo appears instantly
- [ ] Transition feels fast and intentional
- [ ] App works identically on Android 12+ and older
- [ ] Compose UI remains clean and modular

---

## Notes for AI Agents

- Do not reimplement splash logic in Composables alone
- Always use SplashScreen API for system-level splash
- Keep animations short and respectful of user time
- Comment why delays exist
