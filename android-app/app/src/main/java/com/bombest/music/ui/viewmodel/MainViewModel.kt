package com.bombest.music.ui.viewmodel

import androidx.lifecycle.viewModelScope
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionToken
import com.bombest.music.service.BombestMediaService
import com.bombest.music.data.NetworkModule
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bombest.music.data.DownloadManager
import com.bombest.music.data.repository.MusicRepository

class MainViewModel : ViewModel() {

    var mediaBrowser: MediaBrowser? = null
        private set

    // UI State
    val currentMediaItem = mutableStateOf<MediaItem?>(null)
    val isPlaying = mutableStateOf(false)
    val isShuffleEnabled = mutableStateOf(false)
    val repeatMode = mutableStateOf(Player.REPEAT_MODE_OFF)
    val playlist = mutableStateListOf<MediaItem>()
    val isRefreshing = mutableStateOf(false)
    val favorites = mutableStateOf<Set<String>>(emptySet())
    val downloads = mutableStateOf<Set<String>>(emptySet()) // Track downloaded files
    
    // Progress State
    val currentPosition = mutableStateOf(0L)
    val duration = mutableStateOf(0L)
    
    // Loops
    private var updateProgressJob: kotlinx.coroutines.Job? = null
    private val scope = viewModelScope
    
    // Connection Future
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    
    // Download Manager
    private var downloadManager: DownloadManager? = null
    private val musicRepository = MusicRepository()

    // Visualizer State - now uses server-side pre-computed waveform data
    val visualizerAmplitudes = mutableStateListOf<Float>()
    private var visualizer: android.media.audiofx.Visualizer? = null
    
    // Server waveform data
    private var serverWaveformPeaks: List<Float> = emptyList()
    private var currentTrackId: Int? = null
    private val api = NetworkModule.api
    
    // Shuffle History for proper back button and no-repeat behavior
    private val shuffleHistory = mutableListOf<Int>()
    private val unplayedIndices = mutableSetOf<Int>()
    
    private fun requestAudioSessionId() {
        android.util.Log.d("MainViewModel", "requestAudioSessionId called, mediaBrowser: $mediaBrowser")
        
        if (mediaBrowser == null) {
            android.util.Log.w("MainViewModel", "mediaBrowser is null, using session 0 (output mix)")
            setupVisualizer(0)
            return
        }
        
        val command = androidx.media3.session.SessionCommand("GET_AUDIO_SESSION_ID", android.os.Bundle.EMPTY)
        val future = mediaBrowser?.sendCustomCommand(command, android.os.Bundle.EMPTY)
        
        if (future == null) {
            android.util.Log.w("MainViewModel", "sendCustomCommand returned null, using session 0")
            setupVisualizer(0)
            return
        }
        
        // Use a wrapper to execute safely
        future.addListener({
            try {
                val result = future.get()
                android.util.Log.d("MainViewModel", "Audio session command result: ${result.resultCode}")
                if (result.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                    val sessionId = result.extras.getInt("AUDIO_SESSION_ID")
                    android.util.Log.d("MainViewModel", "Got audio session ID: $sessionId")
                    setupVisualizer(sessionId)
                } else {
                    android.util.Log.w("MainViewModel", "Audio session command failed, using session 0")
                    setupVisualizer(0)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to get audio session id, using session 0", e)
                setupVisualizer(0)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupVisualizer(sessionId: Int) {
        try {
            if (visualizer != null) return
            
            android.util.Log.d("MainViewModel", "Setting up Visualizer with session ID: $sessionId")
            
            // Try with provided session, fallback to 0 (output mix) if needed
            val targetSession = if (sessionId > 0) sessionId else 0
            visualizer = android.media.audiofx.Visualizer(targetSession)
            visualizer?.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1] // Max size
            
            android.util.Log.d("MainViewModel", "Visualizer capture size: ${visualizer?.captureSize}")
            
            visualizer?.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                     if (waveform != null && waveform.isNotEmpty()) {
                         updateVisualizerFromWaveform(waveform)
                     }
                }

                override fun onFftDataCapture(visualizer: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {
                     // Not using FFT - waveform is more reliable
                }
            }, android.media.audiofx.Visualizer.getMaxCaptureRate(), true, false) // waveform=true, fft=false
            
            visualizer?.enabled = true
            android.util.Log.d("MainViewModel", "Visualizer enabled: ${visualizer?.enabled}")
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Visualizer init failed, trying session 0", e)
            // Try with session 0 as fallback
            if (sessionId != 0) {
                setupVisualizer(0)
            }
        }
    }

    // Throttling & Simulation
    private var lastVisualizerUpdate = 0L
    private val VISUALIZER_UPDATE_INTERVAL = 30L // ~33fps for smoother updates
    private var isVisualizerActive = false
    private var simulationJob: kotlinx.coroutines.Job? = null

    private fun updateVisualizerFromWaveform(waveform: ByteArray) {
        isVisualizerActive = true
        // Cancel simulation if real data is coming
        if (simulationJob?.isActive == true) {
            simulationJob?.cancel()
            simulationJob = null
        }

        val now = System.currentTimeMillis()
        if (now - lastVisualizerUpdate < VISUALIZER_UPDATE_INTERVAL) return
        lastVisualizerUpdate = now

        val bars = 60
        val samplesPerBar = waveform.size / bars
        val newAmplitudes = mutableListOf<Float>()
        
        for (i in 0 until bars) {
            var sum = 0f
            for (j in 0 until samplesPerBar) {
                val index = i * samplesPerBar + j
                if (index < waveform.size) {
                    // Waveform is unsigned byte (0-255), center at 128
                    val sample = (waveform[index].toInt() and 0xFF) - 128
                    sum += kotlin.math.abs(sample).toFloat()
                }
            }
            val avg = sum / samplesPerBar
            // Scale to 0-1 range with boost
            val normalized = (avg / 128f) * 3.0f
            newAmplitudes.add(normalized.coerceIn(0f, 1f))
        }
        
        updateAmplitudesInternal(newAmplitudes)
    }

    private fun updateVisualizerState(fft: ByteArray) {
        isVisualizerActive = true
        // If we get real data, cancel any simulation
        if (simulationJob?.isActive == true) {
             simulationJob?.cancel()
             simulationJob = null
        }

        val now = System.currentTimeMillis()
        if (now - lastVisualizerUpdate < VISUALIZER_UPDATE_INTERVAL) return
        lastVisualizerUpdate = now

        // FFT comes as real/imaginary pairs.
        val newAmplitudes = mutableListOf<Float>()
        val n = fft.size
        val usableBins = n / 2
        val bars = 60
        val step = usableBins / bars
        
        for (i in 0 until bars) {
            var magnitudeSum = 0f
            var count = 0
            
            for (j in 0 until step) {
                val binIndex = (i * step) + j
                if ((binIndex * 2 + 1) < n) {
                    val real = fft[binIndex * 2].toFloat()
                    val imag = fft[binIndex * 2 + 1].toFloat()
                    magnitudeSum += kotlin.math.sqrt(real * real + imag * imag)
                    count++
                }
            }
            
            val avgMag = if (count > 0) magnitudeSum / count else 0f
            val scale = 5.0f // Strong boost for better visibility
            val normalized = (avgMag / 128f) * scale
            newAmplitudes.add(normalized.coerceIn(0f, 1f))
        }
        
        updateAmplitudesInternal(newAmplitudes)
    }

    private fun updateAmplitudesInternal(newAmplitudes: List<Float>) {
        viewModelScope.launch(Dispatchers.Main) { 
             if (visualizerAmplitudes.size != newAmplitudes.size) {
                 visualizerAmplitudes.clear()
                 visualizerAmplitudes.addAll(newAmplitudes)
             } else {
                 for (i in 0 until newAmplitudes.size) {
                     val old = visualizerAmplitudes[i]
                     val target = newAmplitudes[i]
                     visualizerAmplitudes[i] = old + (target - old) * 0.4f 
                 }
             }
        }
    }

    // Fetch waveform from server for current track
    private fun fetchWaveform(trackId: Int) {
        if (trackId == currentTrackId && serverWaveformPeaks.isNotEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("MainViewModel", "Fetching waveform for track $trackId")
                val response = api.getWaveform(trackId)
                serverWaveformPeaks = response.peaks
                currentTrackId = trackId
                android.util.Log.d("MainViewModel", "Got ${serverWaveformPeaks.size} peaks for track $trackId")
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to fetch waveform", e)
                serverWaveformPeaks = emptyList()
            }
        }
    }

    private fun startWaveformAnimationLoop() {
        if (simulationJob?.isActive == true) return
        simulationJob = viewModelScope.launch(Dispatchers.Default) {
             while(true) {
                 if (isPlaying.value && serverWaveformPeaks.isNotEmpty()) {
                     // Calculate current position in the waveform based on playback progress
                     val progress = if (duration.value > 0) {
                         currentPosition.value.toFloat() / duration.value.toFloat()
                     } else 0f
                     
                     val numPeaks = serverWaveformPeaks.size
                     val durationSeconds = duration.value / 1000f
                     val displayBars = 60 // Number of bars to display
                     
                     // Show only ~2 seconds of audio at a time
                     // Calculate how many peaks represent 2 seconds
                     val windowSeconds = 2f
                     val peaksPerSecond = if (durationSeconds > 0) numPeaks / durationSeconds else numPeaks.toFloat()
                     val windowPeaks = (peaksPerSecond * windowSeconds).toInt().coerceIn(1, numPeaks)
                     
                     // Calculate the center peak based on current playback position
                     val centerPeak = (progress * numPeaks).toInt().coerceIn(0, numPeaks - 1)
                     val halfWindow = windowPeaks / 2
                     val startIndex = (centerPeak - halfWindow).coerceIn(0, numPeaks - windowPeaks)
                     
                     // Build visualizer amplitudes from 2-second window of waveform data
                     val amplitudes = mutableListOf<Float>()
                     val timeOffset = System.currentTimeMillis() / 80.0 // Faster pulsing
                     
                     for (i in 0 until displayBars) {
                         // Map display bars to the 2-second window of peaks
                         val peakIndex = startIndex + (i.toFloat() / displayBars * windowPeaks).toInt()
                         val clampedIndex = peakIndex.coerceIn(0, numPeaks - 1)
                         var baseAmplitude = serverWaveformPeaks[clampedIndex]
                         
                         // Apply power curve for visual contrast
                         baseAmplitude = Math.pow(baseAmplitude.toDouble(), 1.5).toFloat()
                         
                         // Center SIZE emphasis - bars at center are much taller
                         // Edges are nearly flat (~5%), center is full height
                         val center = displayBars / 2f
                         val distFromCenter = kotlin.math.abs(i - center) / center
                         // Sharp gaussian-like falloff for dramatic size difference
                         val centerBoost = 0.05f + 0.95f * Math.pow((1.0 - distFromCenter).toDouble(), 2.5).toFloat()
                         
                         // Add time-based pulsing (±20%)
                         val pulse = 0.8f + 0.2f * kotlin.math.sin(timeOffset + i * 0.5).toFloat()
                         val amplitude = (baseAmplitude * pulse * centerBoost).coerceIn(0.05f, 1f)
                         
                         amplitudes.add(amplitude)
                     }
                     
                     updateAmplitudesInternal(amplitudes)
                 } else if (isPlaying.value) {
                     // Fallback simulation while waveform loads
                     val simulated = List(60) {
                         val noise = kotlin.random.Random.nextFloat() * 0.3f
                         val wave = kotlin.math.sin(it * 0.2 + System.currentTimeMillis() * 0.003).toFloat() * 0.3f + 0.4f
                         (noise + wave).coerceIn(0.1f, 1f)
                     }
                     updateAmplitudesInternal(simulated)
                 }
                 delay(VISUALIZER_UPDATE_INTERVAL)
             }
        }
    }
    
    fun initializeController(context: Context) {
        // Start simulation immediately as fallback
        startWaveformAnimationLoop()

        if (mediaBrowser != null) return
        
        val sessionToken = SessionToken(context, ComponentName(context, BombestMediaService::class.java))
        
        val browserListener = object : MediaBrowser.Listener {
            override fun onChildrenChanged(
                browser: MediaBrowser,
                parentId: String,
                itemCount: Int,
                params: MediaLibraryService.LibraryParams?
            ) {
                if (parentId == "root") {
                    fetchChildren()
                }
            }
        }

        browserFuture = MediaBrowser.Builder(context, sessionToken)
            .setListener(browserListener)
            .buildAsync()
        
        browserFuture?.addListener({
            try {
                mediaBrowser = browserFuture?.get()
                setupPlayerListeners()
                // Initial state sync
                syncPlayerState()
                fetchChildren() // Populate local playlist from service
                
                // Request Audio Session ID for Visualizer
                requestAudioSessionId()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListeners() {
        mediaBrowser?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMediaItem.value = mediaItem
                currentPosition.value = 0L // Reset immediately to prevent UI flash
                updateDuration()
                
                // Fetch waveform for new track
                mediaItem?.mediaId?.toIntOrNull()?.let { trackId ->
                    fetchWaveform(trackId)
                }
            }

            override fun onIsPlayingChanged(isPlayingState: Boolean) {
                isPlaying.value = isPlayingState
                if (isPlayingState) startProgressLoop() else stopProgressLoop()
            }
            
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                isShuffleEnabled.value = shuffleModeEnabled
            }
            
            override fun onRepeatModeChanged(repeatModeVal: Int) {
                repeatMode.value = repeatModeVal
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    updateDuration()
                }
            }
        })
    }
    
    private fun syncPlayerState() {
        mediaBrowser?.let { controller ->
            currentMediaItem.value = controller.currentMediaItem
            isPlaying.value = controller.isPlaying
            isShuffleEnabled.value = controller.shuffleModeEnabled
            repeatMode.value = controller.repeatMode
            updateDuration()
            if (controller.isPlaying) startProgressLoop()
        }
    }

    private fun updateDuration() {
        mediaBrowser?.let { 
             val d = it.duration
             duration.value = if (d == androidx.media3.common.C.TIME_UNSET) 0L else d
        }
    }

    private fun startProgressLoop() {
        stopProgressLoop()
        updateProgressJob = scope.launch(Dispatchers.Main) {
            while(true) {
                mediaBrowser?.let {
                    currentPosition.value = it.currentPosition
                }
                delay(500) // Update every 500ms
            }
        }
    }
    
    private fun stopProgressLoop() {
        updateProgressJob?.cancel()
        updateProgressJob = null
    }

    // ...

    fun seekTo(positionMs: Long) {
        mediaBrowser?.seekTo(positionMs)
        currentPosition.value = positionMs
    }
    
    fun stop() {
        mediaBrowser?.stop()
        stopProgressLoop()
    }

    fun toggleShuffle() {
        mediaBrowser?.let {
            val newEnabled = !it.shuffleModeEnabled
            it.shuffleModeEnabled = newEnabled
            if (!newEnabled) {
                // Clear history when turning shuffle off
                shuffleHistory.clear()
                unplayedIndices.clear()
            }
        }
    }
    
    fun toggleRepeat() {
        mediaBrowser?.let {
            val newMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
            it.repeatMode = newMode
        }
    }
    
    // ... existing playPause, skipNext ...
    
    private fun fetchChildren() {
        android.util.Log.d("MainViewModel", "Fetching children...")
        // We know the service has the "root" library
        val future = mediaBrowser?.getLibraryRoot(null)
        future?.addListener({
             try {
                 // Subscribe to updates for root
                 val subFuture = mediaBrowser?.subscribe("root", null)
                 subFuture?.addListener({
                     try {
                         val result = subFuture.get()
                         android.util.Log.d("MainViewModel", "Subscribed to root: ${result.resultCode}")
                     } catch (e: Exception) {
                         android.util.Log.e("MainViewModel", "Subscription failed", e)
                     }
                 }, MoreExecutors.directExecutor())
                 
                 // Once root is ready, get children
                 android.util.Log.d("MainViewModel", "Root ready, requesting children")
                 val childrenFuture = mediaBrowser?.getChildren("root", 0, Int.MAX_VALUE, null)
                 childrenFuture?.addListener({
                     try {
                         val result = childrenFuture.get()
                         android.util.Log.d("MainViewModel", "Got children: ${result.value?.size}")
                         playlist.clear()
                         result.value?.let { playlist.addAll(it) }
                     } catch(e: Exception) { 
                        android.util.Log.e("MainViewModel", "Error getting children", e)
                        e.printStackTrace() 
                     }
                 }, MoreExecutors.directExecutor())
             } catch(e: Exception) { 
                android.util.Log.e("MainViewModel", "Error getting root", e)
                e.printStackTrace()
             }
        }, MoreExecutors.directExecutor())
    }
    
    // Public refresh function for pull-to-refresh
    fun refreshLibrary() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        android.util.Log.d("MainViewModel", "Refreshing library...")
        
        // Tell the service to re-fetch from the API
        val command = androidx.media3.session.SessionCommand("REFRESH_LIBRARY", android.os.Bundle.EMPTY)
        val future = mediaBrowser?.sendCustomCommand(command, android.os.Bundle.EMPTY)
        future?.addListener({
            // After refresh command, fetch children again
            fetchChildren()
            viewModelScope.launch {
                delay(1000) // Give time for data to load
                isRefreshing.value = false
            }
        }, MoreExecutors.directExecutor()) ?: run {
            // No browser, just fetch children directly
            fetchChildren()
            viewModelScope.launch {
                delay(1000)
                isRefreshing.value = false
            }
        }
    }

    fun playMedia(mediaItem: MediaItem) {
        mediaBrowser?.let {
            // Find index in playlist
            val index = playlist.indexOfFirst { it.mediaId == mediaItem.mediaId }
            if (index != -1) {
                // Set the whole playlist so next/prev works
                it.setMediaItems(playlist)
                it.seekTo(index, 0)
                it.prepare()
                it.play()
            } else {
                // Fallback
                it.setMediaItem(mediaItem)
                it.prepare()
                it.play()
            }
        }
    }
    
    fun playPause() {
        mediaBrowser?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }
    
    fun skipNext() {
        mediaBrowser?.let { browser ->
            if (isShuffleEnabled.value && playlist.isNotEmpty()) {
                // Custom shuffle: no repeats until all played
                val currentIndex = browser.currentMediaItemIndex
                
                // Add current to history
                shuffleHistory.add(currentIndex)
                
                // Initialize unplayed if needed
                if (unplayedIndices.isEmpty()) {
                    unplayedIndices.addAll(playlist.indices)
                }
                
                // Remove current from unplayed
                unplayedIndices.remove(currentIndex)
                
                // Pick random from unplayed
                if (unplayedIndices.isEmpty()) {
                    // All played - restart, avoiding immediate repeat
                    unplayedIndices.addAll(playlist.indices)
                    unplayedIndices.remove(currentIndex)
                }
                
                val nextIndex = unplayedIndices.random()
                browser.seekTo(nextIndex, 0)
            } else {
                // Default behavior
                if (browser.hasNextMediaItem()) {
                    browser.seekToNextMediaItem()
                } else {
                    browser.seekToNext()
                }
            }
        }
    }
    
    fun skipPrevious() {
        mediaBrowser?.let { browser ->
            if (isShuffleEnabled.value && shuffleHistory.isNotEmpty()) {
                // Use history to go back to actual previous song
                val prevIndex = shuffleHistory.removeLast()
                
                // Re-add current to unplayed
                val currentIndex = browser.currentMediaItemIndex
                unplayedIndices.add(currentIndex)
                
                browser.seekTo(prevIndex, 0)
            } else {
                browser.seekToPreviousMediaItem()
            }
        }
    }
    
    fun onPermissionGranted() {
         // Retry visualizer setup if we have a session ID already? 
         // Or just re-request session ID which triggers setup
         if (mediaBrowser != null) {
             requestAudioSessionId()
         }
    }

    fun onFavorite() {
        val trackId = currentMediaItem.value?.mediaId ?: return
        val currentFavorites = favorites.value.toMutableSet()
        if (currentFavorites.contains(trackId)) {
            currentFavorites.remove(trackId)
            android.util.Log.d("MainViewModel", "Removed from favorites: $trackId")
        } else {
            currentFavorites.add(trackId)
            android.util.Log.d("MainViewModel", "Added to favorites: $trackId")
        }
        favorites.value = currentFavorites
    }

    fun onDownload() {
        val trackId = currentMediaItem.value?.mediaId ?: return
        val dm = downloadManager ?: return
        
        viewModelScope.launch {
            val streamUrl = musicRepository.getStreamUrl(trackId.toIntOrNull() ?: return@launch)
            val result = dm.downloadTrack(trackId, streamUrl)
            
            if (result.isSuccess) {
                val currentDownloads = downloads.value.toMutableSet()
                currentDownloads.add(trackId)
                downloads.value = currentDownloads
                android.util.Log.d("MainViewModel", "Track downloaded: $trackId")
            } else {
                android.util.Log.e("MainViewModel", "Download failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }
    
    fun onRemoveDownload() {
        val trackId = currentMediaItem.value?.mediaId ?: return
        val dm = downloadManager ?: return
        
        viewModelScope.launch {
            val result = dm.removeDownload(trackId)
            
            if (result.isSuccess) {
                val currentDownloads = downloads.value.toMutableSet()
                currentDownloads.remove(trackId)
                downloads.value = currentDownloads
                android.util.Log.d("MainViewModel", "Download removed: $trackId")
            } else {
                android.util.Log.e("MainViewModel", "Remove failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }
    
    fun initDownloadManager(context: Context) {
        if (downloadManager == null) {
            downloadManager = DownloadManager.getInstance(context)
            // Load persisted download state
            viewModelScope.launch {
                val downloadedTracks = downloadManager?.getDownloadedTracks() ?: emptySet()
                downloads.value = downloadedTracks
            }
        }
    }

    fun onShare() {
        // Share is handled externally via MainActivity since it needs Context
        android.util.Log.d("MainViewModel", "Share clicked")
    }
    
    fun isFavorite(trackId: String): Boolean {
        return favorites.value.contains(trackId)
    }
    
    fun onSMS(context: android.content.Context) {
        // Open SMS with pre-filled message
        currentMediaItem.value?.let { mediaItem ->
            val title = mediaItem.mediaMetadata.title?.toString() ?: "this song"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("sms:")
                putExtra("sms_body", "hey thomas, i'm interested in $title")
            }
            context.startActivity(intent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
    }
}
