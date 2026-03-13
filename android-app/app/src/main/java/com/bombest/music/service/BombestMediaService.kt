package com.bombest.music.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.bombest.music.MainActivity
import com.bombest.music.R
import com.bombest.music.data.DownloadManager
import com.bombest.music.data.MetricsManager
import com.bombest.music.data.repository.MusicRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.future
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

class BombestMediaService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val repository by lazy { MusicRepository(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Cache for MediaItems - synchronized for thread-safe access from coroutine + binder thread
    private val libraryItems = java.util.Collections.synchronizedList(mutableListOf<MediaItem>())
    
    // Android Auto content style constants
    companion object {
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2
        const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
        
        // Custom command actions
        const val ACTION_TOGGLE_SHUFFLE = "com.bombest.music.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.bombest.music.TOGGLE_REPEAT"
        
        // Browse hierarchy IDs
        const val ROOT_ID = "root"
        const val SONGS_ID = "songs"
        const val PLAYLISTS_ID = "playlists"
        const val RECENT_ID = "recent"
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("BombestMediaService", "onCreate called")
        
        // Audio Attributes
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()
            
        // Load Control (Optimized Buffering for faster track transitions)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,  // Min Buffer (reduced from 40s for faster start)
                60_000,  // Max Buffer (reduced from 120s)
                1_500,   // Buffer for playback (reduced from 4s - faster start)
                3_000    // Buffer for rebuffer (reduced from 5s)
            )
            .setPrioritizeTimeOverSizeThresholds(true) // Prioritize time for smoother playback
            .build()
        
        // Initialize DownloadManager for caching
        val downloadManager = DownloadManager.getInstance(this)
        
        // Initialize MetricsManager
        MetricsManager.init(this)

        // Create media source factory with caching
        val mediaSourceFactory = DefaultMediaSourceFactory(downloadManager.cacheDataSourceFactory)
        
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
            
        // Track playback for metrics
        player.addListener(object : Player.Listener {
            private var lastMediaId: String? = null
            private var startTimeMs: Long = 0
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val now = System.currentTimeMillis()
                
                // Check if we should log the PREVIOUS track
                if (lastMediaId != null && startTimeMs > 0) {
                    val duration = now - startTimeMs
                    // Log if played for more than 30 seconds
                    if (duration > 30_000) {
                         try {
                             val trackId = lastMediaId!!.toInt()
                             MetricsManager.logPlay(trackId)
                         } catch (e: NumberFormatException) {
                             // Ignore non-integer IDs (like "root")
                         }
                    }
                }
                
                // Update for new track
                lastMediaId = mediaItem?.mediaId
                startTimeMs = now
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Optional: handle pause/stop logic tracking if needed using rigorous state
                // For MVP transition-based logging is usually sufficient for "plays"
            }
        })
        
        // Create a BitmapLoader for loading artwork
        val bitmapLoader = androidx.media3.session.CacheBitmapLoader(
            androidx.media3.datasource.DataSourceBitmapLoader(this)
        )
        
        // Custom commands for shuffle/repeat
        val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
        val repeatCommand = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
        
        // Build the session
        mediaSession = MediaLibrarySession.Builder(
            this,
            player,
            object : MediaLibrarySession.Callback {
                
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    android.util.Log.i("BombestMediaService", "onConnect: ${controller.packageName} connected")
                    // Add custom commands for Android Auto
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(shuffleCommand)
                        .add(repeatCommand)
                        .add(SessionCommand("GET_AUDIO_SESSION_ID", Bundle.EMPTY))
                        .add(SessionCommand("REFRESH_LIBRARY", Bundle.EMPTY))
                        .build()
                    
                    // Return default connection result - uses standard transport controls 
                    // (prev, play/pause, next) which Android Auto displays properly
                    // Custom commands (shuffle, repeat) are available via voice or menu
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }
                
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        "GET_AUDIO_SESSION_ID" -> {
                            val resultBundle = Bundle()
                            resultBundle.putInt("AUDIO_SESSION_ID", player.audioSessionId)
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
                        }
                        "REFRESH_LIBRARY" -> {
                            android.util.Log.d("BombestMediaService", "Refreshing library on request")
                            fetchLibrary(player)
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        ACTION_TOGGLE_SHUFFLE -> {
                            player.shuffleModeEnabled = !player.shuffleModeEnabled
                            android.util.Log.d("BombestMediaService", "Shuffle toggled: ${player.shuffleModeEnabled}")
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        ACTION_TOGGLE_REPEAT -> {
                            player.repeatMode = when (player.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            android.util.Log.d("BombestMediaService", "Repeat mode: ${player.repeatMode}")
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
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
                    android.util.Log.i("BombestMediaService", "onGetLibraryRoot: ${browser.packageName} requested root")
                    // Create root extras with content style hints
                    val rootExtras = Bundle().apply {
                        putBoolean(CONTENT_STYLE_SUPPORTED, true)
                        putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
                        putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
                    }
                    
                    val rootItem = MediaItem.Builder()
                        .setMediaId(ROOT_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("bombest beats")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setExtras(rootExtras)
                                .build()
                        )
                        .build()
                    
                    return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
                }
                
                override fun onGetChildren(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    
                    return when (parentId) {
                        ROOT_ID -> {
                            // Return browsing categories with icons
                            val categories = listOf(
                                createBrowsableItem(
                                    SONGS_ID, 
                                    "🎵 All Songs", 
                                    "Your complete music library",
                                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE
                                ),
                                createBrowsableItem(
                                    PLAYLISTS_ID, 
                                    "📋 Playlists", 
                                    "Your curated collections",
                                    CONTENT_STYLE_GRID_ITEM_HINT_VALUE
                                ),
                                createBrowsableItem(
                                    RECENT_ID, 
                                    "⏱️ Recently Played", 
                                    "Your listening history",
                                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE
                                )
                            )
                            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(categories), params))
                        }
                        SONGS_ID -> {
                            // Return all songs
                            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(libraryItems), params))
                        }
                        PLAYLISTS_ID -> {
                            // Return playlists - for now empty until we integrate
                            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                        }
                        RECENT_ID -> {
                            // Return most recent 10 songs
                            val recent = libraryItems.takeLast(10).reversed()
                            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(recent), params))
                        }
                        else -> {
                            // Fallback - return all songs
                            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(libraryItems), params))
                        }
                    }
                }
                
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>
                ): ListenableFuture<List<MediaItem>> {
                    val updatedMediaItems = mediaItems.map { mediaItem ->
                        libraryItems.find { it.mediaId == mediaItem.mediaId } ?: mediaItem
                    }
                    return Futures.immediateFuture(updatedMediaItems)
                }
            }
        )
        .setSessionActivity(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setBitmapLoader(bitmapLoader)
        .build()

        fetchLibrary(player)
    }
    
    private fun createBrowsableItem(
        id: String,
        title: String,
        subtitle: String,
        contentStyleHint: Int
    ): MediaItem {
        val extras = Bundle().apply {
            putInt(CONTENT_STYLE_PLAYABLE_HINT, contentStyleHint)
        }
        
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
    
    private fun fetchLibrary(player: Player) {
        android.util.Log.d("BombestMediaService", "fetchLibrary() called, launching coroutine...")
        serviceScope.launch {
            android.util.Log.d("BombestMediaService", "Coroutine started, calling repository.fetchLibrary()...")
            try {
                android.util.Log.d("BombestMediaService", "Fetching library...")
                val tracks = repository.fetchLibrary()
                android.util.Log.d("BombestMediaService", "Fetched ${tracks.size} tracks")
                
                libraryItems.clear()
                
                val mediaItems = tracks.map { track ->
                    val artUrl = repository.getTrackArtUrl(track.id)
                    val artUri = Uri.parse(artUrl)
                    
                    MediaItem.Builder()
                        .setMediaId(track.id.toString())
                        .setUri(repository.getStreamUrl(track.id))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.displayTitle)
                                .setArtist(track.displayArtist)
                                .setAlbumTitle(track.album)
                                .setArtworkUri(artUri)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                }
                libraryItems.addAll(mediaItems)
                android.util.Log.d("BombestMediaService", "Added ${libraryItems.size} items to library cache")
                
                // Only set media items when player is idle - don't clobber active playback during refresh
                if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) {
                    player.setMediaItems(mediaItems)
                    player.prepare()
                }
                
                // Notify session that children changed
                mediaSession?.notifyChildrenChanged(ROOT_ID, 3, null)
                mediaSession?.notifyChildrenChanged(SONGS_ID, mediaItems.size, null)
            } catch (e: Exception) {
                android.util.Log.e("BombestMediaService", "Error fetching library", e)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.run {
            stop()
            clearMediaItems()
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.stop()
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
