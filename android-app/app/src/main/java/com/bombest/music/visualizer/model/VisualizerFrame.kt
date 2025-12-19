package com.bombest.music.visualizer.model

/**
 * Data class representing a single frame of visualizer state.
 * 
 * @param amplitudes Smoothed amplitude values for each bar/segment
 * @param timestamp Frame timestamp in milliseconds
 * @param rms Root mean square amplitude (overall energy)
 * @param peakIndices Indices of bars with peak energy (for mist/splatter)
 */
data class VisualizerFrame(
    val amplitudes: List<Float>,
    val timestamp: Long,
    val rms: Float,
    val peakIndices: List<Int>
) {
    companion object {
        /**
         * Create a frame from raw amplitude data.
         */
        fun from(
            amplitudes: List<Float>,
            timestamp: Long = System.currentTimeMillis(),
            peakThreshold: Float = 0.6f
        ): VisualizerFrame {
            val rms = if (amplitudes.isNotEmpty()) {
                kotlin.math.sqrt(amplitudes.map { it * it }.average()).toFloat()
            } else 0f
            
            val peakIndices = amplitudes
                .mapIndexed { index, value -> if (value > peakThreshold) index else -1 }
                .filter { it >= 0 }
            
            return VisualizerFrame(
                amplitudes = amplitudes,
                timestamp = timestamp,
                rms = rms,
                peakIndices = peakIndices
            )
        }
    }
}
