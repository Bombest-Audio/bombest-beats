package com.bombest.musify.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bombest.musify.ui.theme.graffitiOrange
import com.bombest.musify.ui.theme.graffitiPink
import com.bombest.musify.ui.theme.graffitiPurple
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Energy tier for musical reactivity (0.0 = low, 1.0 = high)
 * If null, will simulate based on progress and time
 */
data class EnergyTier(val value: Float) // 0.0 to 1.0

@Composable
fun GraffitiCircularProgress(
    progress: Float, // 0-100
    onSeek: (Float) -> Unit, // Called with 0-100 when user drags
    modifier: Modifier = Modifier,
    size: Dp = 300.dp,
    strokeWidth: Dp = 14.dp,
    energyTier: EnergyTier? = null, // Optional energy input for musical reactivity
    isPlaying: Boolean = false, // For energy simulation when no real data
    onScrubStart: (() -> Unit)? = null, // Called when scrubbing starts (turntable effect)
    onScrubProgress: ((Float) -> Unit)? = null, // Called continuously during scrub (0-100)
    onScrubEnd: (() -> Unit)? = null // Called when scrubbing ends
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Check for reduced motion (simplified - Android doesn't have direct API, but we can respect it via settings)
    val enableHaptics = remember { true } // Can be tied to user preference later
    val enableMotion = remember { true } // Can be tied to reduced motion preference
    
    // Haptic feedback helper
    val performHapticTick: () -> Unit = remember {
        {
            if (enableHaptics && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                        vibratorManager.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                    }
                } catch (e: Exception) {
                    // Silently fail if haptics unavailable
                }
            }
        }
    }
    // Reset drag state when progress resets to 0 (new track started)
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var seekTargetProgress by remember { mutableFloatStateOf(-1f) } // Track the target seek position
    var lastHapticProgress by remember { mutableFloatStateOf(-1f) } // Track last haptic position
    
    // Reset drag state when progress jumps back to near 0 (new track)
    LaunchedEffect(progress) {
        if (progress < 1f && isDragging) {
            // Progress reset to near 0, likely a new track - reset drag state
            isDragging = false
            dragProgress = 0f
            seekTargetProgress = -1f
            lastHapticProgress = -1f
        }
    }
    
    // Simulate energy if not provided (lightweight pseudo-energy based on progress and time)
    val simulatedEnergy by remember(progress, isPlaying) {
        derivedStateOf {
            if (energyTier != null) {
                energyTier.value
            } else {
                // Simulate energy: vary based on progress position (simulate "dense sections")
                val baseEnergy = 0.3f + (progress / 100f) * 0.4f // Base energy increases with progress
                val variation = sin(progress * 0.1f) * 0.2f // Add variation
                (baseEnergy + variation).coerceIn(0.2f, 0.9f)
            }
        }
    }
    
    // Smooth energy for jitter calculations (avoid per-frame allocations)
    val smoothedEnergy by remember(simulatedEnergy) {
        derivedStateOf { simulatedEnergy }
    }
    
    // Keep isDragging true until progress catches up to the seek target
    LaunchedEffect(progress, seekTargetProgress) {
        if (seekTargetProgress >= 0f && isDragging) {
            // Check if progress has caught up to the seek target (within 2% tolerance)
            val difference = abs(progress - seekTargetProgress)
            if (difference < 2f) {
                // Progress has caught up, safe to stop showing drag progress
                isDragging = false
                seekTargetProgress = -1f
            }
        }
    }
    
    val displayProgress = if (isDragging) dragProgress else progress
    
    Canvas(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                // Use detectDragGestures which handles both tap and drag
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        seekTargetProgress = -1f // Reset seek target when starting new drag
                        lastHapticProgress = -1f // Reset haptic tracking
                        // Calculate initial progress from drag start position
                        val canvasSize = this.size
                        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                        val dx = offset.x - center.x
                        val dy = offset.y - center.y
                        
                        var angle = atan2(dy, dx)
                        var angleDegrees = Math.toDegrees(angle.toDouble()).toFloat() + 90f
                        if (angleDegrees < 0) angleDegrees += 360f
                        
                        val clockwiseProgress = angleDegrees / 360f
                        val counterClockwiseProgress = (1f - clockwiseProgress).coerceIn(0f, 1f)
                        dragProgress = counterClockwiseProgress * 100f
                        
                        // Turntable scrubbing: notify parent to start audio scrubbing
                        // #region agent log
                        try {
                            val logFile = java.io.File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                            java.io.FileWriter(logFile, true).use { writer ->
                                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"GraffitiCircularProgress.kt:147\",\"message\":\"onDragStart - calling onScrubStart\",\"data\":{\"dragProgress\":$dragProgress,\"hasOnScrubStart\":${onScrubStart != null},\"hasOnScrubProgress\":${onScrubProgress != null}},\"timestamp\":${System.currentTimeMillis()}}\n")
                            }
                        } catch (e: Exception) {}
                        // #endregion
                        onScrubStart?.invoke()
                        onScrubProgress?.invoke(dragProgress)
                        
                        // Initial haptic on drag start
                        if (enableHaptics && enableMotion) {
                            performHapticTick()
                        }
                    },
                    onDragEnd = { 
                        // Turntable scrubbing: notify parent to end audio scrubbing
                        onScrubEnd?.invoke()
                        
                        // Don't set isDragging = false immediately
                        // Keep it true until progress catches up (handled by LaunchedEffect)
                        seekTargetProgress = dragProgress
                        onSeek(dragProgress)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val canvasSize = this.size
                        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                        val touch = change.position
                        val dx = touch.x - center.x
                        val dy = touch.y - center.y
                        
                        // Calculate angle in radians
                        var angle = atan2(dy, dx)
                        
                        // Convert to degrees and adjust for -90 degree start (top of circle)
                        var angleDegrees = Math.toDegrees(angle.toDouble()).toFloat() + 90f
                        if (angleDegrees < 0) angleDegrees += 360f
                        
                        // Convert to counterclockwise: 0% starts at top, goes counterclockwise
                        val clockwiseProgress = angleDegrees / 360f
                        val counterClockwiseProgress = (1f - clockwiseProgress).coerceIn(0f, 1f)
                        
                        val rawProgress = counterClockwiseProgress * 100f
                        
                        // Magnetic easing: if close to current progress, ease towards it
                        val currentProgress = if (isDragging) dragProgress else progress
                        val distance = abs(rawProgress - currentProgress)
                        val magneticZone = 5f // degrees of magnetic attraction
                        
                        val newProgress = if (distance < magneticZone && !isDragging) {
                            // Magnetic pull: ease towards the touch point
                            currentProgress + (rawProgress - currentProgress) * 0.3f
                        } else {
                            rawProgress
                        }
                        
                        dragProgress = newProgress
                        
                        // Turntable scrubbing: continuously update audio position during drag
                        // #region agent log
                        try {
                            val logFile = java.io.File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                            val timestamp = System.currentTimeMillis()
                            java.io.FileWriter(logFile, true).use { writer ->
                                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"F\",\"location\":\"GraffitiCircularProgress.kt:195\",\"message\":\"onDrag - calling onScrubProgress\",\"data\":{\"newProgress\":$newProgress,\"hasOnScrubProgress\":${onScrubProgress != null},\"progressDelta\":${abs(newProgress - (if (isDragging) dragProgress else progress))}},\"timestamp\":$timestamp}\n")
                            }
                        } catch (e: Exception) {}
                        // #endregion
                        onScrubProgress?.invoke(newProgress)
                        
                        // Haptic feedback on scrub (every 5% change)
                        if (enableHaptics && enableMotion) {
                            val progressDelta = abs(newProgress - lastHapticProgress)
                            if (progressDelta >= 5f) {
                                performHapticTick()
                                lastHapticProgress = newProgress
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // Separate tap gesture for quick seeking
                detectTapGestures { tapOffset ->
                    if (!isDragging) {
                        val canvasSize = this.size
                        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                        val dx = tapOffset.x - center.x
                        val dy = tapOffset.y - center.y
                        
                        // Calculate angle and convert to progress
                        var angle = atan2(dy, dx)
                        var angleDegrees = Math.toDegrees(angle.toDouble()).toFloat() + 90f
                        if (angleDegrees < 0) angleDegrees += 360f
                        
                        val clockwiseProgress = angleDegrees / 360f
                        val counterClockwiseProgress = (1f - clockwiseProgress).coerceIn(0f, 1f)
                        val newProgress = counterClockwiseProgress * 100f
                        
                        onSeek(newProgress)
                    }
                }
            }
    ) {
        // Read state values directly in draw scope to ensure recomposition
        val currentIsDragging = isDragging
        val currentDragProgress = dragProgress
        val currentDisplayProgress = if (currentIsDragging) currentDragProgress else progress
        
        val canvasSize = this.size
        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        val radius = (canvasSize.minDimension / 2f) - strokeWidth.toPx() / 2f
        
        // Draw background track
        drawCircle(
            color = Color.White.copy(alpha = 0.1f),
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth.toPx())
        )
        
        // Draw progress arc with graffiti styling (counterclockwise)
        if (currentDisplayProgress > 0f) {
            val progressRatio = currentDisplayProgress / 100f
            val sweepAngle = -progressRatio * 360f // Negative for counterclockwise
            
            // Continuous spray effect - very high density for seamless coverage
            // Using 400+ blotches for continuous spray can effect
            val maxBlotchCount = 400
            val energyMultiplier = if (enableMotion) 1f + smoothedEnergy * 0.3f else 1f
            val effectiveBlotchCount = (maxBlotchCount * energyMultiplier).toInt()
            
            // Draw continuous spray paint along the path - positions are deterministic based on progress
            // Each blotch uses its position as a seed so it always renders in the same place
            // Only draw blotches up to the current progress
            for (i in 0 until effectiveBlotchCount) {
                // Calculate progress position using fixed max count for consistent positioning
                val blotchProgress = i.toFloat() / maxBlotchCount.toFloat()
                
                // Only draw if this blotch is within the current progress
                if (blotchProgress > progressRatio) break
                val blotchAngle = -90f - (blotchProgress * 360f) // Counterclockwise from top
                val angleRad = Math.toRadians(blotchAngle.toDouble())
                
                // Use blotchProgress as seed for deterministic randomness - same position = same random values
                // Quantize progress to avoid floating point precision issues (2000 steps for smoother variation)
                val positionSeed = (blotchProgress * 2000).toInt()
                val positionRandom = Random(positionSeed)
                
                // Continuous spray: smaller, more frequent offsets for seamless coverage
                // Reduced spread for tighter, more continuous stream (67% smaller radius)
                val baseOffsetRadius = (positionRandom.nextFloat() * strokeWidth.toPx() * 0.5f - strokeWidth.toPx() * 0.25f) * 0.33f
                val blotchRadius = radius + baseOffsetRadius

                // Tighter track offset for continuous stream effect (67% smaller)
                val baseTrackOffset = (positionRandom.nextFloat() * strokeWidth.toPx() * 0.8f - strokeWidth.toPx() * 0.4f) * 0.33f
                
                val blotchX = center.x + cos(angleRad).toFloat() * blotchRadius + cos(angleRad + Math.PI / 2).toFloat() * baseTrackOffset
                val blotchY = center.y + sin(angleRad).toFloat() * blotchRadius + sin(angleRad + Math.PI / 2).toFloat() * baseTrackOffset
                
                // Larger, overlapping sizes for continuous coverage
                // Size range increased to ensure overlap and continuity (67% smaller radius)
                val baseSize = strokeWidth.toPx() * (0.6f + positionRandom.nextFloat() * 1.2f) * 0.33f
                val blotchSize = baseSize
                
                // Color based on position along gradient
                val colorProgress = blotchProgress
                val color = when {
                    colorProgress < 0.33f -> {
                        val t = colorProgress / 0.33f
                        Color(
                            red = graffitiOrange.red + (graffitiPink.red - graffitiOrange.red) * t,
                            green = graffitiOrange.green + (graffitiPink.green - graffitiOrange.green) * t,
                            blue = graffitiOrange.blue + (graffitiPink.blue - graffitiOrange.blue) * t
                        )
                    }
                    colorProgress < 0.66f -> {
                        val t = (colorProgress - 0.33f) / 0.33f
                        Color(
                            red = graffitiPink.red + (graffitiPurple.red - graffitiPink.red) * t,
                            green = graffitiPink.green + (graffitiPurple.green - graffitiPink.green) * t,
                            blue = graffitiPink.blue + (graffitiPurple.blue - graffitiPink.blue) * t
                        )
                    }
                    else -> graffitiPurple
                }
                
                // Higher alpha for more visible, continuous coverage
                // Deterministic based on position
                val alpha = 0.75f + positionRandom.nextFloat() * 0.25f

                // Draw blotch as an ellipse with more variation for continuous spray effect
                // Larger ellipses with more overlap for seamless coverage
                val ellipseWidth = blotchSize * (0.8f + positionRandom.nextFloat() * 0.8f)
                val ellipseHeight = blotchSize * (0.8f + positionRandom.nextFloat() * 0.8f)
                
                // Draw multiple overlapping layers for continuous spray effect
                // Main blotch
                drawOval(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(blotchX - ellipseWidth / 2f, blotchY - ellipseHeight / 2f),
                    size = Size(ellipseWidth, ellipseHeight)
                )
                
                // Overlay smaller blotch for texture (spray paint has multiple layers)
                if (positionRandom.nextFloat() > 0.3f) { // 70% chance for overlay
                    val overlaySeed = positionSeed + 1000
                    val overlayRandom = Random(overlaySeed)
                    val overlaySize = blotchSize * (0.3f + overlayRandom.nextFloat() * 0.4f)
                    val overlayWidth = overlaySize * (0.7f + overlayRandom.nextFloat() * 0.6f)
                    val overlayHeight = overlaySize * (0.7f + overlayRandom.nextFloat() * 0.6f)
                    val overlayOffsetX = (overlayRandom.nextFloat() - 0.5f) * ellipseWidth * 0.3f
                    val overlayOffsetY = (overlayRandom.nextFloat() - 0.5f) * ellipseHeight * 0.3f
                    
                    drawOval(
                        color = color.copy(alpha = alpha * 0.4f), // More transparent overlay
                        topLeft = Offset(blotchX - overlayWidth / 2f + overlayOffsetX, blotchY - overlayHeight / 2f + overlayOffsetY),
                        size = Size(overlayWidth, overlayHeight)
                    )
                }
            }
            
            // Draw main progress arc path (thinner, for structure)
            val path = Path().apply {
                arcTo(
                    rect = Rect(
                        left = center.x - radius,
                        top = center.y - radius,
                        right = center.x + radius,
                        bottom = center.y + radius
                    ),
                    startAngleDegrees = -90f,
                    sweepAngleDegrees = sweepAngle,
                    forceMoveTo = false
                )
            }
            drawPath(
                path = path,
                brush = Brush.sweepGradient(
                    colors = listOf(graffitiOrange, graffitiPink, graffitiPurple),
                    center = center
                ),
                style = Stroke(
                    width = strokeWidth.toPx() * 0.3f, // Thinner base line
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)
                )
            )
        }
        
        // Draw white knob (spray can nozzle) that rotates around counterclockwise
        // With magnetic deformation when dragging
        if (currentDisplayProgress > 0f) {
            // For counterclockwise: 0% = top (-90°), 25% = left (180°), 50% = bottom (90°), 75% = right (0°)
            val knobAngle = -90f - (currentDisplayProgress / 100f) * 360f
            val baseKnobRadius = 12.dp.toPx()
            
            // Slight deformation when dragging: knob and nearby ring slightly scale
            val dragDeformation = if (currentIsDragging && enableMotion) {
                1.15f // Slightly larger when dragging for tactile feedback
            } else 1f
            val knobRadius = baseKnobRadius * dragDeformation
            val knobDistance = radius
            
            val knobX = center.x + cos(Math.toRadians(knobAngle.toDouble())).toFloat() * knobDistance
            val knobY = center.y + sin(Math.toRadians(knobAngle.toDouble())).toFloat() * knobDistance
            
            // Draw knob with shadow for depth (enhanced when dragging)
            val shadowAlpha = if (currentIsDragging) 0.5f else 0.3f
            drawCircle(
                color = Color.White.copy(alpha = shadowAlpha),
                radius = knobRadius * 1.3f,
                center = Offset(knobX, knobY)
            )
            drawCircle(
                color = Color.White,
                radius = knobRadius,
                center = Offset(knobX, knobY)
            )
            
            // Ring deformation near knob when dragging (subtle scale of nearby dots)
            if (currentIsDragging && enableMotion) {
                // Draw a subtle highlight ring around the knob area
                val deformationRadius = knobRadius * 2.5f
                drawCircle(
                    color = Color.White.copy(alpha = 0.1f),
                    radius = deformationRadius,
                    center = Offset(knobX, knobY),
                    style = Stroke(width = strokeWidth.toPx() * 0.2f)
                )
            }
        }
    }
}
