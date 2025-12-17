package com.bombest.music.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

/**
 * CompositionLocal for accessing the current theme throughout the app.
 */
val LocalBombestTheme = staticCompositionLocalOf { GraffitiTheme }

/**
 * Provides theme context to the app.
 * Allows switching between themes without breaking functionality.
 */
@Composable
fun BombestThemeProvider(
    theme: BombestThemeSpec = GraffitiTheme,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalBombestTheme provides theme) {
        content()
    }
}

/**
 * Convenience accessor for current theme colors
 */
object ThemeColors {
    val background: Color @Composable get() = LocalBombestTheme.current.colors.background
    val surface: Color @Composable get() = LocalBombestTheme.current.colors.surface
    val surfaceActive: Color @Composable get() = LocalBombestTheme.current.colors.surfaceActive
    val primary: Color @Composable get() = LocalBombestTheme.current.colors.primary
    val accent: Color @Composable get() = LocalBombestTheme.current.colors.accent
    val textPrimary: Color @Composable get() = LocalBombestTheme.current.colors.textPrimary
    val textSecondary: Color @Composable get() = LocalBombestTheme.current.colors.textSecondary
}

/**
 * Check if current theme uses specific styles
 */
@Composable
fun isSprayPaintProgress(): Boolean = LocalBombestTheme.current.progressStyle == ProgressStyle.SPRAY_PAINT

@Composable
fun isGraffitiVisualizer(): Boolean = LocalBombestTheme.current.visualizerStyle == VisualizerThemeStyle.GRAFFITI

@Composable
fun isVUMeterProgress(): Boolean = LocalBombestTheme.current.progressStyle == ProgressStyle.VU_METER

@Composable
fun isOscilloscopeVisualizer(): Boolean = LocalBombestTheme.current.visualizerStyle == VisualizerThemeStyle.OSCILLOSCOPE

