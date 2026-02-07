package com.bombest.musify.ui.components

import android.media.audiofx.Visualizer
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bombest.musify.ui.theme.graffitiOrange
import com.bombest.musify.ui.theme.graffitiPink
import com.bombest.musify.ui.theme.graffitiPurple
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Graffiti-style audio visualizer with spray paint rendering.
 * Uses Android's Visualizer API to capture audio data and renders it
 * with spray paint effects using graffiti colors.
 * 
 * Features:
 * - Real-time audio visualization when audioSessionId is valid
 * - Animated fallback placeholder when audioSessionId is invalid
 * - Smooth transitions between states
 * - Improved error handling
 */
@Composable
fun GraffitiVisualizer(
    audioSessionId: Int,
    modifier: Modifier = Modifier,
    barCount: Int = 48,
    smoothingFactor: Float = 0.5f
) {
    // State for amplitudes (normalized 0.0-1.0)
    var amplitudes by remember { mutableStateOf(List(barCount) { 0f }) }
    var smoothedAmplitudes by remember { mutableStateOf(List(barCount) { 0f }) }
    var isFallbackMode by remember { mutableStateOf(audioSessionId <= 0) }
    
    // Animation for fallback mode
    val infiniteTransition = rememberInfiniteTransition(label = "fallback_animation")
    val fallbackPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fallback_phase"
    )
    
    // Fade-in animation for visualizer
    var isVisualizerActive by remember { mutableStateOf(false) }
    val fadeInAlpha by animateFloatAsState(
        targetValue = if (isVisualizerActive) 1f else 0f,
        animationSpec = tween(500),
        label = "fade_in"
    )
    
    // Visualizer instance with improved error handling
    val visualizer = remember(audioSessionId) {
        if (audioSessionId > 0) {
            Log.d("GraffitiVisualizer", "Initializing visualizer with audioSessionId: $audioSessionId")
            try {
                Visualizer(audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                visualizer: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int
                            ) {
                                waveform?.let { data ->
                                    // Convert waveform data to normalized amplitudes
                                    val newAmplitudes = mutableListOf<Float>()
                                    val segmentSize = data.size / barCount
                                    
                                    for (i in 0 until barCount) {
                                        val start = i * segmentSize
                                        val end = minOf(start + segmentSize, data.size)
                                        
                                        var sum = 0f
                                        for (j in start until end) {
                                            // Convert byte (-128 to 127) to normalized (0.0 to 1.0)
                                            val normalized = ((data[j].toInt() + 128) / 255f)
                                            sum += normalized
                                        }
                                        val avg = sum / (end - start)
                                        newAmplitudes.add(avg)
                                    }
                                    
                                    amplitudes = newAmplitudes
                                    isFallbackMode = false
                                    isVisualizerActive = true
                                }
                            }
                            
                            override fun onFftDataCapture(
                                visualizer: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int
                            ) = Unit
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        true, // waveform
                        false // FFT
                    )
                    enabled = true
                    Log.d("GraffitiVisualizer", "Visualizer initialized successfully")
                }
            } catch (e: Exception) {
                Log.e("GraffitiVisualizer", "Failed to initialize visualizer: ${e.message}", e)
                isFallbackMode = true
                isVisualizerActive = true // Show fallback animation
                null
            }
        } else {
            Log.d("GraffitiVisualizer", "Invalid audioSessionId ($audioSessionId), using fallback mode")
            isFallbackMode = true
            isVisualizerActive = true // Show fallback animation
            null
        }
    }
    
    // Update fallback mode when audioSessionId changes
    LaunchedEffect(audioSessionId) {
        if (audioSessionId <= 0) {
            isFallbackMode = true
            isVisualizerActive = true
        }
    }
    
    // Smooth amplitudes over time
    LaunchedEffect(amplitudes) {
        smoothedAmplitudes = smoothedAmplitudes.mapIndexed { index, oldValue ->
            val newValue = amplitudes.getOrElse(index) { 0f }
            oldValue + (newValue - oldValue) * smoothingFactor
        }
    }
    
    // Cleanup
    DisposableEffect(visualizer) {
        onDispose {
            try {
                visualizer?.enabled = false
                visualizer?.release()
            } catch (e: Exception) {
                Log.e("GraffitiVisualizer", "Error releasing visualizer: ${e.message}", e)
            }
        }
    }
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val barWidth = width / barCount
        
        // Render fallback animation or real audio data
        if (isFallbackMode) {
            // Fallback mode: Show animated placeholder bars
            for (index in 0 until barCount) {
                val x = index * barWidth + barWidth / 2f
                
                // Create wave pattern for fallback animation
                val waveOffset = (index.toFloat() / barCount) * 4f
                val amplitude = (0.2f + 0.3f * sin(fallbackPhase + waveOffset)) * fadeInAlpha
                val barHeight = amplitude * height * 0.8f
                
                // Graffiti color gradient based on position
                val colorProgress = index.toFloat() / barCount
                val color = getGraffitiColor(colorProgress).copy(alpha = fadeInAlpha * 0.6f)
                
                // Draw spray paint bar with texture
                drawSprayPaintBar(
                    centerX = x,
                    centerY = centerY,
                    width = barWidth * 0.8f,
                    height = barHeight,
                    color = color,
                    amplitude = amplitude
                )
            }
        } else {
            // Real audio data mode
            if (smoothedAmplitudes.isNotEmpty()) {
                smoothedAmplitudes.forEachIndexed { index, amplitude ->
                    val barHeight = amplitude * height * 0.8f // Max 80% of height
                    val x = index * barWidth + barWidth / 2f
                    
                    // Graffiti color gradient based on position
                    val colorProgress = index.toFloat() / barCount
                    val color = getGraffitiColor(colorProgress).copy(alpha = fadeInAlpha)
                    
                    // Draw spray paint bar with texture
                    drawSprayPaintBar(
                        centerX = x,
                        centerY = centerY,
                        width = barWidth * 0.8f, // 80% of bar width for spacing
                        height = barHeight,
                        color = color,
                        amplitude = amplitude
                    )
                }
            }
        }
    }
}

/**
 * Get graffiti color based on position in the gradient.
 */
private fun getGraffitiColor(progress: Float): Color {
    return when {
        progress < 0.33f -> {
            val t = progress / 0.33f
            Color(
                red = graffitiOrange.red + (graffitiPink.red - graffitiOrange.red) * t,
                green = graffitiOrange.green + (graffitiPink.green - graffitiOrange.green) * t,
                blue = graffitiOrange.blue + (graffitiPink.blue - graffitiOrange.blue) * t
            )
        }
        progress < 0.66f -> {
            val t = (progress - 0.33f) / 0.33f
            Color(
                red = graffitiPink.red + (graffitiPurple.red - graffitiPink.red) * t,
                green = graffitiPink.green + (graffitiPurple.green - graffitiPink.green) * t,
                blue = graffitiPink.blue + (graffitiPurple.blue - graffitiPink.blue) * t
            )
        }
        else -> graffitiPurple
    }
}

/**
 * Draws a spray paint style bar with texture and overspray.
 */
private fun DrawScope.drawSprayPaintBar(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    color: Color,
    amplitude: Float
) {
    if (height < 1f) return // Skip if too small
    
    // Use position-based seed for deterministic randomness
    val seed = (centerX * 1000 + centerY * 100).toInt()
    
    // Main bar with spray paint texture
    val barTop = centerY - height / 2f
    val barBottom = centerY + height / 2f
    
    // Draw multiple overlapping layers for spray paint effect
    val layerCount = (amplitude * 5).toInt().coerceIn(1, 5)
    
    for (layer in 0 until layerCount) {
        val layerSeed = seed + layer * 1000
        val layerRandom = Random(layerSeed)
        
        // Vary width and position slightly for texture
        val layerWidth = width * (0.7f + layerRandom.nextFloat() * 0.6f)
        val layerOffsetX = (layerRandom.nextFloat() - 0.5f) * width * 0.2f
        val layerOffsetY = (layerRandom.nextFloat() - 0.5f) * height * 0.1f
        
        // Alpha decreases for deeper layers
        val layerAlpha = 0.4f + (layerRandom.nextFloat() * 0.4f) * (1f - layer * 0.15f)
        
        // Draw rounded rectangle with spray paint texture
        drawSprayPaintRect(
            left = centerX - layerWidth / 2f + layerOffsetX,
            top = barTop + layerOffsetY,
            right = centerX + layerWidth / 2f + layerOffsetX,
            bottom = barBottom + layerOffsetY,
            color = color.copy(alpha = layerAlpha),
            seed = layerSeed
        )
    }
    
    // Overspray particles around peaks
    if (amplitude > 0.5f) {
        val particleCount = (amplitude * 10).toInt()
        for (i in 0 until particleCount) {
            val particleSeed = seed + i * 500
            val particleRandom = Random(particleSeed)
            
            val particleX = centerX + (particleRandom.nextFloat() - 0.5f) * width * 1.5f
            val particleY = centerY + (particleRandom.nextFloat() - 0.5f) * height * 1.5f
            val particleSize = width * (0.1f + particleRandom.nextFloat() * 0.3f)
            val particleAlpha = 0.2f + particleRandom.nextFloat() * 0.2f
            
            drawOval(
                color = color.copy(alpha = particleAlpha),
                topLeft = Offset(particleX - particleSize / 2f, particleY - particleSize / 2f),
                size = Size(particleSize, particleSize)
            )
        }
    }
}

/**
 * Draws a spray paint rectangle with texture.
 */
private fun DrawScope.drawSprayPaintRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    seed: Int
) {
    val width = right - left
    val height = bottom - top
    
    // Draw main rectangle
    drawRect(color = color, topLeft = Offset(left, top), size = Size(width, height))
    
    // Add texture with smaller overlapping blotches
    val textureCount = (width * height / 100f).toInt().coerceIn(3, 15)
    for (i in 0 until textureCount) {
        val textureSeed = seed + i * 100
        val textureRandom = Random(textureSeed)
        
        val textureX = left + textureRandom.nextFloat() * width
        val textureY = top + textureRandom.nextFloat() * height
        val textureSize = width * (0.2f + textureRandom.nextFloat() * 0.4f)
        val textureAlpha = 0.3f + textureRandom.nextFloat() * 0.3f
        
        drawOval(
            color = color.copy(alpha = textureAlpha),
            topLeft = Offset(textureX - textureSize / 2f, textureY - textureSize / 2f),
            size = Size(textureSize, textureSize)
        )
    }
}
