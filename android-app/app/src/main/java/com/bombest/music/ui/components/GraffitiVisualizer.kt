package com.bombest.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp
import kotlin.math.*
import kotlin.random.Random

/**
 * Graffiti-style spray paint waveform visualizer.
 * Features:
 * - Spray strokes instead of bars
 * - Paint mist expansion
 * - Louder = thicker, faster spray
 * - Quiet = drifting mist + drips
 * - Light randomness for organic feel
 */
@Composable
fun GraffitiVisualizer(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        Color(0xFFFF6B35),  // Orange
        Color(0xFFE90060),  // Magenta  
        Color(0xFF8B5CF6)   // Purple
    )
) {
    if (amplitudes.isEmpty()) return
    
    // Time-based seed for slight frame variations
    val timeSeed by remember { 
        mutableIntStateOf(System.currentTimeMillis().toInt()) 
    }
    
    // Animation for mist drift
    val infiniteTransition = rememberInfiniteTransition(label = "mist")
    val mistDrift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "drift"
    )
    
    // Outward expansion animation
    val expandProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "expand"
    )
    
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        val barCount = amplitudes.size
        val barWidth = width / barCount
        
        // Draw base wall texture (subtle)
        drawWallTexture(width, height, timeSeed)
        
        // Draw each amplitude as a spray stroke
        amplitudes.forEachIndexed { index, amplitude ->
            val x = index * barWidth + barWidth / 2
            val progress = index.toFloat() / barCount
            val color = interpolateGraffitiColor(colors, progress)
            
            // Amplitude affects spray intensity
            val intensity = amplitude.coerceIn(0.1f, 1f)
            val sprayHeight = height * 0.4f * intensity
            
            drawSprayStroke(
                x = x,
                centerY = centerY,
                height = sprayHeight,
                width = barWidth * 0.9f,
                color = color,
                intensity = intensity,
                seed = timeSeed + index,
                mistDrift = mistDrift,
                expandProgress = expandProgress
            )
        }
        
        // Add more frequent splatter bursts
        amplitudes.forEachIndexed { index, amplitude ->
            if (amplitude > 0.4f) { // Lower threshold for more splatters
                val x = index * barWidth + barWidth / 2
                val color = interpolateGraffitiColor(colors, index.toFloat() / barCount)
                drawSplatterBurst(
                    x = x,
                    y = centerY,
                    radius = barWidth * amplitude * 1.5f,
                    color = color,
                    seed = timeSeed + index + 1000
                )
            }
        }
    }
}

/**
 * Draw subtle wall texture background
 */
private fun DrawScope.drawWallTexture(width: Float, height: Float, seed: Int) {
    val random = Random(seed / 100) // Stable seed for consistent texture
    val particleCount = 200
    
    for (i in 0 until particleCount) {
        val x = random.nextFloat() * width
        val y = random.nextFloat() * height
        val alpha = 0.02f + random.nextFloat() * 0.03f
        
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = 1f + random.nextFloat() * 2f,
            center = Offset(x, y)
        )
    }
}

/**
 * Draw a single spray stroke at given position
 */
private fun DrawScope.drawSprayStroke(
    x: Float,
    centerY: Float,
    height: Float,
    width: Float,
    color: Color,
    intensity: Float,
    seed: Int,
    mistDrift: Float,
    expandProgress: Float
) {
    val random = Random(seed)
    val particleCount = (60 * intensity).toInt().coerceAtLeast(20)
    
    // Main spray particles with outward expansion
    for (i in 0 until particleCount) {
        val baseOffsetY = (random.nextFloat() - 0.5f) * height * 2
        val baseOffsetX = (random.nextFloat() - 0.5f) * width * 0.5f
        val particleSize = (2f + intensity * 4f) * random.nextFloat()
        
        // Continuous outward expansion from center - always moving
        val baseExpand = 0.3f // Minimum expansion even at low volume
        val expandMultiplier = baseExpand + intensity * 0.7f
        val expandOffset = expandProgress * 30f * expandMultiplier
        val expandDirY = if (baseOffsetY > 0) 1f else -1f
        
        // Mist drift for organic feel
        val driftX = sin(mistDrift * PI.toFloat() * 2 + i) * (3f + (1f - intensity) * 5f)
        
        drawCircle(
            color = color.copy(alpha = (0.3f + intensity * 0.5f) * random.nextFloat()),
            radius = particleSize,
            center = Offset(x + baseOffsetX + driftX, centerY + baseOffsetY + expandDirY * expandOffset)
        )
    }
    
    // Outer mist particles
    val mistCount = (15 * (1 - intensity)).toInt().coerceAtLeast(5)
    for (i in 0 until mistCount) {
        val offsetY = (random.nextFloat() - 0.5f) * height * 3
        val offsetX = (random.nextFloat() - 0.5f) * width * 1.5f
        val driftX = sin(mistDrift * PI.toFloat() * 2 + i * 0.5f) * 5f
        
        drawCircle(
            color = color.copy(alpha = 0.05f + random.nextFloat() * 0.1f),
            radius = 2f + random.nextFloat() * 3f,
            center = Offset(x + offsetX + driftX, centerY + offsetY)
        )
    }
    
    // Drips for quiet sections
    if (intensity < 0.3f && random.nextFloat() < 0.3f) {
        drawMiniDrip(
            x = x + (random.nextFloat() - 0.5f) * width * 0.5f,
            startY = centerY + height * 0.5f,
            length = 10f + random.nextFloat() * 15f,
            color = color,
            seed = seed + 500
        )
    }
}

/**
 * Draw a splatter burst for transients
 */
private fun DrawScope.drawSplatterBurst(
    x: Float,
    y: Float,
    radius: Float,
    color: Color,
    seed: Int
) {
    val random = Random(seed)
    val particleCount = 30 // More splatter particles
    
    for (i in 0 until particleCount) {
        val angle = random.nextFloat() * 2 * PI.toFloat()
        val distance = random.nextFloat() * radius
        val px = x + cos(angle) * distance
        val py = y + sin(angle) * distance
        
        drawCircle(
            color = color.copy(alpha = 0.2f + random.nextFloat() * 0.3f),
            radius = 1f + random.nextFloat() * 3f,
            center = Offset(px, py)
        )
    }
}

/**
 * Draw mini drip for quiet moments
 */
private fun DrawScope.drawMiniDrip(
    x: Float,
    startY: Float,
    length: Float,
    color: Color,
    seed: Int
) {
    val random = Random(seed)
    var y = startY
    
    for (i in 0 until (length / 2).toInt()) {
        val progress = i.toFloat() / (length / 2)
        val alpha = (0.4f - progress * 0.3f).coerceAtLeast(0.05f)
        val size = (2f * (1 - progress * 0.5f)).coerceAtLeast(0.5f)
        
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = size,
            center = Offset(x + (random.nextFloat() - 0.5f) * 2f, y)
        )
        y += 2f
    }
}

/**
 * Interpolate between graffiti colors
 */
private fun interpolateGraffitiColor(colors: List<Color>, t: Float): Color {
    if (colors.size == 1) return colors[0]
    
    val scaledT = (t * (colors.size - 1)).coerceIn(0f, (colors.size - 1).toFloat())
    val index = scaledT.toInt()
    val localT = scaledT - index
    
    val c1 = colors[index]
    val c2 = colors[(index + 1).coerceAtMost(colors.lastIndex)]
    
    return Color(
        red = c1.red + (c2.red - c1.red) * localT,
        green = c1.green + (c2.green - c1.green) * localT,
        blue = c1.blue + (c2.blue - c1.blue) * localT,
        alpha = 1f
    )
}
