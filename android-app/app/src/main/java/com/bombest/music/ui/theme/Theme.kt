package com.bombest.music.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BombestRed,
    onPrimary = BombestTextPrimary,
    secondary = BombestSurfaceActive,
    onSecondary = BombestTextPrimary,
    tertiary = BombestRed,
    background = BombestBackground,
    onBackground = BombestTextPrimary,
    surface = BombestSurface,
    onSurface = BombestTextPrimary
)

private val LightColorScheme = DarkColorScheme // Enforce dark theme

@Composable
fun BombestBeatsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disable dynamic color to enforce brand
    theme: BombestThemeSpec = GraffitiTheme, // Default to Graffiti theme
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme // Always use dark scheme for now
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = {
            // Wrap with BombestThemeProvider for custom theme access
            BombestThemeProvider(theme = theme) {
                content()
            }
        }
    )
}
