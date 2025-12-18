package com.bombest.music.haptics

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Detects device haptic feedback capabilities.
 * 
 * Pixel devices with amplitude control unlock richer vibration patterns.
 * Basic devices fall back to simple on/off vibrations.
 */
object DeviceCapabilityResolver {
    
    private var vibrator: Vibrator? = null
    private var hasAmplitudeControlCached: Boolean? = null
    
    /**
     * Initialize with application context.
     * Must be called before other methods.
     */
    fun initialize(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        // Cache the capability check
        hasAmplitudeControlCached = vibrator?.hasAmplitudeControl() ?: false
    }
    
    /**
     * Returns true if the device supports variable amplitude vibrations.
     * Pixel devices typically return true, enabling rich haptic patterns.
     */
    fun hasAmplitudeControl(): Boolean {
        return hasAmplitudeControlCached ?: false
    }
    
    /**
     * Returns the vibrator instance for pattern execution.
     */
    fun getVibrator(): Vibrator? = vibrator
    
    /**
     * Returns true if the device has a vibrator at all.
     */
    fun hasVibrator(): Boolean {
        return vibrator?.hasVibrator() ?: false
    }
    
    /**
     * Scale intensity based on device capabilities.
     * Returns a value between 0.0 and 1.0.
     */
    fun scaleIntensity(intensity: Float): Float {
        return if (hasAmplitudeControl()) {
            intensity.coerceIn(0f, 1f)
        } else {
            // Basic devices: threshold to on/off at 0.5
            if (intensity > 0.5f) 1f else 0f
        }
    }
}
