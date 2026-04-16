package com.bombest.music.cast

import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

/**
 * Maps Media3 [MediaItem] ↔ Cast [MediaQueueItem], explicitly setting artwork in
 * the Cast [com.google.android.gms.cast.MediaMetadata.images] list so the Default
 * Media Receiver displays album art on the TV screen.
 *
 * Why not [androidx.media3.cast.DefaultMediaItemConverter]?
 * The default converter stores the full MediaItem as JSON in [MediaQueueItem.customData]
 * for round-trip reconstruction, but only writes track title/artist to the Cast
 * [MediaInfo.metadata] object — it does NOT call [addImage], so the DMR's artwork
 * `<img>` element is never populated.
 *
 * This converter explicitly calls [addImage] with the [MediaMetadata.artworkUri] so
 * the DMR receives the image URL in the standard Cast metadata channel.
 */
class BombestCastMediaItemConverter : MediaItemConverter {

    companion object {
        private const val KEY_MEDIA_ID = "bombestMediaId"
        private const val KEY_MIME = "bombestMime"
        private const val KEY_ARTWORK = "bombestArtwork"
    }

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val localConfig = checkNotNull(mediaItem.localConfiguration) {
            "MediaItem must have localConfiguration to be sent to Cast"
        }
        val m = mediaItem.mediaMetadata

        val castMeta = com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
        )
        m.title?.let {
            castMeta.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, it.toString())
        }
        m.artist?.let {
            castMeta.putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, it.toString())
        }
        m.albumTitle?.let {
            castMeta.putString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE, it.toString())
        }
        // This is the critical call DefaultMediaItemConverter omits — without it the
        // DMR never populates its artwork <img> element.
        m.artworkUri?.let { castMeta.addImage(WebImage(it)) }

        // Store mediaId + mime in item customData so toMediaItem() can reconstruct
        // the full Media3 MediaItem when the session is recovered or the queue wraps.
        val customData = JSONObject().apply {
            put(KEY_MEDIA_ID, mediaItem.mediaId)
            put(KEY_MIME, localConfig.mimeType ?: MimeTypes.AUDIO_MPEG)
            m.artworkUri?.let { put(KEY_ARTWORK, it.toString()) }
        }

        val mediaInfo = MediaInfo.Builder(localConfig.uri.toString())
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(localConfig.mimeType ?: MimeTypes.AUDIO_MPEG)
            .setMetadata(castMeta)
            .setCustomData(customData)
            .build()

        return MediaQueueItem.Builder(mediaInfo).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val media = mediaQueueItem.media ?: return MediaItem.EMPTY
        val custom = media.customData

        val mediaId = custom?.optString(KEY_MEDIA_ID, "") ?: ""
        val mimeType = custom?.optString(KEY_MIME, MimeTypes.AUDIO_MPEG) ?: MimeTypes.AUDIO_MPEG
        val artworkUriStr = custom?.optString(KEY_ARTWORK, null)

        val castMeta = media.metadata
        val artworkUri = artworkUriStr?.let { android.net.Uri.parse(it) }
            ?: castMeta?.images?.firstOrNull()?.url

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(media.contentUrl ?: media.contentId ?: "")
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(castMeta?.getString(
                        com.google.android.gms.cast.MediaMetadata.KEY_TITLE))
                    .setArtist(castMeta?.getString(
                        com.google.android.gms.cast.MediaMetadata.KEY_ARTIST))
                    .setAlbumTitle(castMeta?.getString(
                        com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE))
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()
    }
}
