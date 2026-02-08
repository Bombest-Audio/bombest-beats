package com.bombest.music.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        mediaSession?.player?.run {
            pause()
            clearMediaItems()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_URL)?.let { playUrl(it) }
        return START_STICKY
    }

    fun playUrl(url: String) {
        val session = mediaSession ?: return
        session.player.setMediaItem(MediaItem.fromUri(url))
        session.player.prepare()
        session.player.playWhenReady = true
    }
}


