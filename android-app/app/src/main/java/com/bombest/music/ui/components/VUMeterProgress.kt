package com.bombest.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.*

/**
 * VU Meter style circular progress indicator.
 * Features:
 * - Analog dial aesthetic with smooth arc
 * - Eased motion for natural feel
 * - Leading edge glow effect
 * - Peak brightness on high values (like VU meter hitting yellow/red)
 * - Subtle grain texture overlay
 */
@Composable
fun VUMeterProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 280.dp,
    strokeWidth: Dp = 6.dp,
    baseColor: Color = Color(0xFF3A3A3A),
    primaryColor: Color = Color(0xFFD4A574),
    peakColor: Color = Color(0xFFC45C5C),
    glowColor: Color = Color(0xFF5A7D7E)
) {
    // Smooth eased progress for natural analog feel
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 150,
            easing = FastOutSlowInEasing
        ),
        label = "vuProgress"
    )
    
    // Subtle glow pulse for leading edge
    val infiniteTransition = rememberInfiniteTransition(label = "vuGlow")
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )
    
    Canvas(modifier = modifier.size(size)) {
        val canvasSize = this.size.minDimension
        val radius = (canvasSize / 2) - strokeWidth.toPx() * 3
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val strokePx = strokeWidth.toPx()
        
        // Sweep angle: counter-clockwise from top
        val sweepAngle = -animatedProgress * 360f
        
        // Background track - subtle tick marks like analog dial
        drawArc(
            color = baseColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(width = strokePx * 0.5f, cap = StrokeCap.Round)
        )
        
        // Draw dial tick marks
        for (i in 0..11) {
            val tickAngle = -90f + (i * 30f)
            val tickRad = Math.toRadians(tickAngle.toDouble())
            val innerRadius = radius - strokePx * 1.5f
            val outerRadius = radius + strokePx * 0.5f
            
            val startX = center.x + (innerRadius * cos(tickRad)).toFloat()
            val startY = center.y + (innerRadius * sin(tickRad)).toFloat()
            val endX = center.x + (outerRadius * cos(tickRad)).toFloat()
            val endY = center.y + (outerRadius * sin(tickRad)).toFloat()
            
            drawLine(
                color = baseColor.copy(alpha = 0.6f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
        
        if (animatedProgress > 0.001f) {
            // Determine color based on progress (VU meter zones)
            val meterColor = when {
                animatedProgress > 0.85f -> peakColor  // Red zone
                animatedProgress > 0.7f -> lerp(primaryColor, peakColor, (animatedProgress - 0.7f) / 0.15f)
                else -> primaryColor
            }
            
            // Outer glow layer
            drawArc(
                color = glowColor.copy(alpha = 0.15f * glowIntensity),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius - strokePx, center.y - radius - strokePx),
                size = androidx.compose.ui.geometry.Size((radius + strokePx) * 2, (radius + strokePx) * 2),
                style = Stroke(width = strokePx * 2.5f, cap = StrokeCap.Round)
            )
            
            // Main progress arc
            drawArc(
                color = meterColor,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = strokePx * 1.5f, cap = StrokeCap.Round)
            )
            
            // Leading edge glow
            val leadingAngle = -90f + sweepAngle
            val leadingRad = Math.toRadians(leadingAngle.toDouble())
            val leadingX = center.x + (radius * cos(leadingRad)).toFloat()
            val leadingY = center.y + (radius * sin(leadingRad)).toFloat()
            
            // Soft glow circle at leading edge
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        meterColor.copy(alpha = 0.8f * glowIntensity),
                        meterColor.copy(alpha = 0.3f * glowIntensity),
                        Color.Transparent
                    ),
                    center = Offset(leadingX, leadingY),
                    radius = strokePx * 3
                ),
                radius = strokePx * 3,
                center = Offset(leadingX, leadingY)
            )
            
            // Small bright dot at leading edge
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = strokePx * 0.4f,
                center = Offset(leadingX, leadingY)
            )
        }
        
        // Subtle grain texture overlay (very low opacity)
        drawGrainTexture(center, radius + strokePx * 2, 0.03f)
    }
}

/**
 * Draw subtle grain/dust texture
 */
private fun DrawScope.drawGrainTexture(center: Offset, radius: Float, opacity: Float) {
    val random = kotlin.random.Random(42) // Fixed seed for consistent texture
    val particleCount = 50
    
    for (i in 0 until particleCount) {
        val angle = random.nextFloat() * 2 * PI.toFloat()
        val dist = random.nextFloat() * radius
        val x = center.x + cos(angle) * dist
        val y = center.y + sin(angle) * dist
        val size = random.nextFloat() * 1.5f + 0.5f
        
        drawCircle(
            color = Color.White.copy(alpha = opacity * random.nextFloat()),
            radius = size,
            center = Offset(x, y)
        )
    }
}

/**
 * Linear interpolation between two colors
 */
private fun lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = start.alpha + (end.alpha - start.alpha) * fraction
    )
}
