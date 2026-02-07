package com.bombest.musify.ui.components

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.chibde.visualizer.BarVisualizer

/**
 * Composable wrapper for BarVisualizer from android-audio-visualizer library.
 * Displays a horizontal bar visualizer that animates with audio playback.
 * 
 * @param audioSessionId The audio session ID from ExoPlayer (obtained via player.audioSessionId)
 *                       Must be > 0 and valid. If 0 or invalid, visualizer will not be initialized.
 * @param modifier Modifier for the composable
 * @param color The color of the visualizer bars (default: white)
 * @param density The density of bars (10-256, default: 70)
 */
@Composable
fun BarVisualizer(
    audioSessionId: Int,
    modifier: Modifier = Modifier,
    color: Int = android.graphics.Color.WHITE,
    density: Int = 70
) {
    val context = LocalContext.current
    
    // Only show visualizer if audioSessionId is valid (> 0)
    // Error -3 from Visualizer typically means invalid session ID
    if (audioSessionId <= 0) {
        // Don't render visualizer if session ID is invalid
        return
    }
    
    AndroidView(
        factory = { ctx ->
            try {
                BarVisualizer(ctx).apply {
                    // Validate audioSessionId before setting
                    if (audioSessionId > 0) {
                        setPlayer(audioSessionId)
                        setColor(color)
                        // Set density (number of bars)
                        try {
                            val densityMethod = javaClass.getMethod("setDensity", Int::class.java)
                            densityMethod.invoke(this, density)
                        } catch (e: Exception) {
                            // Method might not be available or have different signature
                            Log.w("BarVisualizer", "Failed to set density: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("BarVisualizer", "Failed to initialize visualizer: ${e.message}", e)
                // Return a dummy view to prevent crash
                android.view.View(ctx)
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { visualizer ->
            // Only update if visualizer is valid and audioSessionId is valid
            if (visualizer is BarVisualizer && audioSessionId > 0) {
                try {
                    visualizer.setPlayer(audioSessionId)
                } catch (e: Exception) {
                    Log.e("BarVisualizer", "Failed to update visualizer: ${e.message}", e)
                }
            }
        },
        onRelease = { visualizer ->
            // Release visualizer when composable is disposed
            if (visualizer is BarVisualizer) {
                try {
                    visualizer.release()
                } catch (e: Exception) {
                    Log.e("BarVisualizer", "Failed to release visualizer: ${e.message}", e)
                }
            }
        }
    )
}
