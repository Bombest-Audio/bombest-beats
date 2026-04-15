package com.bombest.music.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import com.bombest.music.data.AutoRecentTracksStore
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.bombest.music.MainActivity
import com.bombest.music.R
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.DownloadManager
import com.bombest.music.data.MetricsManager
import com.bombest.music.data.PerformanceMetricsManager
import com.bombest.music.data.api.PerfEventPayload
import com.bombest.music.data.NetworkModule
import com.bombest.music.data.authDataStore
import com.bombest.music.data.api.Playlist
import com.bombest.music.data.api.PlaylistApi
import com.bombest.music.data.api.Track as ApiTrack
import com.bombest.music.data.repository.MusicRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.atomic.AtomicBoolean

class BombestMediaService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var playlistApi: PlaylistApi? = null
    /** Favorites / "Liked songs" system playlist — used for AA root shortcut. */
    private var favoritesPlaylist: Playlist? = null
    private val recentTracksStore by lazy { AutoRecentTracksStore(this) }
    private val repository by lazy { MusicRepository(this) }
    private lateinit var downloadManager: DownloadManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Kept as a field so NetworkCallback and error recovery can reference it
    private var player: ExoPlayer? = null

    // Listener references stored so onDestroy can remove them before player.release().
    // Belt-and-braces against leaks when the service is recreated rapidly (e.g., Auto reconnects).
    private var audioOffloadListener: ExoPlayer.AudioOffloadListener? = null
    private var playerListener: Player.Listener? = null
    private var analyticsListener: androidx.media3.exoplayer.analytics.AnalyticsListener? = null
    // onDestroy can race with the service being torn down twice (rapid Auto reconnects
    // observed in prod logs). The AtomicBoolean guard makes listener removal idempotent.
    private val listenersRemoved = AtomicBoolean(false)

    // Track current audio format + connection type as dimensions for CloudWatch metrics.
    // Updated on each media item transition and network change.
    @Volatile private var currentFormat: String = "mp3"
    @Volatile private var currentConnectionType: String = "wifi"
    private lateinit var connectivityManager: ConnectivityManager

    // Volatile snapshot — lock-free reads from any thread (binder, main, IO).
    // Written once per library refresh as a single atomic reference swap; no lock
    // is ever held while iterating, which eliminates synchronization contention
    // with Android Auto's binder thread (the primary cause of audio skips).
    @Volatile private var librarySnapshot: List<MediaItem> = emptyList()

    // Adjust buffer target based on transport type: cellular needs a larger runway
    // because bandwidth is less predictable; WiFi can afford a smaller max buffer.
    private fun buildLoadControl(isWifi: Boolean) = androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            if (isWifi) 20_000 else 30_000,   // minBuffer
            if (isWifi) 60_000 else 120_000,   // maxBuffer
            5_000,                              // bufferForPlayback: 5s to avoid immediate underrun on transcode streams
            8_000                               // bufferForPlaybackAfterRebuffer: extra cushion for transcode latency
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Callbacks fire on ConnectivityThread — all ExoPlayer access must be on main.
            serviceScope.launch {
                val caps = connectivityManager.getNetworkCapabilities(network)
                currentConnectionType = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "wifi" else "cellular"
                val p = player ?: return@launch
                if (p.playbackState == Player.STATE_BUFFERING) {
                    android.util.Log.i("BombestMediaService", "Network available ($currentConnectionType) — player is buffering, preparing")
                    p.prepare()
                }
                // Re-fetch library if snapshot is empty (backend was unreachable earlier)
                if (librarySnapshot.isEmpty()) {
                    android.util.Log.i("BombestMediaService", "Network available — library empty, re-fetching")
                    fetchLibrary(p)
                }
            }
        }
        override fun onLost(network: Network) {
            serviceScope.launch {
                android.util.Log.i("BombestMediaService", "Network lost — player has ${player?.bufferedPosition?.let { it / 1000 }}s buffered")
            }
        }
    }

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
        /** Playable row: expands to full shuffled library in onSetMediaItems / onAddMediaItems */
        const val SPECIAL_SHUFFLE_ALL = "special:shuffle_all"
        private const val BROWSE_PAGE_SIZE = 80
        /** Package name Android Auto's Gearhead host (projection mode) advertises on connect. */
        const val AUTO_PACKAGE = "com.google.android.projection.gearhead"
    }

    private fun formatFromMimeType(mimeType: String?): String = when {
        mimeType == null -> "mp3"
        mimeType.contains("flac", ignoreCase = true) -> "flac"
        mimeType.contains("wav", ignoreCase = true) -> "wav"
        mimeType.contains("aac", ignoreCase = true) || mimeType.contains("m4a", ignoreCase = true) -> "aac"
        mimeType.contains("ogg", ignoreCase = true) -> "ogg"
        else -> "mp3"
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("BombestMediaService", "onCreate called")

        // Audio Attributes
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        // Load control: size based on transport type detected at service start.
        // NetworkCallback adjusts behaviour on subsequent transitions.
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val startingOnWifi = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork
        )?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        currentConnectionType = if (startingOnWifi) "wifi" else "cellular"
        val loadControl = buildLoadControl(isWifi = startingOnWifi)
        android.util.Log.i("BombestMediaService", "LoadControl built for $currentConnectionType")

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Initialize DownloadManager for caching + offline-first playback URIs
        downloadManager = DownloadManager.getInstance(this)

        // Read auth token asynchronously — don't block onCreate() on DataStore I/O.
        // playlistApi starts with a null token (streams are public); token is patched
        // in once DataStore resolves, before the first user browse request completes.
        playlistApi = NetworkModule.createPlaylistApi(null)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val token = authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
                downloadManager.setAuthToken(token)
                // Replace playlistApi with an authenticated instance
                withContext(Dispatchers.Main) {
                    playlistApi = NetworkModule.createPlaylistApi(token)
                }
            } catch (_: Exception) {
                // Streams are public; token only adds optional auth headers
            }
        }

        // Initialize metrics managers
        MetricsManager.init(this)
        PerformanceMetricsManager.init(this)

        // Create media source factory with caching
        val mediaSourceFactory = DefaultMediaSourceFactory(downloadManager.cacheDataSourceFactory)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
        this.player = player

        // Hardware audio offload: prefer delegating decoding to the audio DSP coprocessor
        // so the main CPU can sleep between buffer refills (Android 10+, MP3/AAC).
        // Eliminates GC/scheduler wakeups that cause mid-track skipping.
        val audioOffloadPrefs = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .setIsSpeedChangeSupportRequired(false)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(audioOffloadPrefs)
            .build()

        // Log when the player is sleeping for offload (confirms hardware DSP is active)
        val offloadListener = object : ExoPlayer.AudioOffloadListener {
            override fun onSleepingForOffloadChanged(sleepingForOffload: Boolean) {
                android.util.Log.i("BombestMediaService", "Audio offload sleeping: $sleepingForOffload")
                PerformanceMetricsManager.record(
                    com.bombest.music.data.api.PerfEventPayload(
                        event_type = "OffloadState",
                        timestamp = PerformanceMetricsManager.isoNow(),
                        track_id = player.currentMediaItem?.mediaId,
                        format = currentFormat,
                        sleeping = sleepingForOffload
                    )
                )
            }
        }
        audioOffloadListener = offloadListener
        player.addAudioOffloadListener(offloadListener)

        // Track playback for metrics
        val pListener = object : Player.Listener {
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
                currentFormat = formatFromMimeType(mediaItem?.localConfiguration?.mimeType)
                mediaItem?.mediaId?.toIntOrNull()?.let { id ->
                    recentTracksStore.recordPlayed(id)
                }
                // Flush perf events on each track change so data arrives promptly
                PerformanceMetricsManager.flush()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Optional: handle pause/stop logic tracking if needed using rigorous state
                // For MVP transition-based logging is usually sufficient for "plays"
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("BombestMediaService", "Playback error [${error.errorCodeName}]", error)
                val pos = player.currentPosition
                player.prepare()
                // Only seek if the stream supports seeking — fragmented MP4 transcode streams
                // report isCurrentMediaItemSeekable=false until the moov atom is parsed.
                // Seeking into a non-seekable stream restarts from byte 0 and can loop.
                if (pos > 2_000 && player.isCurrentMediaItemSeekable) {
                    player.seekTo(pos)
                }
            }
        }
        playerListener = pListener
        player.addListener(pListener)

        // Diagnostic instrumentation — logs to logcat AND records to CloudWatch via
        // PerformanceMetricsManager. Events are batched locally and uploaded every 60s so
        // you can review the dashboard after a drive without needing adb.
        val aListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                val kbps = bitrateEstimate / 1000
                android.util.Log.i("BombestMediaService",
                    "Bandwidth: $kbps kbps, loaded: ${totalBytesLoaded / 1024} KB in ${totalLoadTimeMs}ms")
                PerformanceMetricsManager.record(
                    com.bombest.music.data.api.PerfEventPayload(
                        event_type = "BandwidthSample",
                        timestamp = PerformanceMetricsManager.isoNow(),
                        format = currentFormat,
                        connection = currentConnectionType,
                        bitrate_kbps = kbps,
                        bytes_loaded = totalBytesLoaded,
                        load_time_ms = totalLoadTimeMs
                    )
                )
            }

            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean
            ) {
                android.util.Log.e("BombestMediaService",
                    "Load error: ${error.javaClass.simpleName} — ${error.message}, wasCanceled=$wasCanceled, uri=${loadEventInfo.uri}")
                PerformanceMetricsManager.record(
                    com.bombest.music.data.api.PerfEventPayload(
                        event_type = "LoadError",
                        timestamp = PerformanceMetricsManager.isoNow(),
                        track_id = player.currentMediaItem?.mediaId,
                        format = currentFormat,
                        connection = currentConnectionType,
                        error_type = error.javaClass.simpleName,
                        error_message = error.message?.take(200)
                    )
                )
            }

            override fun onAudioUnderrun(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                android.util.Log.e("BombestMediaService",
                    "AUDIO UNDERRUN — buffer: ${bufferSizeMs}ms, elapsed since last feed: ${elapsedSinceLastFeedMs}ms")
                PerformanceMetricsManager.record(
                    com.bombest.music.data.api.PerfEventPayload(
                        event_type = "AudioUnderrun",
                        timestamp = PerformanceMetricsManager.isoNow(),
                        track_id = player.currentMediaItem?.mediaId,
                        format = currentFormat,
                        connection = currentConnectionType,
                        buffer_ms = bufferSizeMs,
                        elapsed_since_feed_ms = elapsedSinceLastFeedMs
                    )
                )
            }

            override fun onIsLoadingChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                isLoading: Boolean
            ) {
                android.util.Log.d("BombestMediaService",
                    "Loading: $isLoading, bufferedMs=${player.bufferedPosition - player.currentPosition}")
            }
        }
        analyticsListener = aListener
        player.addAnalyticsListener(aListener)

        // Flush performance metrics every 60s during playback so data arrives after a drive
        // even when the app is backgrounded and adb is not connected.
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                PerformanceMetricsManager.flush()
            }
        }

        // Same OkHttp + User-Agent as streaming so session/Android Auto can load artwork behind CDNs
        val bitmapLoader = downloadManager.createSessionBitmapLoader()

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
                            val rows = mutableListOf<MediaItem>()
                            favoritesPlaylist?.let { rows.add(createPlaylistBrowseItem(it)) }
                            rows.add(createShuffleAllItem())
                            rows.add(
                                createBrowsableItem(
                                    RECENT_ID,
                                    "Recently played",
                                    "On this device",
                                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE
                                )
                            )
                            rows.add(
                                createBrowsableItem(
                                    SONGS_ID,
                                    "Songs",
                                    "All tracks in your library",
                                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE
                                )
                            )
                            rows.add(
                                createBrowsableItem(
                                    PLAYLISTS_ID,
                                    "Playlists",
                                    "Your playlists",
                                    CONTENT_STYLE_GRID_ITEM_HINT_VALUE
                                )
                            )
                            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(rows), params))
                        }
                        SONGS_ID -> {
                            val snap = librarySnapshot
                            val ps = pageSize.coerceIn(1, BROWSE_PAGE_SIZE)
                            val slice = slicePage(snap, page, ps)
                            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(slice), params))
                        }
                        PLAYLISTS_ID -> {
                            val api = playlistApi
                            if (api == null) {
                                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                            } else {
                                serviceScope.future(Dispatchers.IO) {
                                    try {
                                        val response = api.getPlaylists()
                                        val items = response.playlists.map { createPlaylistBrowseItem(it) }
                                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                                    } catch (e: Exception) {
                                        android.util.Log.e("BombestMediaService", "browse playlists", e)
                                        LibraryResult.ofItemList(ImmutableList.of(), params)
                                    }
                                }
                            }
                        }
                        RECENT_ID -> {
                            val snap = librarySnapshot
                            val ids = recentTracksStore.getRecentIds()
                            val byId = snap.associateBy { it.mediaId }
                            val recent = ids.mapNotNull { id -> byId[id.toString()] }
                            val ps = pageSize.coerceIn(1, BROWSE_PAGE_SIZE)
                            val slice = slicePage(recent, page, ps)
                            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(slice), params))
                        }
                        else -> {
                            if (parentId.startsWith("playlist:")) {
                                val pid = parentId.removePrefix("playlist:").toIntOrNull()
                                val api = playlistApi
                                if (pid == null || api == null) {
                                    Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                                } else {
                                    val transcodeForAuto = isAutoController(browser)
                                    serviceScope.future(Dispatchers.IO) {
                                        try {
                                            val tracks = api.getPlaylistTracks(pid).tracks
                                            val items = tracks.map { buildMediaItemFromApiTrack(it, transcodeForAuto) }
                                            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                                        } catch (e: Exception) {
                                            android.util.Log.e("BombestMediaService", "browse playlist $pid", e)
                                            LibraryResult.ofItemList(ImmutableList.of(), params)
                                        }
                                    }
                                }
                            } else {
                                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                            }
                        }
                    }
                }

                override fun onGetItem(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    mediaId: String
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    if (mediaId == SPECIAL_SHUFFLE_ALL) {
                        return Futures.immediateFuture(LibraryResult.ofItem(createShuffleAllItem(), null))
                    }
                    if (mediaId.startsWith("playlist:")) {
                        val pid = mediaId.removePrefix("playlist:").toIntOrNull()
                            ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE, null))
                        favoritesPlaylist?.takeIf { it.id == pid }?.let { pl ->
                            return Futures.immediateFuture(LibraryResult.ofItem(createPlaylistBrowseItem(pl), null))
                        }
                        val api = playlistApi
                            ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_INVALID_STATE, null))
                        return serviceScope.future(Dispatchers.IO) {
                            try {
                                val pl = api.getPlaylists().playlists.find { it.id == pid }
                                if (pl != null) LibraryResult.ofItem(createPlaylistBrowseItem(pl), null)
                                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE, null)
                            } catch (e: Exception) {
                                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO, null)
                            }
                        }
                    }
                    val item = librarySnapshot.find { it.mediaId == mediaId }
                    return if (item != null) {
                        Futures.immediateFuture(LibraryResult.ofItem(item, null))
                    } else {
                        Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE, null))
                    }
                }

                override fun onSearch(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    query: String,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<Void>> {
                    return Futures.immediateFuture(LibraryResult.ofVoid(params))
                }

                override fun onGetSearchResult(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    query: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val q = query.trim().lowercase()
                    if (q.isEmpty()) {
                        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                    }
                    val snap = librarySnapshot
                    val filtered = snap.filter { mi ->
                        val t = mi.mediaMetadata.title?.toString()?.lowercase() ?: ""
                        val a = mi.mediaMetadata.artist?.toString()?.lowercase() ?: ""
                        val al = mi.mediaMetadata.albumTitle?.toString()?.lowercase() ?: ""
                        t.contains(q) || a.contains(q) || al.contains(q)
                    }
                    val ps = pageSize.coerceIn(1, BROWSE_PAGE_SIZE)
                    val slice = slicePage(filtered, page, ps)
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(slice), params))
                }

                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    if (mediaItems.any { it.mediaId == SPECIAL_SHUFFLE_ALL }) {
                        val snap = librarySnapshot
                        if (snap.isNotEmpty()) {
                            // Pre-shuffle with OS-entropy SecureRandom so every session
                            // starts at a genuinely different track (not just a different
                            // position in ExoPlayer's LCG-based internal shuffle order).
                            val shuffled = snap.toMutableList().also {
                                java.util.Collections.shuffle(it, java.security.SecureRandom())
                            }
                            player.shuffleModeEnabled = true
                            return Futures.immediateFuture(
                                MediaSession.MediaItemsWithStartPosition(
                                    ImmutableList.copyOf(shuffled), 0, 0L
                                )
                            )
                        }
                        // Library not ready yet — trigger re-fetch and return empty so AA
                        // doesn't show "Shuffle library" as the current track.
                        android.util.Log.w("BombestMediaService", "Shuffle but library empty — re-fetching")
                        serviceScope.launch { fetchLibrary(player) }
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0L)
                        )
                    }
                    val expanded = expandPlaybackItems(mediaItems)
                    val immutable = ImmutableList.copyOf(expanded)
                    val idx = startIndex.coerceIn(0, maxOf(0, immutable.size - 1))
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(immutable, idx, startPositionMs)
                    )
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>
                ): ListenableFuture<List<MediaItem>> {
                    val snap = librarySnapshot
                    val out = ArrayList<MediaItem>(mediaItems.size + snap.size)
                    for (item in mediaItems) {
                        when (item.mediaId) {
                            SPECIAL_SHUFFLE_ALL -> {
                                // Same SecureRandom pre-shuffle as onSetMediaItems so
                                // "add and shuffle" from any controller is also truly random.
                                val shuffled = snap.toMutableList().also {
                                    java.util.Collections.shuffle(it, java.security.SecureRandom())
                                }
                                out.addAll(shuffled)
                                player.shuffleModeEnabled = true
                            }
                            else -> {
                                out.add(snap.find { it.mediaId == item.mediaId } ?: item)
                            }
                        }
                    }
                    return Futures.immediateFuture(out)
                }
            }
        )
        .setId("bombest_media")
        .setCustomLayout(
            ImmutableList.of(
                CommandButton.Builder()
                    .setDisplayName("Shuffle")
                    .setSessionCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                    .setIconResId(R.drawable.ic_shuffle)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("Repeat")
                    .setSessionCommand(SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
                    .setIconResId(R.drawable.ic_repeat)
                    .build()
            )
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

    private fun createPlaylistBrowseItem(pl: Playlist): MediaItem {
        val artUri = pl.art_url?.let { Uri.parse(NetworkModule.getStreamBaseUrl() + it) }
        val extras = Bundle().apply {
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        }
        return MediaItem.Builder()
            .setMediaId("playlist:${pl.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setTitle(pl.name)
                    .setSubtitle("${pl.count} tracks")
                    .setDisplayTitle(pl.name)
                    .setArtworkUri(artUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun buildMediaItemFromApiTrack(
        track: ApiTrack,
        transcodeForAuto: Boolean = false,
    ): MediaItem {
        val tid = track.id.toString()
        val streamUrl = repository.getStreamUrl(track.id, transcodeForAuto)
        val artUri = Uri.parse(repository.getTrackArtUrl(track.id))
        // Local downloads: let DownloadManager infer from extension (mp3/m4a/flac).
        // Remote streams: if we asked the server to transcode (Auto path), the bytes
        // come back as fragmented MP4/AAC; otherwise the source format's canonical
        // MIME (flac/mpeg/mp4/wav) so hardware offload can engage on phones.
        val isLocal = downloadManager.hasLocalTrackFile(tid)
        val mimeType = when {
            isLocal -> downloadManager.mimeTypeForPlayback(tid, track.path)
            transcodeForAuto -> androidx.media3.common.MimeTypes.AUDIO_MP4
            else -> inferRemoteMimeType(track.path)
        }
        return MediaItem.Builder()
            .setMediaId(tid)
            .setUri(downloadManager.playbackUriForTrack(tid, streamUrl))
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setTitle(track.title)
                    .setDisplayTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(artUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    /**
     * Whether this browse/session controller is the Android Auto projection host.
     * Gearhead is the package name shipped on every factory AA head unit; Polestar
     * and BMW OEM stacks also advertise as this package per the Car App Library.
     */
    private fun isAutoController(info: MediaSession.ControllerInfo?): Boolean {
        return info?.packageName == AUTO_PACKAGE
    }

    /**
     * Canonical MIME for a remote pass-through stream based on the source's file extension.
     * Falls back to MPEG (MP3) when the extension is unknown — safest default for the
     * legacy library where most uploads are MP3 anyway. Must agree with the backend's
     * `_PASS_THROUGH_CONTENT_TYPE` map (upload_server.py).
     */
    private fun inferRemoteMimeType(path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "flac" -> androidx.media3.common.MimeTypes.AUDIO_FLAC
            "mp3" -> androidx.media3.common.MimeTypes.AUDIO_MPEG
            "m4a", "mp4", "aac" -> androidx.media3.common.MimeTypes.AUDIO_MP4
            "wav" -> androidx.media3.common.MimeTypes.AUDIO_WAV
            "ogg", "oga" -> androidx.media3.common.MimeTypes.AUDIO_OGG
            else -> androidx.media3.common.MimeTypes.AUDIO_MPEG
        }
    }

    private fun slicePage(list: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (list.isEmpty()) return emptyList()
        val from = (page * pageSize).coerceAtMost(list.size)
        val to = (from + pageSize).coerceAtMost(list.size)
        return if (from < to) list.subList(from, to) else emptyList()
    }

    private fun rootBrowseItemCount(): Int {
        var n = 4 // shuffle, recent, songs, playlists
        if (favoritesPlaylist != null) n++
        return n
    }

    private fun createShuffleAllItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(SPECIAL_SHUFFLE_ALL)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setTitle("Shuffle library")
                    .setSubtitle("Play all tracks in random order")
                    .setDisplayTitle("Shuffle library")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun expandPlaybackItems(items: List<MediaItem>): List<MediaItem> {
        val snap = librarySnapshot
        val out = ArrayList<MediaItem>()
        for (item in items) {
            if (item.mediaId == SPECIAL_SHUFFLE_ALL) {
                if (snap.isNotEmpty()) {
                    // Don't pre-shuffle — ExoPlayer owns the shuffle order via shuffleModeEnabled.
                    // Pre-shuffling AND setting shuffleModeEnabled causes double-shuffle: ExoPlayer
                    // picks its next preload target from a random index in the already-shuffled list,
                    // forcing a cold HTTP connection mid-track instead of buffering sequentially.
                    out.addAll(snap)
                }
            } else {
                out.add(snap.find { it.mediaId == item.mediaId } ?: item)
            }
        }
        return out
    }

    private fun fetchLibrary(player: Player) {
        android.util.Log.d("BombestMediaService", "fetchLibrary() called, launching coroutine...")
        serviceScope.launch {
            try {
                android.util.Log.d("BombestMediaService", "Fetching library...")
                // Network I/O on IO dispatcher; MediaItem construction (CPU) on Default.
                // Keeping both off Main prevents GC pressure from object allocation
                // competing with ExoPlayer's event loop on the main thread.
                val tracks = withContext(Dispatchers.IO) { repository.fetchLibrary() }
                android.util.Log.d("BombestMediaService", "Fetched ${tracks.size} tracks")

                val mediaItems = withContext(Dispatchers.Default) {
                    tracks.map { track ->
                        val tid = track.id.toString()
                        // fetchLibrary runs before any controller connects, so default to the
                        // phone/headphones path (pass-through). Auto re-requests playables via
                        // onGetChildren with its packageName, and those build a fresh MediaItem
                        // list with ?transcode=aac and audio/mp4 MIME.
                        val streamUrl = repository.getStreamUrl(track.id, transcodeForAuto = false)
                        val artUri = Uri.parse(repository.getTrackArtUrl(track.id))
                        val isLocal = downloadManager.hasLocalTrackFile(tid)
                        val mimeType = when {
                            isLocal -> downloadManager.mimeTypeForPlayback(tid, track.path)
                            else -> inferRemoteMimeType(track.path)
                        }
                        MediaItem.Builder()
                            .setMediaId(tid)
                            .setUri(downloadManager.playbackUriForTrack(tid, streamUrl))
                            .setMimeType(mimeType)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .setTitle(track.displayTitle)
                                    .setDisplayTitle(track.displayTitle)
                                    .setArtist(track.displayArtist)
                                    .setAlbumTitle(track.album)
                                    .setArtworkUri(artUri)
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .build()
                            )
                            .build()
                    }
                }

                // Single atomic reference swap — no lock held, no contention
                librarySnapshot = mediaItems
                android.util.Log.d("BombestMediaService", "Library snapshot updated: ${mediaItems.size} items")

                try {
                    favoritesPlaylist = playlistApi?.getPlaylists()?.playlists?.find { it.name == "Favorites" }
                } catch (e: Exception) {
                    android.util.Log.w("BombestMediaService", "Could not load Favorites playlist for Android Auto", e)
                }

                // Only set media items when player is idle - don't clobber active playback during refresh
                if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) {
                    player.setMediaItems(mediaItems)
                    player.prepare()
                }

                // Notify session that children changed
                mediaSession?.notifyChildrenChanged(ROOT_ID, rootBrowseItemCount(), null)
                mediaSession?.notifyChildrenChanged(SONGS_ID, mediaItems.size, null)
                mediaSession?.notifyChildrenChanged(PLAYLISTS_ID, 0, null)
            } catch (e: Exception) {
                android.util.Log.e("BombestMediaService", "Error fetching library", e)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && player.playWhenReady && player.mediaItemCount > 0) {
            // Active playback — keep the service alive so Android Auto keeps working
            // after the user swipes the phone app from recents.
            return
        }
        mediaSession?.player?.run {
            stop()
            clearMediaItems()
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        PerformanceMetricsManager.flush()
        // Detach listeners before release so any held closures drop references promptly —
        // release() alone isn't always enough when the service is recreated rapidly.
        // listenersRemoved guard: protects against onDestroy racing with itself on rapid
        // Auto reconnects (observed in prod logs — double-remove would NPE on the player).
        if (listenersRemoved.compareAndSet(false, true)) {
            this.player?.let { p ->
                audioOffloadListener?.let { p.removeAudioOffloadListener(it) }
                playerListener?.let { p.removeListener(it) }
                analyticsListener?.let { p.removeAnalyticsListener(it) }
            }
            audioOffloadListener = null
            playerListener = null
            analyticsListener = null
        }
        mediaSession?.run {
            player.stop()
            player.release()
            release()
            mediaSession = null
        }
        this.player = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
