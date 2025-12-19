package com.bombest.music.visualizer.perf

/**
 * Performance quality tiers for the Graffiti Waveform Visualizer.
 * 
 * Degrades features based on device capability:
 * - HIGH: All layers, 60fps target
 * - MEDIUM: No splatter, 45-60fps
 * - LOW: Basic strokes only, 30fps minimum
 */
enum class VisualizerQualityTier {
    LOW,
    MEDIUM,
    HIGH;
    
    /**
     * Maximum number of particles to render per frame.
     */
    val maxParticles: Int
        get() = when (this) {
            HIGH -> 50
            MEDIUM -> 20
            LOW -> 0
        }
    
    /**
     * Number of stroke segments for waveform rendering.
     */
    val strokeSegments: Int
        get() = when (this) {
            HIGH -> 60
            MEDIUM -> 40
            LOW -> 30
        }
    
    /**
     * Enable splatter bursts (expensive).
     */
    val enableSplatter: Boolean
        get() = this == HIGH
    
    /**
     * Enable mist/overspray particles.
     */
    val enableMist: Boolean
        get() = this >= MEDIUM
    
    /**
     * Enable ghost strokes (layered rendering).
     */
    val enableGhostStrokes: Boolean
        get() = this >= MEDIUM
    
    companion object {
        /**
         * Auto-detect quality tier based on device performance.
         * For now, returns MEDIUM as a safe default.
         * 
         * Future: Use CPU/GPU benchmarking or device tier detection.
         */
        fun autoDetect(): VisualizerQualityTier {
            // TODO: Implement device-specific detection
            // Could check Android Build.MANUFACTURER, MODEL
            // Or use runtime performance monitoring
            return MEDIUM
        }
    }
}
