package com.bombest.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.*
import kotlin.random.Random

/**
 * Oscilloscope-style waveform visualizer.
 * Features:
 * - Thin glowing lines with natural imperfections
 * - Dynamic reaction to audio energy
 * - Slight jitter and uneven thickness for analog feel
 * - No symmetry enforcement - organic movement
 * - Phosphor glow effect like vintage CRT
 */
@Composable
fun OscilloscopeVisualizer(
    amplitudes: FloatArray,
    modifier: Modifier = Modifier,
    primaryColor: Color = Color(0xFFD4A574),
    glowColor: Color = Color(0xFF5A7D7E),
    lineThickness: Float = 2f
) {
    // Calculate average energy for intensity
    val energy = remember(amplitudes) {
        if (amplitudes.isEmpty()) 0f else amplitudes.average().toFloat()
    }
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        
        if (amplitudes.isEmpty()) return@Canvas
        
        val pointCount = amplitudes.size
        val stepX = width / (pointCount - 1).coerceAtLeast(1)
        
        // Create path for the waveform
        val path = Path()
        val glowPath = Path()
        
        // Random seed for consistent jitter per frame
        val jitterSeed = System.currentTimeMillis() / 50 // Changes every 50ms
        val random = Random(jitterSeed.toInt())
        
        // Build waveform points with natural imperfections
        val points = mutableListOf<Offset>()
        
        for (i in amplitudes.indices) {
            val x = i * stepX
            
            // Base amplitude
            val amp = amplitudes[i].coerceIn(0f, 1f)
            
            // Add natural jitter (minor imperfections)
            val jitter = (random.nextFloat() - 0.5f) * 4f
            
            // Scale to canvas with organic variance
            val y = centerY - (amp * height * 0.4f) + jitter
            
            points.add(Offset(x, y))
        }
        
        // Draw waveform using smooth curves
        if (points.size >= 2) {
            // Outer glow layer (blurred effect simulated with multiple strokes)
            for (glowRadius in listOf(8f, 5f, 3f)) {
                path.reset()
                path.moveTo(points.first().x, points.first().y)
                
                for (i in 1 until points.size) {
                    val prevPoint = points[i - 1]
                    val currPoint = points[i]
                    
                    // Smooth curve between points
                    val controlX = (prevPoint.x + currPoint.x) / 2
                    path.quadraticBezierTo(
                        prevPoint.x, prevPoint.y + (random.nextFloat() - 0.5f) * 2,
                        controlX, (prevPoint.y + currPoint.y) / 2
                    )
                }
                
                // Complete to last point
                path.lineTo(points.last().x, points.last().y)
                
                val glowAlpha = (0.1f / (glowRadius / 3f)) * (0.5f + energy * 0.5f)
                drawPath(
                    path = path,
                    color = glowColor.copy(alpha = glowAlpha.coerceIn(0f, 0.3f)),
                    style = Stroke(
                        width = glowRadius + lineThickness,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
            
            // Main waveform line
            path.reset()
            path.moveTo(points.first().x, points.first().y)
            
            for (i in 1 until points.size) {
                val prevPoint = points[i - 1]
                val currPoint = points[i]
                
                val controlX = (prevPoint.x + currPoint.x) / 2
                path.quadraticBezierTo(
                    prevPoint.x, prevPoint.y,
                    controlX, (prevPoint.y + currPoint.y) / 2
                )
            }
            path.lineTo(points.last().x, points.last().y)
            
            // Dynamic thickness based on energy
            val dynamicThickness = lineThickness * (0.8f + energy * 0.4f)
            
            drawPath(
                path = path,
                color = primaryColor.copy(alpha = 0.9f),
                style = Stroke(
                    width = dynamicThickness,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            
            // Bright center line for phosphor effect
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.4f + energy * 0.3f),
                style = Stroke(
                    width = dynamicThickness * 0.3f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        
        // Add subtle scan lines for CRT effect
        drawScanLines(height, width, 0.03f)
        
        // Add subtle grain
        drawGrainOverlay(size.width, size.height, 0.02f)
    }
}

/**
 * Draw horizontal scan lines for CRT effect
 */
private fun DrawScope.drawScanLines(height: Float, width: Float, opacity: Float) {
    val lineSpacing = 4f
    var y = 0f
    
    while (y < height) {
        drawLine(
            color = Color.Black.copy(alpha = opacity),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 1f
        )
        y += lineSpacing
    }
}

/**
 * Draw subtle grain overlay
 */
private fun DrawScope.drawGrainOverlay(width: Float, height: Float, opacity: Float) {
    val random = Random(System.currentTimeMillis() / 100)
    val particleCount = 30
    
    for (i in 0 until particleCount) {
        val x = random.nextFloat() * width
        val y = random.nextFloat() * height
        val size = random.nextFloat() * 1.5f + 0.5f
        
        drawCircle(
            color = Color.White.copy(alpha = opacity * random.nextFloat()),
            radius = size,
            center = Offset(x, y)
        )
    }
}
