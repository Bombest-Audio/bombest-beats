package com.bombest.music.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

class MusicPlayer(context: Context) {
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    val mediaSession: MediaSession = MediaSession.Builder(context, exoPlayer).build()

    fun play(url: String) {
        val item = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun release() {
        mediaSession.release()
        exoPlayer.release()
    }
}


