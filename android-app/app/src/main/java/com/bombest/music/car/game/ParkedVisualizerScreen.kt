package com.bombest.music.car.game

import android.content.ComponentName
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bombest.music.data.NetworkModule
import com.bombest.music.service.BombestMediaService
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors

/**
 * Parked-only mini-game (tap score) plus large album art from the active [BombestMediaService] session.
 * Art URI may be animated (GIF/WebP) if the head unit renders it; otherwise static bitmap loads.
 */
class ParkedVisualizerScreen(carContext: CarContext) : Screen(carContext) {

    private var mediaController: MediaController? = null
    private var connectionFailed: Boolean = false
    private var tapScore: Int = 0

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            invalidate()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            invalidate()
        }
    }

    init {
        connectMedia()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                mediaController?.removeListener(playerListener)
                mediaController?.release()
                mediaController = null
            }
        })
    }

    private fun connectMedia() {
        val token = SessionToken(
            carContext,
            ComponentName(carContext, BombestMediaService::class.java)
        )
        val future = MediaController.Builder(carContext, token).buildAsync()
        Futures.addCallback(
            future,
            object : FutureCallback<MediaController> {
                override fun onSuccess(result: MediaController?) {
                    if (result == null) {
                        connectionFailed = true
                        invalidate()
                        return
                    }
                    mediaController = result
                    result.addListener(playerListener)
                    invalidate()
                }

                override fun onFailure(t: Throwable) {
                    connectionFailed = true
                    invalidate()
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun resolveArtUri(): String? {
        val c = mediaController ?: return null
        val md = c.mediaMetadata
        val artwork = md.artworkUri
        if (artwork != null) return artwork.toString()
        val id = c.currentMediaItem?.mediaId?.toIntOrNull() ?: return null
        return "${NetworkModule.getStreamBaseUrl()}/track/$id/art"
    }

    private fun resolveTitle(): String {
        val c = mediaController ?: return if (connectionFailed) "Not connected" else "Loading…"
        val t = c.mediaMetadata.title?.toString()?.trim()
        if (!t.isNullOrEmpty()) return t
        return c.currentMediaItem?.mediaMetadata?.title?.toString()?.trim() ?: "Unknown track"
    }

    private fun resolveArtist(): String {
        val c = mediaController ?: return ""
        val a = c.mediaMetadata.artist?.toString()?.trim()
        if (!a.isNullOrEmpty()) return a
        return c.currentMediaItem?.mediaMetadata?.artist?.toString()?.trim() ?: ""
    }

    private fun buildArtIcon(): CarIcon? {
        val uriString = resolveArtUri() ?: return null
        return try {
            CarIcon.Builder(IconCompat.createWithContentUri(Uri.parse(uriString))).build()
        } catch (_: Exception) {
            null
        }
    }

    override fun onGetTemplate(): PaneTemplate {
        val paneBuilder = Pane.Builder().setLoading(mediaController == null && !connectionFailed)

        val artIcon = buildArtIcon()
        if (artIcon != null) {
            paneBuilder.setImage(artIcon)
        }

        val subtitle = buildString {
            val artist = resolveArtist()
            if (artist.isNotEmpty()) {
                append(artist)
                append(" · ")
            }
            append("Tap score: $tapScore")
        }

        paneBuilder.addRow(
            Row.Builder()
                .setTitle(resolveTitle())
                .addText(subtitle)
                .build()
        )

        if (connectionFailed) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Playback")
                    .addText("Open Bombest Beats on the phone and start music, then return here.")
                    .build()
            )
        }

        val tapListener = ParkedOnlyOnClickListener.create(
            OnClickListener {
                tapScore++
                invalidate()
            }
        )

        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Beat tap")
                .setOnClickListener(tapListener)
                .build()
        )

        val pane = paneBuilder.build()

        return PaneTemplate.Builder(pane)
            .setTitle("Bombest Beats · Parked")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
