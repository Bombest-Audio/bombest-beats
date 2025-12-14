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
    SPRAY_PAINT    // Graffiti spray effect
}

enum class VisualizerThemeStyle {
    STANDARD,      // Bar/wave visualizer
    GRAFFITI       // Spray paint visualizer
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
