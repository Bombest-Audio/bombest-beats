package com.bombest.music.haptics

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Main controller for the Haptic Groove Engine.
 * 
 * Maps audio analysis data to musically expressive vibrations.
 * Designed for Pixel devices but gracefully degrades on other hardware.
 * 
 * Usage:
 * 1. Call initialize(context) on app start
 * 2. Call onAmplitudeUpdate() from audio visualizer callback
 * 3. User can toggle/adjust via preferences
 */
object HapticGrooveEngine {
    
    private const val TAG = "HapticGrooveEngine"
    
    // Throttling: minimum ms between haptic events
    private const val MIN_HAPTIC_INTERVAL_MS = 50L
    private const val MAX_HAPTICS_PER_SECOND = 10
    
    // State
    private var isInitialized = false
    private var applicationContext: Context? = null
    private var lastHapticTime = 0L
    private var hapticCountInSecond = 0
    private var lastSecondTimestamp = 0L
    
    // Cached preferences (updated via flow collection)
    private var isEnabled = false
    private var currentIntensity = HapticPatternLibrary.Intensity.MEDIUM
    
    // Previous amplitude values for transient detection
    private var prevLow = 0f
    private var prevMid = 0f
    private var prevHigh = 0f
    private var sustainedLowCount = 0
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * Initialize the haptic engine.
     * Call this from Application.onCreate() or MainActivity.onCreate().
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        
        applicationContext = context.applicationContext
        DeviceCapabilityResolver.initialize(context)
        HapticPreferences.initialize(context)
        
        // Load initial preferences
        scope.launch {
            HapticPreferences.isEnabled(context).collect { enabled ->
                isEnabled = enabled
                android.util.Log.d(TAG, "Haptics enabled: $enabled")
            }
        }
        scope.launch {
            HapticPreferences.intensity(context).collect { intensity ->
                currentIntensity = intensity
                android.util.Log.d(TAG, "Haptics intensity: $intensity")
            }
        }
        
        isInitialized = true
        android.util.Log.d(TAG, "HapticGrooveEngine initialized. Amplitude control: ${DeviceCapabilityResolver.hasAmplitudeControl()}")
    }
    
    /**
     * Process audio amplitude data and trigger appropriate haptics.
     * 
     * @param amplitude Overall RMS amplitude (0.0 - 1.0)
     * @param low Low frequency band energy (0.0 - 1.0) - bass/kick
     * @param mid Mid frequency band energy (0.0 - 1.0) - snare/vocal
     * @param high High frequency band energy (0.0 - 1.0) - hi-hat/cymbal
     */
    fun onAmplitudeUpdate(amplitude: Float, low: Float, mid: Float, high: Float) {
        if (!isEnabled || !isInitialized) return
        if (!DeviceCapabilityResolver.hasVibrator()) return
        if (!canTriggerHaptic()) return
        if (isInCall()) return
        
        // Detect transients (spikes in energy)
        val lowDelta = low - prevLow
        val midDelta = mid - prevMid
        val highDelta = high - prevHigh
        
        // Priority order: kick > snare > hi-hat > bass rumble
        val element = detectAudioElement(low, mid, high, lowDelta, midDelta, highDelta)
        
        if (element != null) {
            triggerPattern(element)
        }
        
        // Update previous values
        prevLow = low
        prevMid = mid
        prevHigh = high
    }
    
    /**
     * Detect which audio element is most prominent based on frequency bands.
     */
    private fun detectAudioElement(
        low: Float, mid: Float, high: Float,
        lowDelta: Float, midDelta: Float, highDelta: Float
    ): HapticPatternLibrary.AudioElement? {
        
        // Kick detection: strong low with sharp attack
        if (low > 0.65f && lowDelta > 0.25f) {
            sustainedLowCount = 0
            return HapticPatternLibrary.AudioElement.KICK
        }
        
        // Snare detection: mid spike
        if (mid > 0.55f && midDelta > 0.2f) {
            return HapticPatternLibrary.AudioElement.SNARE
        }
        
        // Hi-hat detection: high frequency spike (only on capable devices)
        if (DeviceCapabilityResolver.hasAmplitudeControl() && high > 0.5f && highDelta > 0.15f) {
            return HapticPatternLibrary.AudioElement.HAT
        }
        
        // Bass rumble: sustained low energy
        if (low > 0.75f) {
            sustainedLowCount++
            if (sustainedLowCount > 5) { // ~100ms of sustained bass
                sustainedLowCount = 0
                return HapticPatternLibrary.AudioElement.BASS
            }
        } else {
            sustainedLowCount = 0
        }
        
        return null
    }
    
    /**
     * Execute a haptic pattern for the given audio element.
     */
    private fun triggerPattern(element: HapticPatternLibrary.AudioElement) {
        val vibrator = DeviceCapabilityResolver.getVibrator() ?: return
        val pattern = HapticPatternLibrary.getPatternForElement(element, currentIntensity) ?: return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(pattern)
            lastHapticTime = System.currentTimeMillis()
            hapticCountInSecond++
            android.util.Log.v(TAG, "Triggered ${element.name} at intensity $currentIntensity")
        }
    }
    
    /**
     * Check if we can trigger a haptic (throttling).
     */
    private fun canTriggerHaptic(): Boolean {
        val now = System.currentTimeMillis()
        
        // Reset counter each second
        if (now - lastSecondTimestamp > 1000) {
            hapticCountInSecond = 0
            lastSecondTimestamp = now
        }
        
        // Check rate limit
        if (hapticCountInSecond >= MAX_HAPTICS_PER_SECOND) {
            return false
        }
        
        // Check minimum interval
        return (now - lastHapticTime) >= MIN_HAPTIC_INTERVAL_MS
    }
    
    /**
     * Check if user is in a phone call (disable haptics during calls).
     */
    private fun isInCall(): Boolean {
        val context = applicationContext ?: return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager?.mode == AudioManager.MODE_IN_CALL ||
               audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION
    }
    
    /**
     * Manually enable/disable haptics.
     */
    fun setEnabled(enabled: Boolean) {
        val context = applicationContext ?: return
        scope.launch {
            HapticPreferences.setEnabled(context, enabled)
        }
    }
    
    /**
     * Set haptic intensity level.
     */
    fun setIntensity(intensity: HapticPatternLibrary.Intensity) {
        val context = applicationContext ?: return
        scope.launch {
            HapticPreferences.setIntensity(context, intensity)
        }
    }
    
    /**
     * Check current enabled state synchronously.
     */
    fun isCurrentlyEnabled(): Boolean = isEnabled
    
    /**
     * Get current intensity synchronously.
     */
    fun getCurrentIntensity(): HapticPatternLibrary.Intensity = currentIntensity
}
