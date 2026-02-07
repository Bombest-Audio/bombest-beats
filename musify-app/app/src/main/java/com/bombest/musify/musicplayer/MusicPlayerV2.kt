package com.bombest.musify.musicplayer

import android.graphics.Bitmap
import com.bombest.musify.domain.Streamable
import kotlinx.coroutines.flow.Flow

interface MusicPlayerV2 {
    sealed class PlaybackState(open val currentlyPlayingStreamable: Streamable? = null) {
        data class Loading(
            val previouslyPlayingStreamable: Streamable?,
            val bufferPercentage: Int = 0 // 0-100, from MediaPlayer-Extended
        ) : PlaybackState()
        data class Playing(
            override val currentlyPlayingStreamable: Streamable,
            val totalDuration: Long,
            val currentPlaybackPositionInMillisFlow: Flow<Long>,
            val bufferPercentage: Int = 100, // 0-100, from MediaPlayer-Extended
            val playbackSpeed: Float = 1.0f // Current playback speed, from MediaPlayer-Extended
        ) : PlaybackState()

        data class Paused(
            override val currentlyPlayingStreamable: Streamable,
            val playbackSpeed: Float = 1.0f // Preserve speed when paused, from MediaPlayer-Extended
        ) : PlaybackState()
        data class Ended(val streamable: Streamable) : PlaybackState()
        object Error : PlaybackState()
        object Idle : PlaybackState()
    }

    val currentPlaybackStateStream: Flow<PlaybackState>
    fun playStreamable(streamable: Streamable, associatedAlbumArt: Bitmap)
    fun pauseCurrentlyPlayingTrack()
    fun stopPlayingTrack()
    fun tryResume(): Boolean
    fun seekTo(positionMillis: Long)
    
    // Playback speed adjustment (from MediaPlayer-Extended)
    fun setPlaybackSpeed(speed: Float) // 0.5f - 2.0f, typical range
    fun getPlaybackSpeed(): Float
    
    // Buffer level reporting (from MediaPlayer-Extended)
    fun getBufferPercentage(): Int // 0-100
    
    // Audio session ID for visualizer
    fun getAudioSessionId(): Int // Returns audio session ID from ExoPlayer
}