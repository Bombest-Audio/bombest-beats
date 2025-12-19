package com.bombest.music.visualizer.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders paint splatter bursts triggered by audio transients.
 * 
 * Splatter appears on sudden energy spikes (kick hits, snares, etc.)
 * to enhance the graffiti authenticity.
 */
class SplatterBurstSystem {
    
    private val splatters = mutableListOf<Splatter>()
    private val maxAge = 300L // milliseconds
    private var previousRms = 0f
    
    /**
     * Update and render splatter bursts.
     * 
     * @param scope DrawScope for canvas operations
     * @param rms Current RMS (overall energy)
     * @param centerX X position to spawn splatter
     * @param centerY Y position to spawn splatter
     * @param timestamp Current frame timestamp
     */
    fun render(
        scope: DrawScope,
        rms: Float,
        centerX: Float,
        centerY: Float,
        timestamp: Long
    ) {
        // Detect transient (sharp RMS increase)
        val rmsDelta = rms - previousRms
        previousRms = rms
        
        if (rmsDelta > 0.3f && rms > 0.5f) {
            spawnSplatterBurst(centerX, centerY, rms, timestamp)
        }
        
        // Update and draw splatters
        val iterator = splatters.iterator()
        while (iterator.hasNext()) {
            val splatter = iterator.next()
            val age = timestamp - splatter.birthTime
            
            // Remove old splatters
            if (age > maxAge) {
                iterator.remove()
                continue
            }
            
            // Update droplet positions
            for (droplet in splatter.droplets) {
                droplet.x += droplet.vx
                droplet.y += droplet.vy
                droplet.vy += 0.2f // Gravity
            }
            
            // Calculate alpha based on age
            val ageRatio = age.toFloat() / maxAge
            val alpha = (1f - ageRatio) * 0.7f
            
            // Draw droplets
            for (droplet in splatter.droplets) {
                scope.drawCircle(
                    color = droplet.color,
                    center = Offset(droplet.x, droplet.y),
                    radius = droplet.size,
                    alpha = alpha
                )
            }
        }
    }
    
    /**
     * Spawn a burst of paint droplets.
     */
    private fun spawnSplatterBurst(
        x: Float,
        y: Float,
        energy: Float,
        timestamp: Long
    ) {
        val dropletCount = (5 + (energy * 10).toInt()).coerceAtMost(15)
        val droplets = mutableListOf<Droplet>()
        
        // Graffiti colors (same palette as spray strokes)
        val colors = listOf(
            Color(0xFFFF6B35), // Orange
            Color(0xFFE90060), // Magenta
            Color(0xFFBB4BBD)  // Purple
        )
        
        for (i in 0 until dropletCount) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = 2f + Random.nextFloat() * 4f
            
            droplets.add(
                Droplet(
                    x = x,
                    y = y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed - 2f, // Bias upward
                    size = 1.5f + Random.nextFloat() * 2.5f,
                    color = colors.random()
                )
            )
        }
        
        splatters.add(
            Splatter(
                droplets = droplets,
                birthTime = timestamp
            )
        )
    }
    
    /**
     * Reset all splatters (call when playback stops).
     */
    fun reset() {
        splatters.clear()
        previousRms = 0f
    }
}

/**
 * Data class for a splatter burst.
 */
private data class Splatter(
    val droplets: List<Droplet>,
    val birthTime: Long
)

/**
 * Data class for a single paint droplet.
 */
private data class Droplet(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val size: Float,
    val color: Color
)
