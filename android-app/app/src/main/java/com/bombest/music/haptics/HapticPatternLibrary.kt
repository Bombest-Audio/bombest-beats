package com.bombest.music.haptics

import android.os.Build
import android.os.VibrationEffect

/**
 * Library of reusable haptic patterns for musical elements.
 * 
 * Patterns are designed to feel rhythmically correct:
 * - Kick drums → deep, soft thump
 * - Snares/claps → sharp, short tap
 * - Hi-hats → ultra-light micro-ticks
 * - Bass drops → brief low-frequency rumble
 */
object HapticPatternLibrary {
    
    /**
     * Intensity levels for user preference.
     */
    enum class Intensity(val amplitudeScale: Float) {
        LOW(0.3f),
        MEDIUM(0.6f),
        HIGH(1.0f)
    }
    
    /**
     * Low-frequency, medium duration thump for kick drums.
     * Duration: ~50ms, Amplitude: 180 (scaled by intensity)
     */
    fun createKickThump(intensity: Intensity = Intensity.MEDIUM): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        
        val baseAmplitude = 180
        val scaledAmplitude = (baseAmplitude * intensity.amplitudeScale).toInt().coerceIn(1, 255)
        
        return if (DeviceCapabilityResolver.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(50, scaledAmplitude)
        } else {
            // Fallback: simple on/off vibration
            VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
    
    /**
     * Sharp, fast pulse for snares and claps.
     * Duration: ~20ms, Amplitude: 220 (scaled by intensity)
     */
    fun createSnareTap(intensity: Intensity = Intensity.MEDIUM): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        
        val baseAmplitude = 220
        val scaledAmplitude = (baseAmplitude * intensity.amplitudeScale).toInt().coerceIn(1, 255)
        
        return if (DeviceCapabilityResolver.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(20, scaledAmplitude)
        } else {
            VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
    
    /**
     * Very short micro pulse for hi-hats and shakers.
     * Duration: ~10ms, Amplitude: 80 (scaled by intensity)
     */
    fun createHatTick(intensity: Intensity = Intensity.MEDIUM): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        
        val baseAmplitude = 80
        val scaledAmplitude = (baseAmplitude * intensity.amplitudeScale).toInt().coerceIn(1, 255)
        
        return if (DeviceCapabilityResolver.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(10, scaledAmplitude)
        } else {
            // For basic devices, skip hat ticks entirely (too subtle)
            null
        }
    }
    
    /**
     * Low-amplitude sustained vibration for bass drops.
     * Duration: ~100ms with fade-out effect.
     */
    fun createBassRumble(intensity: Intensity = Intensity.MEDIUM): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        
        val baseAmplitude = 140
        val scaledAmplitude = (baseAmplitude * intensity.amplitudeScale).toInt().coerceIn(1, 255)
        
        return if (DeviceCapabilityResolver.hasAmplitudeControl()) {
            // Create a fade-out waveform: start strong, fade to zero
            val timings = longArrayOf(0, 30, 30, 40)
            val amplitudes = intArrayOf(
                scaledAmplitude,
                (scaledAmplitude * 0.7).toInt(),
                (scaledAmplitude * 0.4).toInt(),
                0
            )
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
    
    /**
     * Get a pattern based on detected audio element type.
     */
    fun getPatternForElement(element: AudioElement, intensity: Intensity): VibrationEffect? {
        return when (element) {
            AudioElement.KICK -> createKickThump(intensity)
            AudioElement.SNARE -> createSnareTap(intensity)
            AudioElement.HAT -> createHatTick(intensity)
            AudioElement.BASS -> createBassRumble(intensity)
        }
    }
    
    /**
     * Audio element types that can trigger haptic patterns.
     */
    enum class AudioElement {
        KICK,   // Low frequency transient
        SNARE,  // Mid frequency transient
        HAT,    // High frequency content
        BASS    // Sustained low frequency
    }
}
