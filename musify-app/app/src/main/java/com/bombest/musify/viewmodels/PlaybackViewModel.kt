package com.bombest.musify.viewmodels

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bombest.musify.domain.PodcastEpisode
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.domain.Streamable
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.musicplayer.MusicPlayerV2
import com.bombest.musify.musicplayer.PlaybackQueueManager
import com.bombest.musify.usecases.downloadDrawableFromUrlUseCase.DownloadDrawableFromUrlUseCase
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import android.util.Log
import java.io.File
import java.io.FileWriter
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    application: Application,
    private val musicPlayer: MusicPlayerV2,
    private val downloadDrawableFromUrlUseCase: DownloadDrawableFromUrlUseCase,
    private val playbackQueueManager: PlaybackQueueManager,
    private val tracksRepository: TracksRepository,
    private val exoPlayer: androidx.media3.exoplayer.ExoPlayer
) : AndroidViewModel(application) {
    
    init {
        // Listen to ExoPlayer repeat changes to sync with PlaybackQueueManager
        // CRITICAL: We don't sync shuffle from ExoPlayer - we use custom Spotify-style shuffle
        // This ensures Android Auto changes are reflected in PlaybackQueueManager
        exoPlayer.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // CRITICAL: Always disable ExoPlayer's shuffle - we use custom Spotify-style shuffle
                // If ExoPlayer shuffle gets enabled (e.g., from Android Auto), disable it
                if (shuffleModeEnabled) {
                    exoPlayer.shuffleModeEnabled = false
                    Log.d("PlaybackViewModel", "ExoPlayer shuffle was enabled, disabled it (using custom shuffle)")
                }
            }
            
            override fun onRepeatModeChanged(repeatMode: Int) {
                // Sync ExoPlayer state to PlaybackQueueManager (for Android Auto changes)
                val queueRepeatMode = when (repeatMode) {
                    Player.REPEAT_MODE_OFF -> PlaybackQueueManager.RepeatMode.NONE
                    Player.REPEAT_MODE_ALL -> PlaybackQueueManager.RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> PlaybackQueueManager.RepeatMode.ONE
                    else -> PlaybackQueueManager.RepeatMode.NONE
                }
                // Only update if state differs to avoid infinite loops
                if (playbackQueueManager.repeatMode.value != queueRepeatMode) {
                    playbackQueueManager.setRepeatMode(queueRepeatMode)
                }
            }
        })
    }

    private val _totalDurationOfCurrentTrackTimeText = mutableStateOf("00:00")
    val totalDurationOfCurrentTrackTimeText = _totalDurationOfCurrentTrackTimeText as State<String>

    private val _totalDurationOfCurrentTrackMillis = mutableStateOf(0L)
    private val totalDurationOfCurrentTrackMillis: Long
        get() = _totalDurationOfCurrentTrackMillis.value

    private val _playbackState = mutableStateOf<PlaybackState>(PlaybackState.Idle)
    val playbackState = _playbackState as State<PlaybackState>

    private val _eventChannel = Channel<Event?>()
    val playbackEventsFlow = _eventChannel.receiveAsFlow()

    // 0f to 100f
    val flowOfProgressOfCurrentTrack = mutableStateOf<Flow<Float>>(emptyFlow())
    val flowOfProgressTextOfCurrentTrack = mutableStateOf<Flow<String>>(emptyFlow())

    private val playbackErrorMessage = "An error occurred. Please check internet connection."

    init {
        musicPlayer.currentPlaybackStateStream.onEach {
            val newState = when (it) {
                is MusicPlayerV2.PlaybackState.Loading -> PlaybackState.Loading(it.previouslyPlayingStreamable)
                is MusicPlayerV2.PlaybackState.Idle -> PlaybackState.Idle
                is MusicPlayerV2.PlaybackState.Playing -> {
                    _totalDurationOfCurrentTrackTimeText.value =
                        convertTimestampMillisToString(it.totalDuration)
                    _totalDurationOfCurrentTrackMillis.value = it.totalDuration
                    flowOfProgressOfCurrentTrack.value =
                        it.currentPlaybackPositionInMillisFlow.map { progress -> (progress.toFloat() / it.totalDuration) * 100f }
                    flowOfProgressTextOfCurrentTrack.value =
                        it.currentPlaybackPositionInMillisFlow.map(::convertTimestampMillisToString)
                    PlaybackState.Playing(it.currentlyPlayingStreamable)
                }
                is MusicPlayerV2.PlaybackState.Paused -> PlaybackState.Paused(it.currentlyPlayingStreamable)
                is MusicPlayerV2.PlaybackState.Error -> {
                    viewModelScope.launch {
                        _eventChannel.send(Event.PlaybackError(playbackErrorMessage))
                    }
                    PlaybackState.Error(playbackErrorMessage)
                }
                is MusicPlayerV2.PlaybackState.Ended -> {
                    // Auto-advance to next track if available
                    handleTrackEnded(it.streamable)
                    PlaybackState.PlaybackEnded(it.streamable)
                }
            }
            _playbackState.value = newState
        }.launchIn(viewModelScope)
    }
    
    private fun handleTrackEnded(streamable: Streamable) {
        if (streamable is SearchResult.TrackSearchResult) {
            viewModelScope.launch {
                // Ensure queue is initialized
                if (playbackQueueManager.queue.value.isEmpty()) {
                    val allTracks = tracksRepository.getAllTracks()
                    if (allTracks.isNotEmpty()) {
                        playbackQueueManager.setQueue(allTracks)
                        playbackQueueManager.setCurrentTrack(streamable)
                    }
                } else {
                    // Make sure current track is set in queue
                    playbackQueueManager.setCurrentTrack(streamable)
                }
                
                // Get next track based on repeat mode
                val nextTrack = playbackQueueManager.getNextTrack()
                if (nextTrack != null) {
                    // Small delay to ensure the Ended state is processed
                    kotlinx.coroutines.delay(100)
                    playStreamable(nextTrack)
                }
            }
        }
    }

    fun resumeIfPausedOrPlay(streamable: Streamable){
        if(musicPlayer.tryResume()) return
        playStreamable(streamable)
    }

    fun playStreamable(streamable: Streamable) {
        viewModelScope.launch {
            if (streamable.streamInfo.streamUrl == null) {
                val streamableType = when (streamable) {
                    is PodcastEpisode -> "podcast episode"
                    is SearchResult.TrackSearchResult -> "track"
                }
                Log.e("PlaybackViewModel", "playStreamable: streamUrl is null for ${streamable.streamInfo.title}")
                _eventChannel.send(Event.PlaybackError("This $streamableType is currently unavailable for playback."))
                return@launch
            }

            // Update queue if this is a track
            if (streamable is SearchResult.TrackSearchResult) {
                // Initialize queue with all tracks if empty
                val currentQueue = playbackQueueManager.queue.value
                if (currentQueue.isEmpty()) {
                    val allTracks = tracksRepository.getAllTracks()
                    if (allTracks.isNotEmpty()) {
                        playbackQueueManager.setQueue(allTracks)
                    }
                }
                playbackQueueManager.setCurrentTrack(streamable)
            }

            val downloadAlbumArtResult = downloadDrawableFromUrlUseCase.invoke(
                urlString = streamable.streamInfo.imageUrl,
                context = getApplication()
            )
            val bitmap = if (downloadAlbumArtResult.isSuccess) {
                downloadAlbumArtResult.getOrNull()!!.toBitmap()
            } else {
                Log.w("PlaybackViewModel", "Album art failed for ${streamable.streamInfo.title}, using placeholder. Cause: ${downloadAlbumArtResult.exceptionOrNull()?.message}")
                // Use a 1x1 transparent bitmap so playback is not blocked by artwork failure
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            }
            try {
                musicPlayer.playStreamable(
                    streamable = streamable,
                    associatedAlbumArt = bitmap
                )
            } catch (e: Exception) {
                Log.e("PlaybackViewModel", "playStreamable failed for ${streamable.streamInfo.title}", e)
                val msg = e.message ?: playbackErrorMessage
                _eventChannel.send(Event.PlaybackError("Playback failed: $msg"))
                _playbackState.value = PlaybackState.Error(msg)
            }
        }
    }

    fun pauseCurrentlyPlayingTrack() {
        musicPlayer.pauseCurrentlyPlayingTrack()
    }

    private val _isScrubbing = mutableStateOf(false)
    val isScrubbing: Boolean
        get() = _isScrubbing.value
    
    private var lastSeekTime = 0L
    private var seekCount = 0
    private val SCRUB_SEEK_THROTTLE_MS = 50L // Throttle seeks to max once every 50ms for smoother audio
    private var wasPlayingBeforeScrub = false // Store original playback state when scrubbing starts
    private var scrubPauseJob: Job? = null // Job to pause playback after each seek during scrubbing
    private var lastScrubbedPosition: Float = -1f // Track last scrubbed position to avoid seeking to same position
    private val MIN_POSITION_CHANGE_PERCENT = 0.5f // Minimum position change (0.5%) to trigger a seek
    
    fun seekTo(progressPercent: Float) {
        val playbackState = _playbackState.value
        if ((playbackState is PlaybackState.Playing || playbackState is PlaybackState.Paused) && totalDurationOfCurrentTrackMillis > 0) {
            val positionMillis = (totalDurationOfCurrentTrackMillis * progressPercent / 100f).toLong()
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"location\":\"PlaybackViewModel.kt:197\",\"message\":\"seekTo called\",\"data\":{\"progressPercent\":$progressPercent,\"positionMillis\":$positionMillis,\"totalDuration\":$totalDurationOfCurrentTrackMillis,\"playbackState\":\"$playbackState\"},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
            } catch (e: Exception) {}
            // #endregion
            musicPlayer.seekTo(positionMillis)
        } else {
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"location\":\"PlaybackViewModel.kt:203\",\"message\":\"seekTo skipped - invalid state\",\"data\":{\"playbackState\":\"$playbackState\",\"totalDuration\":$totalDurationOfCurrentTrackMillis},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
            } catch (e: Exception) {}
            // #endregion
        }
    }
    
    fun startScrubbing() {
        // #region agent log
        try {
            val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run4\",\"hypothesisId\":\"TOUCH\",\"location\":\"PlaybackViewModel.kt:230\",\"message\":\"startScrubbing called\",\"data\":{\"playbackState\":\"${_playbackState.value}\",\"isPlaying\":${exoPlayer.isPlaying},\"playWhenReady\":${exoPlayer.playWhenReady},\"playbackStateInt\":${exoPlayer.playbackState}},\"timestamp\":${System.currentTimeMillis()}}\n")
            }
        } catch (e: Exception) {}
        // #endregion
        _isScrubbing.value = true
        lastSeekTime = 0L
        seekCount = 0
        lastScrubbedPosition = -1f // Reset last scrubbed position
        
        // Store original playback state - we'll restore it when scrubbing ends (on touch up)
        wasPlayingBeforeScrub = exoPlayer.playWhenReady
        
        // Cancel any pending pause job
        scrubPauseJob?.cancel()
        
        // Pause playback on touch down - prevents auto-advancing while scrubbing
        exoPlayer.playWhenReady = false
        // #region agent log
        try {
            val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run4\",\"hypothesisId\":\"TOUCH\",\"location\":\"PlaybackViewModel.kt:243\",\"message\":\"playback paused on touch down\",\"data\":{\"wasPlayingBeforeScrub\":$wasPlayingBeforeScrub,\"playWhenReady\":${exoPlayer.playWhenReady}},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
        } catch (e: Exception) {}
        // #endregion
    }
    
    fun updateScrubPosition(progressPercent: Float) {
        if (_isScrubbing.value) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastSeek = if (lastSeekTime > 0) currentTime - lastSeekTime else 0L
            seekCount++
            
            // Check if position has changed significantly (avoid seeking to same position)
            val positionChanged = lastScrubbedPosition < 0f || 
                kotlin.math.abs(progressPercent - lastScrubbedPosition) >= MIN_POSITION_CHANGE_PERCENT
            
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run6\",\"hypothesisId\":\"POSITION\",\"location\":\"PlaybackViewModel.kt:263\",\"message\":\"updateScrubPosition called\",\"data\":{\"progressPercent\":$progressPercent,\"lastScrubbedPosition\":$lastScrubbedPosition,\"positionChanged\":$positionChanged,\"seekCount\":$seekCount,\"timeSinceLastSeek\":$timeSinceLastSeek,\"isPlaying\":${exoPlayer.isPlaying},\"playWhenReady\":${exoPlayer.playWhenReady}},\"timestamp\":$currentTime}\n")
                }
            } catch (e: Exception) {}
            // #endregion
            
            // Only seek if position has changed significantly and enough time has passed
            // This prevents rapid play/pause toggling when holding finger in one spot
            if (positionChanged && (timeSinceLastSeek >= SCRUB_SEEK_THROTTLE_MS || lastSeekTime == 0L)) {
                // Cancel any pending pause job from previous seek
                scrubPauseJob?.cancel()
                
                // Live scrubbing while dragging: enable playback and seek to new position
                // This provides continuous audio at the scrubbed position during drag
                exoPlayer.playWhenReady = true
                seekTo(progressPercent)
                lastSeekTime = currentTime
                lastScrubbedPosition = progressPercent // Update last scrubbed position
                
                // Pause playback after a brief window to prevent it from continuing forward
                // This ensures playback only happens at the scrubbed position, not advancing
                scrubPauseJob = viewModelScope.launch {
                    delay(50) // Brief window to hear audio at scrubbed position
                    if (_isScrubbing.value) { // Only pause if still scrubbing
                        exoPlayer.playWhenReady = false
                        // #region agent log
                        try {
                            val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                            FileWriter(logFile, true).use { writer ->
                                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run6\",\"hypothesisId\":\"PAUSE\",\"location\":\"PlaybackViewModel.kt:295\",\"message\":\"playback paused after scrub seek\",\"data\":{\"playWhenReady\":${exoPlayer.playWhenReady}},\"timestamp\":${System.currentTimeMillis()}}\n")
                            }
                        } catch (e: Exception) {}
                        // #endregion
                    }
                }
                
                // #region agent log
                try {
                    val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                    FileWriter(logFile, true).use { writer ->
                        writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run6\",\"hypothesisId\":\"TOUCH\",\"location\":\"PlaybackViewModel.kt:305\",\"message\":\"scrub seek executed while dragging\",\"data\":{\"isPlaying\":${exoPlayer.isPlaying},\"playWhenReady\":${exoPlayer.playWhenReady},\"currentPosition\":${exoPlayer.currentPosition}},\"timestamp\":${System.currentTimeMillis()}}\n")
                    }
                } catch (e: Exception) {}
                // #endregion
            } else {
                // #region agent log
                try {
                    val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                    FileWriter(logFile, true).use { writer ->
                        writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run3\",\"hypothesisId\":\"F\",\"location\":\"PlaybackViewModel.kt:285\",\"message\":\"seek throttled\",\"data\":{\"timeSinceLastSeek\":$timeSinceLastSeek,\"throttleMs\":$SCRUB_SEEK_THROTTLE_MS},\"timestamp\":$currentTime}\n")
                    }
                } catch (e: Exception) {}
                // #endregion
            }
        }
    }
    
    fun endScrubbing() {
        // #region agent log
        try {
            val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run5\",\"hypothesisId\":\"TOUCH\",\"location\":\"PlaybackViewModel.kt:302\",\"message\":\"endScrubbing called (touch up)\",\"data\":{\"wasPlayingBeforeScrub\":$wasPlayingBeforeScrub,\"playWhenReady\":${exoPlayer.playWhenReady}},\"timestamp\":${System.currentTimeMillis()}}\n")
            }
        } catch (e: Exception) {}
        // #endregion
        
        _isScrubbing.value = false
        
        // Cancel any pending pause job
        scrubPauseJob?.cancel()
        scrubPauseJob = null
        
        // Resume playback on touch up if it was playing before scrubbing started
        // This restores the original playback state
        exoPlayer.playWhenReady = wasPlayingBeforeScrub
        
        // #region agent log
        try {
            val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run4\",\"hypothesisId\":\"TOUCH\",\"location\":\"PlaybackViewModel.kt:310\",\"message\":\"playback resumed on touch up\",\"data\":{\"playWhenReady\":${exoPlayer.playWhenReady},\"isPlaying\":${exoPlayer.isPlaying}},\"timestamp\":${System.currentTimeMillis()}}\n")
            }
        } catch (e: Exception) {}
        // #endregion
    }
    
    val audioSessionId: Int
        get() {
            val sessionId = musicPlayer.getAudioSessionId()
            if (sessionId <= 0) {
                Log.w("PlaybackViewModel", "Audio session ID is invalid: $sessionId")
            }
            return sessionId
        }
    
    fun skipToNext() {
        viewModelScope.launch {
            // Ensure queue is initialized
            if (playbackQueueManager.queue.value.isEmpty()) {
                val allTracks = tracksRepository.getAllTracks()
                if (allTracks.isNotEmpty()) {
                    playbackQueueManager.setQueue(allTracks)
                    // Set current track if playing
                    _playbackState.value.currentlyPlayingStreamable?.let {
                        if (it is SearchResult.TrackSearchResult) {
                            playbackQueueManager.setCurrentTrack(it)
                        }
                    }
                }
            }
            
            val nextTrack = playbackQueueManager.getNextTrack()
            if (nextTrack != null) {
                // If shuffle is enabled, update ExoPlayer queue to match shuffled order
                if (playbackQueueManager.isShuffled.value) {
                    updateExoPlayerQueue()
                }
                playStreamable(nextTrack)
            }
        }
    }
    
    fun skipToPrevious() {
        viewModelScope.launch {
            // Check if track has been playing for more than 2.5 seconds
            // If so, restart the current track instead of going to previous
            val currentPositionMillis = exoPlayer.currentPosition
            val restartThresholdMillis = 2500L // 2.5 seconds
            
            if (currentPositionMillis > restartThresholdMillis) {
                // Restart current track
                musicPlayer.seekTo(0)
                return@launch
            }
            
            // Otherwise, go to previous track
            // Ensure queue is initialized
            if (playbackQueueManager.queue.value.isEmpty()) {
                val allTracks = tracksRepository.getAllTracks()
                if (allTracks.isNotEmpty()) {
                    playbackQueueManager.setQueue(allTracks)
                    // Set current track if playing
                    _playbackState.value.currentlyPlayingStreamable?.let {
                        if (it is SearchResult.TrackSearchResult) {
                            playbackQueueManager.setCurrentTrack(it)
                        }
                    }
                }
            }
            
            val prevTrack = playbackQueueManager.getPreviousTrack()
            if (prevTrack != null) {
                playStreamable(prevTrack)
            }
        }
    }
    
    fun toggleShuffle() {
        playbackQueueManager.toggleShuffle()
        // CRITICAL: Disable ExoPlayer's built-in shuffle - we use custom Spotify-style shuffle
        // ExoPlayer's shuffle can repeat songs before all are played, which we don't want
        exoPlayer.shuffleModeEnabled = false
        // Update ExoPlayer queue with shuffled order from PlaybackQueueManager
        updateExoPlayerQueue()
        Log.d("PlaybackViewModel", "Shuffle toggled: ${playbackQueueManager.isShuffled.value}, ExoPlayer shuffle disabled (using custom shuffle)")
    }
    
    private fun updateExoPlayerQueue() {
        viewModelScope.launch {
            val queue = playbackQueueManager.queue.value
            if (queue.isNotEmpty()) {
                val currentTrack = playbackQueueManager.getCurrentTrack()
                val currentMediaId = currentTrack?.id
                
                // Convert tracks to MediaItems
                val mediaItems = queue.map { track ->
                    val artworkUri = try {
                        android.net.Uri.parse(track.imageUrlString)
                    } catch (e: Exception) {
                        null
                    }
                    androidx.media3.common.MediaItem.Builder()
                        .setMediaId(track.id)
                        .setUri(track.trackUrlString)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(track.name)
                                .setArtist(track.artistsString)
                                .apply {
                                    artworkUri?.let { setArtworkUri(it) }
                                }
                                .build()
                        )
                        .build()
                }
                
                // Find current track index in the new queue
                val currentIndex = currentMediaId?.let { id ->
                    mediaItems.indexOfFirst { it.mediaId == id }.takeIf { it >= 0 }
                } ?: playbackQueueManager.currentIndex.value ?: 0
                
                val wasPlaying = exoPlayer.isPlaying
                val currentPosition = exoPlayer.currentPosition
                
                // Update ExoPlayer queue
                exoPlayer.setMediaItems(mediaItems, /* resetPosition= */ false)
                
                // Restore current position
                if (currentIndex < mediaItems.size) {
                    exoPlayer.seekToDefaultPosition(currentIndex)
                    // Restore playback position within track if it was playing
                    if (currentPosition > 0 && currentPosition < exoPlayer.duration) {
                        exoPlayer.seekTo(currentPosition)
                    }
                    // Restore playing state
                    if (wasPlaying) {
                        exoPlayer.play()
                    }
                }
                
                Log.d("PlaybackViewModel", "Updated ExoPlayer queue: ${mediaItems.size} items, currentIndex=$currentIndex")
            }
        }
    }
    
    fun toggleRepeat() {
        val newMode = playbackQueueManager.toggleRepeat()
        // Sync with ExoPlayer for Android Auto - Android Auto reads ExoPlayer's state directly
        val exoPlayerRepeatMode = when (newMode) {
            PlaybackQueueManager.RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            PlaybackQueueManager.RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            PlaybackQueueManager.RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        exoPlayer.repeatMode = exoPlayerRepeatMode
        Log.d("PlaybackViewModel", "Repeat mode changed: $newMode -> $exoPlayerRepeatMode")
    }
    
    val isShuffled: Boolean
        get() = playbackQueueManager.isShuffled.value
    
    val repeatMode: PlaybackQueueManager.RepeatMode
        get() = playbackQueueManager.repeatMode.value
    
    // Expose StateFlows for reactive observation in Compose
    val isShuffledFlow: StateFlow<Boolean> = playbackQueueManager.isShuffled
    val repeatModeFlow: StateFlow<PlaybackQueueManager.RepeatMode> = playbackQueueManager.repeatMode

    private fun convertTimestampMillisToString(millis: Long): String = with(TimeUnit.MILLISECONDS) {
        // don't display the hour information if the track's duration is
        // less than an hour
        if (toHours(millis) == 0L) "%02d:%02d".format(
            toMinutes(millis), toSeconds(millis) % 60
        )
        else "%02d:%02d:%02d".format(
            toHours(millis), toMinutes(millis) % 60, toSeconds(millis) % 60
        )
    }

    companion object {
        val PLAYBACK_PROGRESS_RANGE = 0f..100f
    }

    sealed class PlaybackState(
        val currentlyPlayingStreamable: Streamable? = null,
        val previouslyPlayingStreamable: Streamable? = null
    ) {
        object Idle : PlaybackState()
        object Stopped : PlaybackState()
        data class Error(val errorMessage: String) : PlaybackState()
        data class Paused(val streamable: Streamable) : PlaybackState(streamable)
        data class Playing(val streamable: Streamable) : PlaybackState(streamable)
        data class PlaybackEnded(val streamable: Streamable) : PlaybackState(streamable)
        data class Loading(
            // Streamable instance that indicates the track that was playing before
            // the state was changed to loading
            val previousStreamable: Streamable?
        ) : PlaybackState(previouslyPlayingStreamable = previousStreamable)
    }

    sealed class Event {
        // a data class is not used because a 'Channel' will not send
        // two items of the same type consecutively. Since a data class
        // overrides equals & hashcode by default, if the same event
        // occurs consecutively, the event will not be sent over the
        // channel, resulting in missed events.
        class PlaybackError(val errorMessage: String) : Event()
    }
}
