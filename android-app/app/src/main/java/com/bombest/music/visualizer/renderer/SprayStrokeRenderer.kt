package com.bombest.music.visualizer.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.bombest.music.visualizer.perf.VisualizerQualityTier
import kotlin.math.sin
import kotlin.math.pow
import kotlin.random.Random

/**
 * Renders spray-painted graffiti strokes for the waveform.
 * 
 * Creates a connected wave path instead of individual bars for proper waveform aesthetics.
 */
class SprayStrokeRenderer(
    private val qualityTier: VisualizerQualityTier
) {
    // Graffiti gradient colors
    private val graffitiGradient = listOf(
        Color(0xFFFF6B35), // Orange
        Color(0xFFF7931E), // Gold
        Color(0xFFE90060), // Magenta
        Color(0xFFBB4BBD), // Purple
        Color(0xFF7B2CBF)  // Deep purple
    )
    
    /**
     * Render the graffiti waveform strokes as a connected wave.
     */
    fun render(
        scope: DrawScope,
        amplitudes: List<Float>,
        width: Float,
        height: Float
    ) {
        if (amplitudes.isEmpty()) return
        
        val centerY = height / 2f
        
        // Create connected waveform path
        val mainPath = createWaveformPath(amplitudes, width, height, centerY)
        
        // Render with gradient stroke
        val brush = Brush.horizontalGradient(
            colors = graffitiGradient,
            startX = 0f,
            endX = width
        )
        
        scope.drawPath(
            path = mainPath,
            brush = brush,
            style = Stroke(
                width = 3f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            ),
            alpha = 0.9f
        )
        
        // Add ghost strokes for depth if quality allows
        if (qualityTier.enableGhostStrokes) {
            val ghostPath1 = createWaveformPath(amplitudes, width, height, centerY, yOffset = -2f)
            val ghostPath2 = createWaveformPath(amplitudes, width, height, centerY, yOffset = 2f)
            
            scope.drawPath(
                path = ghostPath1,
                color = Color.White,
                style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = 0.3f
            )
            
            scope.drawPath(
                path = ghostPath2,
                color = Color.White,
                style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = 0.25f
            )
        }
    }
    
    /**
     * Create a smooth waveform path from amplitude values.
     */
    private fun createWaveformPath(
        amplitudes: List<Float>,
        width: Float,
        height: Float,
        centerY: Float,
        yOffset: Float = 0f
    ): Path {
        val path = Path()
        val stepX = width / (amplitudes.size - 1).coerceAtLeast(1)
        
        // Scale amplitudes for dramatic effect - power function pulls up low end smoothly
        val scaledAmps = amplitudes.map { amp ->
            val clampedAmp = amp.coerceIn(0.01f, 1f) // Avoid 0 for pow
            clampedAmp.pow(0.8f) // Adjusted for more high-end range
        }
        
        // Start path at first point (upper side)
        val firstY = centerY - (scaledAmps[0] * height * 0.4f) + yOffset
        path.moveTo(0f, firstY)
        
        // Create smooth upper curve
        for (i in 1 until scaledAmps.size) {
            val x = i * stepX
            val y = centerY - (scaledAmps[i] * height * 0.4f) + yOffset
            
            val prevX = (i - 1) * stepX
            val prevY = centerY - (scaledAmps[i - 1] * height * 0.4f) + yOffset
            val controlX = (prevX + x) / 2f
            val controlY = (prevY + y) / 2f
            
            path.quadraticBezierTo(controlX, controlY, x, y)
        }
        
        // Mirror back for lower curve (bottom side)
        // IMPORTANT: Use moveTo to avoid a vertical line connecting top and bottom ends
        for (i in (scaledAmps.size - 1) downTo 0) {
            val x = i * stepX
            val y = centerY + (scaledAmps[i] * height * 0.4f) - yOffset // Mirrored Y
            
            if (i == scaledAmps.size - 1) {
                path.moveTo(x, y) // Use moveTo here to break the line
            } else {
                val nextX = (i + 1) * stepX
                val nextY = centerY + (scaledAmps[i + 1] * height * 0.4f) - yOffset
                val controlX = (nextX + x) / 2f
                val controlY = (nextY + y) / 2f
                
                path.quadraticBezierTo(controlX, controlY, x, y)
            }
        }
        
        // Do not call path.close() to keep ends open
        
        return path
    }
}
