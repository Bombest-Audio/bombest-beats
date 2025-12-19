package com.bombest.music.visualizer.model

/**
 * EMA (Exponential Moving Average) smoother for visualizer data.
 * 
 * Prevents jitter and creates smooth transitions between frames.
 */
class VisualizerSmoother(
    private val smoothingFactor: Float = 0.3f
) {
    private val smoothedValues = mutableListOf<Float>()
    
    /**
     * Apply smoothing to a list of amplitude values.
     * 
     * @param newValues Raw amplitude values (0.0 - 1.0)
     * @return Smoothed amplitude values
     */
    fun smooth(newValues: List<Float>): List<Float> {
        // Initialize on first call
        if (smoothedValues.isEmpty()) {
            smoothedValues.addAll(newValues)
            return newValues
        }
        
        // Resize if needed
        if (smoothedValues.size != newValues.size) {
            smoothedValues.clear()
            smoothedValues.addAll(newValues)
            return newValues
        }
        
        // Apply EMA: smoothed = smoothed * (1 - α) + new * α
        for (i in newValues.indices) {
            val old = smoothedValues[i]
            val new = newValues[i]
            smoothedValues[i] = old * (1f - smoothingFactor) + new * smoothingFactor
        }
        
        return smoothedValues.toList()
    }
    
    /**
     * Reset the smoother state.
     */
    fun reset() {
        smoothedValues.clear()
    }
}
