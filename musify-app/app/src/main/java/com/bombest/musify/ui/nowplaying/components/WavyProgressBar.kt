package com.bombest.musify.ui.nowplaying.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Android Auto-inspired wavy progress bar for the Now Playing screen.
 * Features animated wave motion while playing, scrubbing support, and brand gradient.
 * 
 * @param progress Progress value from 0f to 1f
 * @param modifier Modifier for the composable
 * @param isPlaying Whether playback is currently active (drives wave animation)
 * @param amplitude Wave amplitude multiplier (0f to 1f), controls wave height
 * @param waves Number of wave cycles across the width (default: 3)
 * @param strokeWidthDp Stroke width for the wave lines
 * @param trackAlpha Alpha for the track (background) wave
 * @param progressBrush Brush for the played portion of the wave
 * @param trackColor Color for the track (background) wave
 * @param onSeek Callback when user scrubs to a new position (0f to 1f)
 * @param currentTimeFormatted Formatted current time string for accessibility (e.g., "1:27")
 * @param totalTimeFormatted Formatted total duration string for accessibility (e.g., "3:18")
 */
@Composable
fun WavyProgressBar(
    progress: Float, // 0f to 1f
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    amplitude: Float = 0.35f, // 0f to 1f
    waves: Int = 3,
    strokeWidthDp: Dp = 3.dp,
    trackAlpha: Float = 0.3f,
    progressBrush: Brush,
    trackColor: Color,
    onSeek: (Float) -> Unit,
    currentTimeFormatted: String = "",
    totalTimeFormatted: String = ""
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidthDp.toPx() }
    
    // Animate phase when playing, freeze when paused
    val infiniteTransition = rememberInfiniteTransition(label = "wavy_progress_phase")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = if (isPlaying) {
            infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        } else {
            // When paused, freeze at current phase - use infiniteRepeatable with very long duration
            infiniteRepeatable(
                animation = tween(durationMillis = Int.MAX_VALUE, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        },
        label = "phase"
    )
    
    // Scrubbing state
    var progressPreview by remember { mutableStateOf<Float?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    
    // Update preview from actual progress when not dragging
    LaunchedEffect(progress) {
        if (!isDragging) {
            progressPreview = null
        }
    }
    
    // Sample count: fixed based on typical width (160 samples for ~400dp width)
    // This is a reasonable balance between smoothness and performance
    val sampleCount = 160
    
    // Precompute x positions (reused across frames - no allocation per frame)
    val xPositions = remember(sampleCount) {
        FloatArray(sampleCount) { i ->
            i.toFloat() / (sampleCount - 1)
        }
    }
    
    // Cached lists for wave points (reused, cleared and refilled in-place - no new allocations)
    // Using ArrayList for better performance than mutableListOf
    val trackPoints = remember { ArrayList<Offset>(sampleCount) }
    val progressPoints = remember { ArrayList<Offset>(sampleCount) }
    
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp) // Fixed height to ensure visibility
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val width = size.width
                        val newProgress = (offset.x / width).coerceIn(0f, 1f)
                        progressPreview = newProgress
                    },
                    onDrag = { change, _ ->
                        val width = size.width
                        val newProgress = (change.position.x / width).coerceIn(0f, 1f)
                        progressPreview = newProgress
                    },
                    onDragEnd = {
                        isDragging = false
                        progressPreview?.let { preview ->
                            onSeek(preview)
                            progressPreview = null
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        progressPreview = null
                    }
                )
            }
            .semantics {
                val progressPercent = (progressPreview ?: progress) * 100f
                val description = if (currentTimeFormatted.isNotEmpty() && totalTimeFormatted.isNotEmpty()) {
                    "Playback position $currentTimeFormatted of $totalTimeFormatted"
                } else {
                    "Playback position ${progressPercent.toInt()}%"
                }
                contentDescription = description
            }
    ) {
        val width = size.width
        val height = size.height
        val midY = height / 2f
        
        // Amplitude in pixels (scaled by amplitude parameter and available height)
        val amplitudePx = (height * 0.3f) * amplitude.coerceIn(0f, 1f)
        
        // Current progress to display (preview during drag, actual otherwise)
        val displayProgress = progressPreview ?: progress
        val progressX = width * displayProgress
        
        // Clear and rebuild point lists
        trackPoints.clear()
        progressPoints.clear()
        
        // Generate wave points
        for (i in 0 until sampleCount) {
            val xNorm = xPositions[i]
            val x = xNorm * width
            
            // Wave equation: y = midY + A * sin(2π * waves * xNorm + phase)
            // Optional second harmonic at low strength for more organic feel
            val baseWave = sin(2f * PI.toFloat() * waves * xNorm + phase)
            val secondHarmonic = sin(2f * PI.toFloat() * (waves * 2) * xNorm + phase * 1.7f) * 0.15f
            val y = midY + amplitudePx * (baseWave + secondHarmonic)
            
            val point = Offset(x, y)
            trackPoints.add(point)
            
            // Only add to progress points if before progress boundary
            if (x <= progressX) {
                progressPoints.add(point)
            }
        }
        
        // Draw track (background) wave
        if (trackPoints.size >= 2) {
            drawWavePath(
                points = trackPoints,
                brush = null,
                color = trackColor.copy(alpha = trackAlpha),
                strokeWidth = strokeWidthPx
            )
        }
        
        // Draw progress (played) wave
        if (progressPoints.size >= 2) {
            // Fade alpha of last 1-2 segments near progress boundary for smoother edge
            val fadeStartIndex = (progressPoints.size * 0.85f).toInt().coerceAtLeast(0)
            drawWavePathWithFade(
                points = progressPoints,
                brush = progressBrush,
                strokeWidth = strokeWidthPx,
                fadeStartIndex = fadeStartIndex
            )
        } else if (progressPoints.size == 1) {
            // If only one point (at start), draw a small dot
            drawThumb(progressPoints[0], progressBrush, strokeWidthPx)
        }
        
        // Draw thumb indicator (glow dot) at progress position
        if (progressPoints.isNotEmpty()) {
            val thumbPoint = progressPoints.last()
            drawThumb(thumbPoint, progressBrush, strokeWidthPx)
        }
    }
}

/**
 * Draw a wave path from a list of points.
 */
private fun DrawScope.drawWavePath(
    points: List<Offset>,
    brush: Brush?,
    color: Color?,
    strokeWidth: Float
) {
    if (points.size < 2) return
    
    for (i in 0 until points.size - 1) {
        val start = points[i]
        val end = points[i + 1]
        
        if (brush != null) {
            drawLine(
                brush = brush,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        } else if (color != null) {
            drawLine(
                color = color,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

/**
 * Draw a wave path with alpha fade near the end for smoother progress boundary.
 */
private fun DrawScope.drawWavePathWithFade(
    points: List<Offset>,
    brush: Brush,
    strokeWidth: Float,
    fadeStartIndex: Int
) {
    if (points.size < 2) return
    
    for (i in 0 until points.size - 1) {
        val start = points[i]
        val end = points[i + 1]
        
        // Calculate alpha fade for segments near the end
        val alpha = if (i >= fadeStartIndex) {
            val fadeProgress = (i - fadeStartIndex).toFloat() / (points.size - fadeStartIndex).coerceAtLeast(1)
            1f - (fadeProgress * 0.5f) // Fade to 50% alpha
        } else {
            1f
        }
        
        // Create a color brush with alpha if needed, or use the provided brush
        // For simplicity, we'll draw with reduced alpha by modifying the brush
        // In practice, we might need to extract colors from the brush
        drawLine(
            brush = brush,
            start = start,
            end = end,
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            alpha = alpha
        )
    }
}

/**
 * Draw a thumb indicator (glow dot) at the progress position.
 */
private fun DrawScope.drawThumb(
    position: Offset,
    brush: Brush,
    baseStrokeWidth: Float
) {
    val thumbRadius = baseStrokeWidth * 1.5f
    
    // Draw glow ring
    drawCircle(
        brush = brush,
        radius = thumbRadius,
        center = position,
        style = Stroke(width = baseStrokeWidth * 0.5f)
    )
    
    // Draw center dot
    drawCircle(
        brush = brush,
        radius = thumbRadius * 0.6f,
        center = position
    )
}
