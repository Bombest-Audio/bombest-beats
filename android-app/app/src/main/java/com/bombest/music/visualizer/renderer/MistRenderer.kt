package com.bombest.music.visualizer.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders mist/overspray particles around waveform peaks.
 * 
 * Mist appears around energetic parts of the waveform to create
 * a spray-painted aesthetic.
 */
class MistRenderer {
    
    private val mistParticles = mutableListOf<MistParticle>()
    private val maxAge = 500L // milliseconds
    
    /**
     * Update and render mist particles.
     * 
     * @param scope DrawScope for canvas operations
     * @param peakIndices Indices of bars with high energy
     * @param amplitudes All amplitude values
     * @param barWidth Width of each bar
     * @param centerY Center Y position
     * @param height Canvas height
     * @param timestamp Current frame timestamp
     */
    fun render(
        scope: DrawScope,
        peakIndices: List<Int>,
        amplitudes: List<Float>,
        barWidth: Float,
        centerY: Float,
        height: Float,
        timestamp: Long
    ) {
        // Spawn new particles at peaks
        for (peakIndex in peakIndices) {
            if (Random.nextFloat() > 0.7f) { // 30% spawn chance
                spawnMistCluster(
                    x = peakIndex * barWidth + barWidth / 2f,
                    y = centerY - amplitudes[peakIndex] * (height * 0.4f),
                    energy = amplitudes[peakIndex],
                    timestamp = timestamp,
                    centerY = centerY
                )
            }
        }
        
        // Update and draw particles
        val iterator = mistParticles.iterator()
        while (iterator.hasNext()) {
            val particle = iterator.next()
            val age = timestamp - particle.birthTime
            
            // Remove old particles
            if (age > maxAge) {
                iterator.remove()
                continue
            }
            
            // Update position
            particle.x += particle.vx
            particle.y += particle.vy
            particle.vy += 0.02f // Reduced gravity
            
            // Calculate alpha based on age
            val ageRatio = age.toFloat() / maxAge
            val alpha = (1f - ageRatio) * 0.4f
            
            // Draw particle
            scope.drawCircle(
                color = particle.color,
                center = Offset(particle.x, particle.y),
                radius = particle.size,
                alpha = alpha
            )
        }
    }
    
    /**
     * Spawn a cluster of mist particles.
     */
    private fun spawnMistCluster(
        x: Float,
        y: Float,
        energy: Float,
        timestamp: Long,
        centerY: Float
    ) {
        val count = (3 + (energy * 7).toInt()).coerceAtMost(10)
        
        for (i in 0 until count) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = 0.5f + Random.nextFloat() * 1.5f
            
            // Randomly flip Y to spawn on both sides of center if needed, 
            // or just center around the peak
            val isUpperPeak = Random.nextBoolean()
            val spawnY = if (isUpperPeak) y else centerY + (centerY - y)
            
            mistParticles.add(
                MistParticle(
                    x = x,
                    y = spawnY,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed * 0.5f, // Reduced Y spread
                    size = 1f + Random.nextFloat() * 2f,
                    color = Color.White,
                    birthTime = timestamp
                )
            )
        }
    }
    
    /**
     * Reset all particles (call when playback stops).
     */
    fun reset() {
        mistParticles.clear()
    }
}

/**
 * Data class for a single mist particle.
 */
private data class MistParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val size: Float,
    val color: Color,
    val birthTime: Long
)
