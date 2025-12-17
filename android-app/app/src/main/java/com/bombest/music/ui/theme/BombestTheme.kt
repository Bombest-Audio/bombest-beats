package com.bombest.music.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Theme abstraction for Bombest Beats.
 * Supports multiple visual styles that can be swapped without breaking functionality.
 */
@Immutable
data class BombestThemeColors(
    val background: Color,
    val surface: Color,
    val surfaceActive: Color,
    val primary: Color,
    val primaryGradient: Brush,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color
)

enum class ProgressStyle {
    STANDARD,      // Clean circular progress
    SPRAY_PAINT,   // Graffiti spray effect
    VU_METER       // Analog dial/VU meter style
}

enum class VisualizerThemeStyle {
    STANDARD,      // Bar/wave visualizer
    GRAFFITI,      // Spray paint visualizer
    OSCILLOSCOPE   // Analog oscilloscope waveform
}

@Immutable
data class BombestThemeSpec(
    val name: String,
    val colors: BombestThemeColors,
    val progressStyle: ProgressStyle,
    val visualizerStyle: VisualizerThemeStyle,
    val useTextures: Boolean
)

/**
 * Default theme - original Bombest Beats look
 */
val DefaultTheme = BombestThemeSpec(
    name = "Default",
    colors = BombestThemeColors(
        background = Color(0xFF121212),
        surface = Color(0xFF181818),
        surfaceActive = Color(0xFF232323),
        primary = Color(0xFFFF0033),
        primaryGradient = Brush.linearGradient(listOf(Color(0xFFFF0033), Color(0xFFFF0033))),
        accent = Color(0xFFFFFFFF),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFB3B3B3),
        border = Color(0xFF282828)
    ),
    progressStyle = ProgressStyle.STANDARD,
    visualizerStyle = VisualizerThemeStyle.STANDARD,
    useTextures = false
)

/**
 * Graffiti Theme - Urban spray-paint aesthetic
 * Deep navy background, fiery orange→magenta→purple gradient
 */
val GraffitiTheme = BombestThemeSpec(
    name = "Graffiti",
    colors = BombestThemeColors(
        background = Color(0xFF0B0E23),           // Deep navy
        surface = Color(0xFF121730),              // Slightly lighter navy
        surfaceActive = Color(0xFF1A2040),        // Active surface
        primary = Color(0xFFE90060),              // Magenta
        primaryGradient = Brush.linearGradient(
            colors = listOf(
                Color(0xFFFF6B35),  // Fiery orange
                Color(0xFFE90060),  // Magenta
                Color(0xFF8B5CF6)   // Purple
            )
        ),
        accent = Color(0xFFE8E4DD),               // Off-white chalk
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFF9CA3AF),        // Faded concrete gray
        border = Color(0xFF2D3250)
    ),
    progressStyle = ProgressStyle.SPRAY_PAINT,
    visualizerStyle = VisualizerThemeStyle.GRAFFITI,
    useTextures = true
)

/**
 * Studio Dust Theme - Analog recording studio aesthetic
 * Charcoal background, warm amber/teal accents, tactile feel
 */
val StudioDustTheme = BombestThemeSpec(
    name = "Studio Dust",
    colors = BombestThemeColors(
        background = Color(0xFF1A1A1A),           // Charcoal
        surface = Color(0xFF232323),              // Dark steel
        surfaceActive = Color(0xFF2E2E2E),        // Warm gray
        primary = Color(0xFFD4A574),              // Warm amber
        primaryGradient = Brush.linearGradient(
            colors = listOf(
                Color(0xFFD4A574),  // Warm amber
                Color(0xFF5A7D7E),  // Muted teal
                Color(0xFFC45C5C)   // Soft red
            )
        ),
        accent = Color(0xFF5A7D7E),               // Muted teal
        textPrimary = Color(0xFFE8E4E0),          // Soft white
        textSecondary = Color(0xFF8A8680),        // Dusty gray
        border = Color(0xFF3A3A3A)
    ),
    progressStyle = ProgressStyle.VU_METER,
    visualizerStyle = VisualizerThemeStyle.OSCILLOSCOPE,
    useTextures = true
)

