package com.bombest.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.*
import kotlin.random.Random

/**
 * Spray-paint style circular progress indicator.
 * Features:
 * - Counter-clockwise drawing
 * - Uneven edges with noise
 * - Layered strokes
 * - Random paint density
 * - Drip animations
 * - Overspray fade effect
 */
@Composable
fun SprayPaintProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 280.dp,
    strokeWidth: Dp = 8.dp,
    colors: List<Color> = listOf(
        Color(0xFFFF6B35),  // Orange
        Color(0xFFE90060),  // Magenta
        Color(0xFF8B5CF6)   // Purple
    ),
    onSeek: ((Float) -> Unit)? = null
) {
    // Animation for spray burst effect
    val infiniteTransition = rememberInfiniteTransition(label = "spray")
    val sprayJitter by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "jitter"
    )
    
    // Seed for consistent randomness per frame
    val noiseSeed = remember { Random.nextInt(1000) }
    
    Canvas(modifier = modifier.size(size)) {
        val canvasSize = this.size.minDimension
        val radius = (canvasSize / 2) - strokeWidth.toPx() * 2
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val strokePx = strokeWidth.toPx()
        
        // Background track (faint spray marks)
        drawSprayArc(
            center = center,
            radius = radius,
            startAngle = -90f,
            sweepAngle = 360f,
            strokeWidth = strokePx * 0.8f,
            color = colors.last().copy(alpha = 0.25f),
            sprayDensity = 0.5f,
            seed = noiseSeed
        )
        
        // Progress arc - counter-clockwise (negative sweep)
        val sweepAngle = -progress * 360f
        
        if (progress > 0.001f) {
            // Overspray layer (outer glow)
            drawSprayArc(
                center = center,
                radius = radius + strokePx * 1.0f,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                strokeWidth = strokePx * 2.0f,
                color = colors.first().copy(alpha = 0.25f),
                sprayDensity = 0.4f,
                seed = noiseSeed + 100
            )
            
            // Main spray layer 1 (base)
            drawGradientSprayArc(
                center = center,
                radius = radius,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                strokeWidth = strokePx * 1.8f,
                colors = colors,
                sprayDensity = 0.9f + sprayJitter * 0.1f,
                seed = noiseSeed
            )
            
            // Main spray layer 2 (detail)
            drawGradientSprayArc(
                center = center,
                radius = radius,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                strokeWidth = strokePx * 1.2f,
                colors = colors.map { it.copy(alpha = 0.9f) },
                sprayDensity = 0.75f,
                seed = noiseSeed + 50
            )
            
            // Inner edge detail
            drawSprayArc(
                center = center,
                radius = radius - strokePx * 0.4f,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                strokeWidth = strokePx * 0.4f,
                color = colors.last().copy(alpha = 0.4f),
                sprayDensity = 0.4f,
                seed = noiseSeed + 200
            )
            
            // Paint drips at slow points (every ~90 degrees)
            val dripCount = (abs(sweepAngle) / 90).toInt().coerceIn(0, 4)
            for (i in 0 until dripCount) {
                val dripAngle = -90f + (sweepAngle / (dripCount + 1)) * (i + 1)
                drawDrip(
                    center = center,
                    radius = radius,
                    angle = dripAngle,
                    length = strokePx * (1.5f + Random(noiseSeed + i).nextFloat() * 2f),
                    color = interpolateColor(colors, i.toFloat() / dripCount),
                    seed = noiseSeed + i * 10
                )
            }
        }
    }
}

/**
 * Draw a spray-paint style arc with noise and uneven edges
 */
private fun DrawScope.drawSprayArc(
    center: Offset,
    radius: Float,
    startAngle: Float,
    sweepAngle: Float,
    strokeWidth: Float,
    color: Color,
    sprayDensity: Float,
    seed: Int
) {
    val random = Random(seed)
    val steps = (abs(sweepAngle) * 2).toInt().coerceAtLeast(10)
    val angleStep = sweepAngle / steps
    
    for (i in 0 until steps) {
        val angle = startAngle + angleStep * i
        val angleRad = Math.toRadians(angle.toDouble())
        
        // Add noise to radius for uneven edge
        val noise = (random.nextFloat() - 0.5f) * strokeWidth * 0.5f
        val noiseRadius = radius + noise
        
        val x = center.x + (noiseRadius * cos(angleRad)).toFloat()
        val y = center.y + (noiseRadius * sin(angleRad)).toFloat()
        
        // Random dot density for spray effect
        if (random.nextFloat() < sprayDensity) {
            val dotSize = strokeWidth * (0.5f + random.nextFloat() * 1.0f)
            drawCircle(
                color = color.copy(alpha = color.alpha * (0.7f + random.nextFloat() * 0.3f)),
                radius = dotSize / 2,
                center = Offset(x, y)
            )
        }
        
        // Scatter particles for spray mist
        for (j in 0..2) {
            if (random.nextFloat() < sprayDensity * 0.3f) {
                val scatterRadius = strokeWidth * (0.5f + random.nextFloat())
                val scatterAngle = random.nextFloat() * 2 * PI.toFloat()
                val sx = x + cos(scatterAngle) * scatterRadius
                val sy = y + sin(scatterAngle) * scatterRadius
                drawCircle(
                    color = color.copy(alpha = 0.1f + random.nextFloat() * 0.2f),
                    radius = strokeWidth * 0.15f,
                    center = Offset(sx, sy)
                )
            }
        }
    }
}

/**
 * Draw gradient spray arc with color interpolation
 */
private fun DrawScope.drawGradientSprayArc(
    center: Offset,
    radius: Float,
    startAngle: Float,
    sweepAngle: Float,
    strokeWidth: Float,
    colors: List<Color>,
    sprayDensity: Float,
    seed: Int
) {
    val random = Random(seed)
    val steps = (abs(sweepAngle) * 2).toInt().coerceAtLeast(10)
    val angleStep = sweepAngle / steps
    
    for (i in 0 until steps) {
        val t = i.toFloat() / steps
        val color = interpolateColor(colors, t)
        val angle = startAngle + angleStep * i
        val angleRad = Math.toRadians(angle.toDouble())
        
        val noise = (random.nextFloat() - 0.5f) * strokeWidth * 0.4f
        val noiseRadius = radius + noise
        
        val x = center.x + (noiseRadius * cos(angleRad)).toFloat()
        val y = center.y + (noiseRadius * sin(angleRad)).toFloat()
        
        if (random.nextFloat() < sprayDensity) {
            val dotSize = strokeWidth * (0.6f + random.nextFloat() * 0.8f)
            drawCircle(
                color = color.copy(alpha = 0.75f + random.nextFloat() * 0.25f),
                radius = dotSize / 2,
                center = Offset(x, y)
            )
        }
        
        // Mist particles
        for (j in 0..3) {
            if (random.nextFloat() < sprayDensity * 0.2f) {
                val scatter = strokeWidth * (0.3f + random.nextFloat() * 0.8f)
                val sAngle = random.nextFloat() * 2 * PI.toFloat()
                drawCircle(
                    color = color.copy(alpha = 0.05f + random.nextFloat() * 0.15f),
                    radius = strokeWidth * 0.1f,
                    center = Offset(x + cos(sAngle) * scatter, y + sin(sAngle) * scatter)
                )
            }
        }
    }
}

/**
 * Draw a paint drip
 */
private fun DrawScope.drawDrip(
    center: Offset,
    radius: Float,
    angle: Float,
    length: Float,
    color: Color,
    seed: Int
) {
    val random = Random(seed)
    val angleRad = Math.toRadians(angle.toDouble())
    val startX = center.x + (radius * cos(angleRad)).toFloat()
    val startY = center.y + (radius * sin(angleRad)).toFloat()
    
    // Drip goes downward with slight randomness
    val dripAngle = PI.toFloat() / 2 + (random.nextFloat() - 0.5f) * 0.3f
    
    var y = startY
    var x = startX
    var width = 3f
    
    for (i in 0 until (length / 2).toInt()) {
        val progress = i.toFloat() / (length / 2)
        width = (3f * (1 - progress * 0.7f)).coerceAtLeast(1f)
        
        drawCircle(
            color = color.copy(alpha = (0.8f - progress * 0.5f).coerceAtLeast(0.1f)),
            radius = width,
            center = Offset(x, y)
        )
        
        y += 2f
        x += (random.nextFloat() - 0.5f) * 0.5f
    }
}

/**
 * Interpolate between colors in a list
 */
private fun interpolateColor(colors: List<Color>, t: Float): Color {
    if (colors.size == 1) return colors[0]
    if (t <= 0f) return colors.first()
    if (t >= 1f) return colors.last()
    
    val scaledT = t * (colors.size - 1)
    val index = scaledT.toInt()
    val localT = scaledT - index
    
    val c1 = colors[index]
    val c2 = colors[(index + 1).coerceAtMost(colors.lastIndex)]
    
    return Color(
        red = c1.red + (c2.red - c1.red) * localT,
        green = c1.green + (c2.green - c1.green) * localT,
        blue = c1.blue + (c2.blue - c1.blue) * localT,
        alpha = c1.alpha + (c2.alpha - c1.alpha) * localT
    )
}
