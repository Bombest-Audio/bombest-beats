package com.bombest.music.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bombest.music.visualizer.model.VisualizerFrame
import com.bombest.music.visualizer.model.VisualizerSmoother
import com.bombest.music.visualizer.perf.VisualizerQualityTier
import com.bombest.music.visualizer.renderer.MistRenderer
import com.bombest.music.visualizer.renderer.SplatterBurstSystem
import com.bombest.music.visualizer.renderer.SprayStrokeRenderer

/**
 * Graffiti Waveform Visualizer - spray-painted style waveform rendering.
 * 
 * Features:
 * - Layered spray strokes with graffiti gradient
 * - Mist/overspray particles around peaks
 * - Splatter bursts on transients
 * - Performance scaling via quality tiers
 * 
 * @param amplitudes List of normalized amplitude values (0.0 - 1.0)
 * @param isPlaying Whether audio is currently playing
 * @param qualityTier Performance quality tier (auto-detected if not specified)
 * @param modifier Composable modifier
 */
@Composable
fun GraffitiWaveformVisualizer(
    amplitudes: List<Float>,
    isPlaying: Boolean,
    qualityTier: VisualizerQualityTier = VisualizerQualityTier.autoDetect(),
    modifier: Modifier = Modifier
) {
    // Renderers (remember to avoid recreation)
    val smoother = remember { VisualizerSmoother(smoothingFactor = 0.5f) }
    val sprayRenderer = remember(qualityTier) { SprayStrokeRenderer(qualityTier) }
    val mistRenderer = remember { MistRenderer() }
    val splatterSystem = remember { SplatterBurstSystem() }
    
    // Reset on playback stop
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            smoother.reset()
            mistRenderer.reset()
            splatterSystem.reset()
        }
    }
    
    // Smooth amplitudes. NOTE: the previous implementation used `remember { derivedStateOf {
    // amplitudes.take(..) } }` with no key. `amplitudes` is a plain function parameter — not a
    // Snapshot state — so `derivedStateOf` never observed it, and `remember {}` cached the
    // first (empty) value forever. That's why the canvas below short-circuited on
    // `smoothedAmplitudes.isEmpty()` on every frame. Key the remember on `amplitudes` so the
    // smoother runs on every recomposition of the parent (which recomposes whenever
    // MainViewModel.visualizerAmplitudes — a real mutableStateOf — changes).
    val smoothedAmplitudes = remember(amplitudes, qualityTier) {
        if (amplitudes.isEmpty()) emptyList()
        else smoother.smooth(amplitudes.take(qualityTier.strokeSegments))
    }

    // Create frame with peak detection
    val frame = remember(smoothedAmplitudes) {
        VisualizerFrame.from(smoothedAmplitudes)
    }
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        if (smoothedAmplitudes.isEmpty() || !isPlaying) {
            return@Canvas
        }
        
        // Layer 1 & 2: Spray strokes (always rendered)
        sprayRenderer.render(
            scope = this,
            amplitudes = smoothedAmplitudes,
            width = width,
            height = height
        )
        
        // Layer 3: Mist (if quality allows)
        if (qualityTier.enableMist) {
            val barWidth = width / smoothedAmplitudes.size
            mistRenderer.render(
                scope = this,
                peakIndices = frame.peakIndices,
                amplitudes = smoothedAmplitudes,
                barWidth = barWidth,
                centerY = height / 2f,
                height = height,
                timestamp = frame.timestamp
            )
        }
        
        // Layer 4: Splatter bursts (if quality allows)
        if (qualityTier.enableSplatter) {
            val barWidth = width / smoothedAmplitudes.size
            splatterSystem.render(
                scope = this,
                rms = frame.rms,
                peakIndices = frame.peakIndices,
                barWidth = barWidth,
                centerY = height / 2f,
                timestamp = frame.timestamp
            )
        }
    }
}
