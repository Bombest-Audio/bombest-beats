package com.bombest.musify.musicplayer

import android.content.Context
import android.graphics.Bitmap
import com.bombest.musify.R
import com.bombest.musify.data.download.DownloadManager
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.domain.Streamable
import com.bombest.musify.musicplayer.utils.getCurrentPlaybackProgressFlow
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.util.UnstableApi
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@UnstableApi
class MusifyBackgroundMusicPlayerV2 @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exoPlayer: ExoPlayer,
    private val downloadManager: DownloadManager
) : MusicPlayerV2 {
    private var currentlyPlayingStreamable: Streamable? = null

    override val currentPlaybackStateStream: Flow<MusicPlayerV2.PlaybackState> = callbackFlow {
        val listener = createEventsListener { player, events ->
            if (!events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAYER_ERROR,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_IS_LOADING_CHANGED
                )
            ) return@createEventsListener
            val isPlaying =
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.playbackState == Player.STATE_READY && player.playWhenReady
            val isPaused =
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.playbackState == Player.STATE_READY && !player.playWhenReady
            val newPlaybackState = when {
                events.contains(Player.EVENT_PLAYER_ERROR) -> MusicPlayerV2.PlaybackState.Error
                isPlaying -> currentlyPlayingStreamable?.let { buildPlayingState(it, player) }
                isPaused -> currentlyPlayingStreamable?.let { 
                    MusicPlayerV2.PlaybackState.Paused(
                        it,
                        playbackSpeed = player.playbackParameters.speed
                    )
                }
                player.playbackState == Player.STATE_IDLE -> MusicPlayerV2.PlaybackState.Idle
                player.playbackState == Player.STATE_ENDED -> currentlyPlayingStreamable?.let(
                    MusicPlayerV2.PlaybackState::Ended
                )
                player.isLoading -> MusicPlayerV2.PlaybackState.Loading(
                    previouslyPlayingStreamable = currentlyPlayingStreamable,
                    bufferPercentage = calculateBufferPercentage(player)
                )
                else -> null
            } ?: return@createEventsListener
            trySend(newPlaybackState)
        }
        exoPlayer.addListener(listener)
        awaitClose { exoPlayer.removeListener(listener) }
        // This callback can be called multiple times on events that may
        // not be of relevance. This may lead to the generation of a new
        // state that is equivalent to the old state. Therefore use
        // distinctUntilChanged
    }.distinctUntilChanged()
        .stateIn(
            // Convert to stateflow so that new subscribers always get the latest value.
            // For example, if the user starts playing a track on the search screen
            // and moves to an album detail screen containing the same track, then
            // the subscriber associated with the detail screen can be used to
            // highlight the playing track. It is able to do so because, the first
            // value that the new subscriber gets will be the currently playing track.
            scope = CoroutineScope(Dispatchers.Default),
            started = SharingStarted.WhileSubscribed(500),
            initialValue = MusicPlayerV2.PlaybackState.Idle
        )

    private fun createEventsListener(onEvents: (Player, Player.Events) -> Unit) =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                onEvents(player, events)
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("MusifyPlayer", "ExoPlayer error: ${error.message}", error)
                Log.e("MusifyPlayer", "ExoPlayer error cause: ${error.cause?.message}")
                Log.e("MusifyPlayer", "ExoPlayer errorCode: ${error.errorCode}")
            }
        }

    private fun buildPlayingState(
        streamable: Streamable,
        player: Player,
    ) = MusicPlayerV2.PlaybackState.Playing(
        currentlyPlayingStreamable = streamable,
        totalDuration = player.duration,
        currentPlaybackPositionInMillisFlow = player.getCurrentPlaybackProgressFlow(),
        bufferPercentage = calculateBufferPercentage(player),
        playbackSpeed = player.playbackParameters.speed
    )
    
    private fun calculateBufferPercentage(player: Player): Int {
        val duration = player.duration
        val bufferedPosition = player.bufferedPosition
        return if (duration > 0 && bufferedPosition > 0) {
            ((bufferedPosition.toFloat() / duration.toFloat()) * 100f).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    override fun playStreamable(
        streamable: Streamable,
        associatedAlbumArt: Bitmap
    ) {
        with(exoPlayer) {
            if (streamable.streamInfo.streamUrl == null) {
                Log.e("MusifyPlayer", "playStreamable: streamUrl is null for ${streamable.streamInfo.title}")
                return@with
            }
            
            // Check if track is downloaded locally (for S3 tracks)
            val streamUrl = if (streamable is SearchResult.TrackSearchResult) {
                val localUrl = downloadManager.getLocalFileUrl(streamable.id)
                localUrl ?: streamable.streamInfo.streamUrl
            } else {
                streamable.streamInfo.streamUrl
            }
            
            if (streamUrl == null) {
                Log.e("MusifyPlayer", "playStreamable: resolved streamUrl is null for ${streamable.streamInfo.title}")
                return@with
            }
            Log.d("MusifyPlayer", "playStreamable: playing ${streamable.streamInfo.title}, url=$streamUrl")
            
            if (currentlyPlayingStreamable == streamable) {
                seekTo(0)
                // without this statement, after seeking to the start,
                // the player will be ready to play, but will not actually
                // start the playback if playWhenReady is set to false.
                playWhenReady = true
                return@with
            }
            if (isPlaying) exoPlayer.stop()
            currentlyPlayingStreamable = streamable
            
            // Start the PlaybackService FIRST to ensure MediaSession is ready
            val serviceIntent = Intent(context, PlaybackService::class.java)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d("MusifyPlayer", "PlaybackService started")
            } catch (e: Exception) {
                Log.e("MusifyPlayer", "Failed to start PlaybackService", e)
            }
            
            // Create MediaItem with metadata for notification
            val artworkUri = try {
                Uri.parse(streamable.streamInfo.imageUrl)
            } catch (e: Exception) {
                null
            }
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(streamable.streamInfo.title)
                .setArtist(streamable.streamInfo.subtitle)
                .apply {
                    artworkUri?.let { setArtworkUri(it) }
                }
                .build()
            
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(streamUrl)
                .setMediaMetadata(mediaMetadata)
            
            // Set mediaId if this is a TrackSearchResult for queue management
            if (streamable is SearchResult.TrackSearchResult) {
                mediaItemBuilder.setMediaId(streamable.id)
            }
            
            val mediaItem = mediaItemBuilder.build()
            
            // Check if the item is already in the player's queue
            val existingIndex = if (mediaItem.mediaId != null && exoPlayer.mediaItemCount > 0) {
                // Search through the player's media items
                var foundIndex = -1
                for (i in 0 until exoPlayer.mediaItemCount) {
                    val item = exoPlayer.getMediaItemAt(i)
                    if (item.mediaId == mediaItem.mediaId) {
                        foundIndex = i
                        break
                    }
                }
                foundIndex
            } else if (exoPlayer.mediaItemCount > 0) {
                // Search by URI if no mediaId
                var foundIndex = -1
                for (i in 0 until exoPlayer.mediaItemCount) {
                    val item = exoPlayer.getMediaItemAt(i)
                    if (item.requestMetadata.mediaUri?.toString() == streamUrl) {
                        foundIndex = i
                        break
                    }
                }
                foundIndex
            } else {
                -1
            }
            
            if (existingIndex >= 0 && exoPlayer.mediaItemCount > 1) {
                // Item is in queue, just seek to it
                Log.d("MusifyPlayer", "Track found in queue at index $existingIndex, seeking to it")
                exoPlayer.seekToDefaultPosition(existingIndex)
            } else {
                // Item not in queue or queue is empty
                if (exoPlayer.mediaItemCount > 0) {
                    // Queue has items but this one isn't in it, add it and seek
                    exoPlayer.addMediaItem(mediaItem)
                    val newIndex = exoPlayer.mediaItemCount - 1
                    exoPlayer.seekToDefaultPosition(newIndex)
                    Log.d("MusifyPlayer", "Added track to queue at index $newIndex, queue size=${exoPlayer.mediaItemCount}")
                } else {
                    // Queue is empty - set as single item for now
                    // Note: PlaybackService will populate the full queue when library loads
                    // This ensures hasNextMediaItem() will return true after library loads
                    setMediaItem(mediaItem)
                    Log.d("MusifyPlayer", "Queue empty, set as single item. Queue will be populated when library loads.")
                }
            }
            
            prepare()
            play()
        }
    }

    override fun pauseCurrentlyPlayingTrack() {
        exoPlayer.pause()
    }

    override fun stopPlayingTrack() {
        exoPlayer.stop()
    }

    override fun tryResume(): Boolean {
        val hasPlaybackEnded = exoPlayer.currentPosition > exoPlayer.duration
        if (hasPlaybackEnded) return false
        if (exoPlayer.isPlaying) return false
        return currentlyPlayingStreamable?.let {
            exoPlayer.playWhenReady = true
            true
        } ?: false
    }

    override fun seekTo(positionMillis: Long) {
        // #region agent log
        try {
            val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C,D,E\",\"location\":\"MusifyBackgroundMusicPlayerV2.kt:248\",\"message\":\"seekTo entry\",\"data\":{\"positionMillis\":$positionMillis,\"playbackState\":${exoPlayer.playbackState},\"isPlaying\":${exoPlayer.isPlaying},\"playWhenReady\":${exoPlayer.playWhenReady},\"duration\":${exoPlayer.duration},\"currentPosition\":${exoPlayer.currentPosition}},\"timestamp\":${System.currentTimeMillis()}}\n")
            }
        } catch (e: Exception) {}
        // #endregion
        // State validation (from MediaPlayer-Extended): Only seek if player is in a valid state
        if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED) {
            Log.w("MusifyPlayer", "Cannot seek: player is in ${exoPlayer.playbackState} state")
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"location\":\"MusifyBackgroundMusicPlayerV2.kt:252\",\"message\":\"seekTo rejected - invalid state\",\"data\":{\"playbackState\":${exoPlayer.playbackState}},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
            } catch (e: Exception) {}
            // #endregion
            return
        }
        
        if (exoPlayer.duration > 0) {
            val clampedPosition = positionMillis.coerceIn(0, exoPlayer.duration)
            // For turntable scrubbing: seek to position
            // Note: playWhenReady is managed by PlaybackViewModel during scrubbing
            // We don't force it here to allow scrub preview behavior
            val wasPlaying = exoPlayer.isPlaying
            val beforeSeekPosition = exoPlayer.currentPosition
            val beforeBufferedPosition = exoPlayer.bufferedPosition
            
            exoPlayer.seekTo(clampedPosition)
            
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"H\",\"location\":\"MusifyBackgroundMusicPlayerV2.kt:275\",\"message\":\"seekTo executed\",\"data\":{\"clampedPosition\":$clampedPosition,\"beforePosition\":$beforeSeekPosition,\"beforeBuffered\":$beforeBufferedPosition,\"wasPlaying\":$wasPlaying,\"isPlaying\":${exoPlayer.isPlaying},\"playWhenReady\":${exoPlayer.playWhenReady},\"playbackState\":${exoPlayer.playbackState},\"bufferedPosition\":${exoPlayer.bufferedPosition}},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
            } catch (e: Exception) {}
            // #endregion
        } else {
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"location\":\"MusifyBackgroundMusicPlayerV2.kt:268\",\"message\":\"seekTo skipped - duration is 0\",\"data\":{\"duration\":${exoPlayer.duration}},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
            } catch (e: Exception) {}
            // #endregion
        }
    }
    
    // Playback speed adjustment (from MediaPlayer-Extended)
    override fun setPlaybackSpeed(speed: Float) {
        // State validation: Only set speed if player is ready
        if (exoPlayer.playbackState == Player.STATE_IDLE) {
            Log.w("MusifyPlayer", "Cannot set playback speed: player is idle")
            return
        }
        
        // Validate speed range (0.5x - 2.0x, typical range from MediaPlayer-Extended)
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
        // Use Media3's simpler setPlaybackSpeed method
        exoPlayer.setPlaybackSpeed(clampedSpeed)
        Log.d("MusifyPlayer", "Playback speed set to ${clampedSpeed}x")
    }
    
    override fun getPlaybackSpeed(): Float {
        return exoPlayer.playbackParameters.speed
    }
    
    // Buffer level reporting (from MediaPlayer-Extended)
    override fun getBufferPercentage(): Int {
        return calculateBufferPercentage(exoPlayer)
    }
    
    // Audio session ID for visualizer
    override fun getAudioSessionId(): Int {
        val sessionId = exoPlayer.audioSessionId
        if (sessionId <= 0) {
            Log.w("MusifyPlayer", "Audio session ID is invalid: $sessionId. Visualizer will use fallback mode.")
        }
        return sessionId
    }

}