package com.bombest.music.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.common.Player
import com.bombest.music.data.repository.MusicRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.future

class BombestMediaService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val repository = MusicRepository()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Cache map for MediaItems to serve to Android Auto easily
    private val libraryItems = mutableListOf<MediaItem>()

    override fun onCreate() {
        super.onCreate()
        
        // Audio Attributes
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()
            
        // Load Control (Buffering)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                40_000, // Min Buffer (40s) - Reduced slightly to allow earlier play readiness
                120_000, // Max Buffer (120s) - Keep high for stability
                4_000, // Buffer for playback (4s) - Increased to ensure solid start
                5_000  // Buffer for rebuffer (5s) - Increased to prevent choppy stutter loop
            )
            .build()
        
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build()
        
        // Create a BitmapLoader for loading artwork from URIs
        val bitmapLoader = androidx.media3.session.CacheBitmapLoader(
            androidx.media3.datasource.DataSourceBitmapLoader(this)
        )
        
        // Build the session
        mediaSession = MediaLibrarySession.Builder(
            this,
            player,
            object : MediaLibrarySession.Callback {
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: androidx.media3.session.SessionCommand,
                    args: android.os.Bundle
                ): ListenableFuture<androidx.media3.session.SessionResult> {
                    if (customCommand.customAction == "GET_AUDIO_SESSION_ID") {
                        val resultBundle = android.os.Bundle()
                        resultBundle.putInt("AUDIO_SESSION_ID", player.audioSessionId)
                        return Futures.immediateFuture(
                            androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS, resultBundle)
                        )
                    }
                    if (customCommand.customAction == "REFRESH_LIBRARY") {
                        android.util.Log.d("BombestMediaService", "Refreshing library on request")
                        fetchLibrary(player)
                        return Futures.immediateFuture(
                            androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                        )
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }

                override fun onSubscribe(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<Void>> {
                    android.util.Log.d("BombestMediaService", "onSubscribe: $parentId")
                    return Futures.immediateFuture(LibraryResult.ofVoid(params))
                }

                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    return Futures.immediateFuture(LibraryResult.ofItem(
                        MediaItem.Builder()
                           .setMediaId("root")
                           .setMediaMetadata(
                               MediaMetadata.Builder()
                                   .setTitle("All Songs")
                                   .setIsBrowsable(true)
                                   .setIsPlayable(false)
                                   .build()
                           )
                           .build(), 
                        params
                    ))
                }
                
                override fun onGetChildren(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    // For now, regardless of parentId (root), return all songs
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(libraryItems), params))
                }
                
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>
                ): ListenableFuture<List<MediaItem>> {
                    val updatedMediaItems = mediaItems.map { mediaItem ->
                        // If the item requested is just an ID, find the full item with URI
                        libraryItems.find { it.mediaId == mediaItem.mediaId } ?: mediaItem
                    }
                    return Futures.immediateFuture(updatedMediaItems)
                }
            }
        )
        .setBitmapLoader(bitmapLoader)
        .build()

        fetchLibrary(player)
    }
    
    private fun fetchLibrary(player: Player) {
        serviceScope.launch {
            try {
                android.util.Log.d("BombestMediaService", "Fetching library...")
                val tracks = repository.fetchLibrary()
                android.util.Log.d("BombestMediaService", "Fetched ${tracks.size} tracks")
                
                libraryItems.clear()
                val mediaItems = tracks.map { track ->
                    // Use track-level art endpoint since most tracks don't have album_id
                    val artUrl = repository.getTrackArtUrl(track.id)
                    val artUri = Uri.parse(artUrl)
                    android.util.Log.d("BombestMediaService", "Track: ${track.displayTitle}, artUrl: $artUrl")
                    
                    MediaItem.Builder()
                        .setMediaId(track.id.toString())
                        .setUri(repository.getStreamUrl(track.id))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.displayTitle)
                                .setArtist(track.displayArtist)
                                .setAlbumTitle(track.album)
                                .setArtworkUri(artUri)
                                .setIsBrowsable(false) // Leaf node
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                }
                libraryItems.addAll(mediaItems)
                android.util.Log.d("BombestMediaService", "Added ${libraryItems.size} items to library cache")
                
                // Pre-populate player playlist
                player.setMediaItems(mediaItems)
                player.prepare()
                
                // Notify session that children changed (if connected browsers exist)
                mediaSession?.notifyChildrenChanged("root", mediaItems.size, null)
            } catch (e: Exception) {
                android.util.Log.e("BombestMediaService", "Error fetching library", e)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
