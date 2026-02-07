package com.bombest.musify.di

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.util.UnstableApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@UnstableApi
@Module
@InstallIn(SingletonComponent::class)
object ExoPlayerModule {
    @Provides
    @Singleton
    fun provideExoplayer(
        @ApplicationContext context: Context
    ): ExoPlayer {
        // Configure ExoPlayer with features inspired by MediaPlayer-Extended:
        // - Frame-exact seeking (default in ExoPlayer, but ensure proper configuration)
        // - Better buffering control
        // - Playback speed support (enabled by default in ExoPlayer)
        // - Audio focus handling to pause other apps when playback starts
        
        // Configure AudioAttributes for proper audio focus handling
        // This ensures that when Bombest Beats starts playing, it requests audio focus
        // and other apps (like YouTube) will pause automatically
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,  // Min buffer (reduced for faster start)
                60_000,  // Max buffer
                1_500,   // Buffer for playback (faster start)
                3_000    // Buffer for rebuffer
            )
            .setBackBuffer(30_000, true)  // Keep 30 seconds of back buffer for faster backward seeks during scrubbing
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        
        // Use CLOSEST_SYNC seek parameters for faster seeks during scrubbing
        // This trades exact frame positioning for faster response time
        return ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)  // true = handle audio focus automatically
            .setHandleAudioBecomingNoisy(true)  // Pause when headphones disconnect
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)  // Faster seeks for turntable scrubbing
            // Frame-exact seeking is enabled by default in ExoPlayer
            // Playback speed is supported by default
            .build()
    }
}