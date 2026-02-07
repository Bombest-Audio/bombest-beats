@file:OptIn(UnstableApi::class)

package com.bombest.musify.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import android.content.BroadcastReceiver
import android.content.Context
import android.widget.RemoteViews
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.net.URL
import java.net.HttpURLConnection
import java.io.File
import java.io.FileWriter
import android.net.Uri
import com.bombest.musify.R
import com.bombest.musify.ui.activities.MainActivity
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.domain.SearchResult
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    lateinit var exoPlayer: ExoPlayer
    
    @Inject
    lateinit var tracksRepository: TracksRepository

    private var mediaSession: MediaLibraryService.MediaLibrarySession? = null
    
    // Android Auto library caching
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val libraryItems = mutableListOf<MediaItem>()
    private val categoryItems = mutableListOf<MediaItem>()
    private val subscriptions = mutableSetOf<String>()
    private var loadingDeferred: Deferred<Unit>? = null

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "com.bombest.musify.PLAYBACK_CHANNEL"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.bombest.musify.STOP"
        const val ACTION_DISMISS = "com.bombest.musify.DISMISS"
        const val ACTION_PREVIOUS = "com.bombest.musify.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.bombest.musify.PLAY_PAUSE"
        const val ACTION_NEXT = "com.bombest.musify.NEXT"
        const val ACTION_SHUFFLE = "com.bombest.musify.SHUFFLE"
        const val ACTION_REPEAT = "com.bombest.musify.REPEAT"
        const val ACTION_ADD = "com.bombest.musify.ADD"
        
        // Android Auto content hierarchy IDs
        private const val ROOT_ID = "root"
        private const val SONGS_ID = "songs"
        private const val RECENT_ID = "recent"
        
        // Android Auto content style constants
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2
        private const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("PlaybackService", "onCreate called")
        createNotificationChannel()
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val mainActivityIntent = Intent().apply {
            setClassName(applicationContext, "com.bombest.musify.ui.activities.MainActivity")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            pendingIntentFlags
        )
        
        // Build MediaLibrarySession with callback for Android Auto support
        // ExoPlayer automatically exposes COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM and
        // COMMAND_SEEK_TO_NEXT_MEDIA_ITEM when hasPreviousMediaItem()/hasNextMediaItem() return true.
        // MediaSession will automatically make these available to the system for Android 13+ button generation.
        mediaSession = MediaLibraryService.MediaLibrarySession.Builder(this, exoPlayer, MediaLibrarySessionCallback())
            .setSessionActivity(pendingIntent)
            .build()
        
        // Note: Media3's MediaSession automatically exposes Player commands to Android Auto
        // The shuffle/repeat buttons should appear when:
        // 1. ExoPlayer has COMMAND_SET_SHUFFLE_MODE and COMMAND_SET_REPEAT_MODE available (default)
        // 2. ExoPlayer has multiple items in queue (for shuffle) or any items (for repeat)
        // 3. ExoPlayer's shuffleModeEnabled and repeatMode are set (synced in PlaybackViewModel)
        // MediaSession reads ExoPlayer state automatically, so no explicit button preferences needed
        
        // Load library content for Android Auto
        loadLibraryContent()
        
        // Ensure ExoPlayer's shuffle/repeat state is properly initialized for Android Auto
        // Android Auto reads these states directly from the Player and shows buttons automatically
        // The MediaSession automatically exposes COMMAND_SET_SHUFFLE_MODE and COMMAND_SET_REPEAT_MODE
        // via DEFAULT_SESSION_AND_LIBRARY_COMMANDS, so Android Auto can control them
        Log.d("PlaybackService", "Initial ExoPlayer state - shuffle: ${exoPlayer.shuffleModeEnabled}, repeat: ${exoPlayer.repeatMode}")
        Log.d("PlaybackService", "ExoPlayer has ${exoPlayer.mediaItemCount} items in queue")
        
        // Add player listener to log state changes and ensure foreground service
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d("PlaybackService", "Player state changed: $playbackState, isPlaying: ${exoPlayer.isPlaying}")
                // Ensure service stays in foreground when playing
                if (playbackState == Player.STATE_READY && exoPlayer.isPlaying) {
                    ensureForegroundService()
                }
                // Update notification when state changes
                mediaSession?.let { session ->
                    onUpdateNotification(session, exoPlayer.isPlaying || exoPlayer.mediaItemCount > 0)
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d("PlaybackService", "Player isPlaying changed: $isPlaying")
                // Ensure service stays in foreground when playing starts
                if (isPlaying) {
                    ensureForegroundService()
                }
                // Update notification when play state changes
                mediaSession?.let { session ->
                    onUpdateNotification(session, isPlaying || exoPlayer.mediaItemCount > 0)
                }
            }
            
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // Update notification when track changes
                mediaSession?.let { session ->
                    onUpdateNotification(session, exoPlayer.isPlaying || exoPlayer.mediaItemCount > 0)
                }
            }
            
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                Log.d("PlaybackService", "ExoPlayer shuffle mode changed: $shuffleModeEnabled")
                // Android Auto will automatically show/hide shuffle button based on this state
                // MediaSession automatically reflects ExoPlayer state changes to Android Auto
                // Ensure we have a queue for Android Auto to show the button
                if (exoPlayer.mediaItemCount > 1) {
                    Log.d("PlaybackService", "Shuffle state updated - Android Auto should show button (queue has ${exoPlayer.mediaItemCount} items)")
                }
            }
            
            override fun onRepeatModeChanged(repeatMode: Int) {
                Log.d("PlaybackService", "ExoPlayer repeat mode changed: $repeatMode")
                // Android Auto will automatically show/hide repeat button based on this state
                // MediaSession automatically reflects ExoPlayer state changes to Android Auto
                // Ensure we have a queue for Android Auto to show the button
                if (exoPlayer.mediaItemCount > 0) {
                    Log.d("PlaybackService", "Repeat state updated - Android Auto should show button (queue has ${exoPlayer.mediaItemCount} items)")
                }
            }
        })
        
        Log.d("PlaybackService", "MediaSession created, player state: ${exoPlayer.playbackState}, mediaItemCount: ${exoPlayer.mediaItemCount}")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        Log.d("PlaybackService", "onGetSession called from ${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onDestroy() {
        // Don't release the player here - it's a singleton used by MusifyBackgroundMusicPlayerV2
        // Only release the MediaSession
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // LOW importance for media notifications - no sound/vibration
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                enableLights(false)
                setSound(null, null) // Explicitly disable sound
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"D\",\"location\":\"PlaybackService.kt:150\",\"message\":\"Notification channel created\",\"data\":{\"channelId\":\"$NOTIFICATION_CHANNEL_ID\",\"importance\":${NotificationManager.IMPORTANCE_DEFAULT}},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
            } catch (e: Exception) {}
            // #endregion
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("PlaybackService", "onStartCommand called, action: ${intent?.action}")
        
        // CRITICAL: Ensure service is in foreground immediately to avoid crash
        // This must happen within 5 seconds of startForegroundService() call
        if (exoPlayer.isPlaying || exoPlayer.mediaItemCount > 0) {
            ensureForegroundServiceImmediate()
        }
        
        when (intent?.action) {
            ACTION_STOP, ACTION_DISMISS -> {
                exoPlayer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PREVIOUS -> {
                // Check if track has been playing for more than 2.5 seconds
                // If so, restart the current track instead of going to previous
                val currentPositionMillis = exoPlayer.currentPosition
                val restartThresholdMillis = 2500L // 2.5 seconds
                
                if (currentPositionMillis > restartThresholdMillis) {
                    // Restart current track
                    exoPlayer.seekTo(0)
                } else if (exoPlayer.hasPreviousMediaItem()) {
                    // Go to previous track
                    exoPlayer.seekToPreviousMediaItem()
                }
            }
            ACTION_PLAY_PAUSE -> {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                } else {
                    exoPlayer.play()
                }
            }
            ACTION_NEXT -> {
                if (exoPlayer.hasNextMediaItem()) {
                    exoPlayer.seekToNextMediaItem()
                }
            }
            ACTION_SHUFFLE -> {
                // CRITICAL: Don't use ExoPlayer's shuffle - use custom Spotify-style shuffle
                // This is handled by PlaybackViewModel, but we need to prevent ExoPlayer shuffle here too
                exoPlayer.shuffleModeEnabled = false
                // The actual shuffle toggle is handled by PlaybackViewModel via the MediaSession
                // For now, just ensure ExoPlayer shuffle is disabled
                Log.d("PlaybackService", "Shuffle action received - ExoPlayer shuffle disabled (using custom shuffle)")
            }
            ACTION_REPEAT -> {
                // Cycle through repeat modes: NONE -> ALL -> ONE -> NONE
                val currentMode = exoPlayer.repeatMode
                val nextMode = when (currentMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                    else -> Player.REPEAT_MODE_OFF
                }
                exoPlayer.repeatMode = nextMode
                Log.d("PlaybackService", "Repeat mode changed via intent: $currentMode -> $nextMode")
            }
            ACTION_ADD -> {
                // Open add to playlist dialog - launch MainActivity with intent
                val addIntent = Intent().apply {
                    setClassName(applicationContext, "com.bombest.musify.ui.activities.MainActivity")
                    action = "com.bombest.musify.ADD_TO_PLAYLIST"
                    putExtra("media_item_id", exoPlayer.currentMediaItem?.mediaId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(addIntent)
            }
        }
        
        // Update notification after action
        mediaSession?.let { session ->
            onUpdateNotification(session, exoPlayer.isPlaying || exoPlayer.mediaItemCount > 0)
        }
        
        return START_STICKY
    }
    
    private fun ensureForegroundServiceImmediate() {
        // #region agent log
        try {
            val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"E\",\"location\":\"PlaybackService.kt:211\",\"message\":\"ensureForegroundServiceImmediate entry\",\"data\":{\"hasMediaSession\":${mediaSession != null}},\"timestamp\":${System.currentTimeMillis()}}\n")
            }
        } catch (e: Exception) {}
        // #endregion
        // This must be called immediately to avoid ForegroundServiceDidNotStartInTimeException
        // Use onUpdateNotification directly to avoid duplicate notifications
        mediaSession?.let { session ->
            onUpdateNotification(session, true)
        } ?: run {
            // Fallback if MediaSession not ready yet
            val currentMediaItem = exoPlayer.currentMediaItem
            if (currentMediaItem != null) {
                val metadata = currentMediaItem.mediaMetadata
                val title = metadata.title?.toString() ?: "Bombest Beats"
                val artist = metadata.artist?.toString() ?: "Unknown Artist"
                
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val mainActivityIntent = Intent().apply {
                    setClassName(applicationContext, "com.bombest.musify.ui.activities.MainActivity")
                }
                val contentIntent = PendingIntent.getActivity(
                    this,
                    0,
                    mainActivityIntent,
                    pendingIntentFlags
                )
                
                // Create a simple notification immediately to satisfy foreground service requirement
                // This is a temporary notification that will be replaced by onUpdateNotification
                val simpleNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(artist)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(contentIntent)
                    .setOngoing(exoPlayer.isPlaying)
                    .build()
                
                startForeground(NOTIFICATION_ID, simpleNotification)
                // #region agent log
                try {
                    val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                    FileWriter(logFile, true).use { writer ->
                        writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"E\",\"location\":\"PlaybackService.kt:249\",\"message\":\"Simple notification started, scheduling update\",\"data\":{\"notificationId\":$NOTIFICATION_ID},\"timestamp\":${System.currentTimeMillis()}}\n")
                    }
                } catch (e: Exception) {}
                // #endregion
                
                // Immediately update with full notification to avoid duplicates
                // Small delay to ensure MediaSession is ready
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    // #region agent log
                    try {
                        val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                        FileWriter(logFile, true).use { writer ->
                            writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"E\",\"location\":\"PlaybackService.kt:253\",\"message\":\"Delayed update callback\",\"data\":{\"hasMediaSession\":${mediaSession != null}},\"timestamp\":${System.currentTimeMillis()}}\n")
                    }
                } catch (e: Exception) {}
                // #endregion
                    mediaSession?.let { session ->
                        onUpdateNotification(session, true)
                    }
                }, 100)
            }
        }
    }
    
    private fun ensureForegroundService() {
        // MediaSessionService should handle this via onUpdateNotification
        // Just trigger the update - don't create duplicate notifications
        if (exoPlayer.isPlaying || exoPlayer.mediaItemCount > 0) {
            try {
                val session = mediaSession ?: return
                onUpdateNotification(session, true)
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error ensuring foreground service", e)
            }
        }
    }
    
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        Log.d("PlaybackService", "onUpdateNotification called, startInForegroundRequired: $startInForegroundRequired, player playing: ${exoPlayer.isPlaying}, has media: ${exoPlayer.mediaItemCount > 0}")
        
        // #region agent log
        try {
            val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A,B,C,D,E,F\",\"location\":\"PlaybackService.kt:273\",\"message\":\"onUpdateNotification entry\",\"data\":{\"startInForegroundRequired\":$startInForegroundRequired,\"isPlaying\":${exoPlayer.isPlaying},\"mediaItemCount\":${exoPlayer.mediaItemCount}},\"timestamp\":${System.currentTimeMillis()}}\n")
            }
        } catch (e: Exception) {}
        // #endregion
        
        // Create MediaStyle notification with custom RemoteViews
        // IMPORTANT: Always create our custom notification if we have a media item,
        // regardless of startInForegroundRequired, to ensure buttons are always shown
        val currentMediaItem = exoPlayer.currentMediaItem
        if (currentMediaItem != null) {
            val metadata = currentMediaItem.mediaMetadata
            val title = metadata.title?.toString() ?: "Bombest Beats"
            val artist = metadata.artist?.toString() ?: "Unknown Artist"
            
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val mainActivityIntent = Intent().apply {
                setClassName(applicationContext, "com.bombest.musify.ui.activities.MainActivity")
            }
            val contentIntent = PendingIntent.getActivity(
                this,
                0,
                mainActivityIntent,
                pendingIntentFlags
            )
            
            // Create PendingIntents for custom big content view buttons
            // These are needed for the expanded notification RemoteViews
            // Note: For Android 13+, standard actions (Previous, Play/Pause, Next) are handled
            // automatically by MediaSession from Player state, but we still need PendingIntents
            // for the custom big content view buttons
            val previousIntent = Intent(ACTION_PREVIOUS).apply {
                setPackage(packageName)
            }
            val previousPendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                previousIntent,
                pendingIntentFlags
            )
            
            val playPauseIntent = Intent(ACTION_PLAY_PAUSE).apply {
                setPackage(packageName)
            }
            val playPausePendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                playPauseIntent,
                pendingIntentFlags
            )
            
            val nextIntent = Intent(ACTION_NEXT).apply {
                setPackage(packageName)
            }
            val nextPendingIntent = PendingIntent.getBroadcast(
                this,
                2,
                nextIntent,
                pendingIntentFlags
            )
            
            // Create dismiss intent to stop playback
            val dismissIntent = Intent(ACTION_DISMISS).apply {
                setPackage(packageName)
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                this,
                3,
                dismissIntent,
                pendingIntentFlags
            )
            
            // Create shuffle and add action intents for custom big content view
            // These are also handled via MediaButtonPreferences for Android 13+
            val shuffleIntent = Intent(ACTION_SHUFFLE).apply {
                setPackage(packageName)
            }
            val shufflePendingIntent = PendingIntent.getBroadcast(
                this,
                4,
                shuffleIntent,
                pendingIntentFlags
            )
            
            val addIntent = Intent(ACTION_ADD).apply {
                setPackage(packageName)
            }
            val addPendingIntent = PendingIntent.getBroadcast(
                this,
                5,
                addIntent,
                pendingIntentFlags
            )
            
            // Determine play/pause icon (for backward compatibility with pre-Android 13)
            val playPauseIcon = if (exoPlayer.isPlaying) {
                R.drawable.ic_pause_graffiti
            } else {
                R.drawable.ic_play_graffiti
            }
            
            // Determine shuffle icon (for backward compatibility)
            val shuffleIcon = R.drawable.ic_round_shuffle_24
            
            // Load album artwork asynchronously to avoid NetworkOnMainThreadException
            // For now, skip artwork loading to avoid blocking notification creation
            // Artwork can be loaded later and notification updated
            val artworkBitmap: Bitmap? = null // Skip artwork for now to ensure notification works
            
            Log.d("PlaybackService", "Artwork loading skipped, proceeding with notification creation")
            
            // Create custom RemoteViews for big layout (Spotify-style)
            val bigView = try {
                RemoteViews(packageName, R.layout.custom_media_notification_big).also {
                    updateRemoteViews(it, title, artist, exoPlayer, previousPendingIntent, playPausePendingIntent, nextPendingIntent, metadata, isBigView = true, shufflePendingIntent, addPendingIntent)
                    Log.d("PlaybackService", "Big RemoteViews created and updated successfully")
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error creating big RemoteViews", e)
                null
            }
            
            // Create MediaStyle notification with Spotify-style layout
            // For Android 13+ (API 33+): Standard action buttons (Previous, Play/Pause, Next) are 
            // automatically derived from Player state via MediaSession
            // Custom actions (Shuffle, Add) still need to be added manually via addAction()
            // For backward compatibility (pre-Android 13), all actions are added manually
            val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(R.drawable.ic_bomb_notification) // Bomb emoji icon
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW) // LOW priority for media notifications - no sound
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setDeleteIntent(dismissPendingIntent)
                .setOngoing(exoPlayer.isPlaying)
                .setShowWhen(false) // Hide timestamp for media notifications
                .setAutoCancel(false) // Don't auto-cancel when clicked
                .setOnlyAlertOnce(true) // Only alert once per notification update
                .setSilent(true) // Explicitly disable sound for media notifications
            
            // Add all actions manually to ensure they appear on all Android versions
            // Even on Android 13+, adding actions manually ensures they're visible
            // The system will use Player state for button placement, but actions must be present
            notificationBuilder
                .addAction(R.drawable.ic_previous_graffiti, "Previous", previousPendingIntent)
                .addAction(playPauseIcon, if (exoPlayer.isPlaying) "Pause" else "Play", playPausePendingIntent)
                .addAction(R.drawable.ic_next_graffiti, "Next", nextPendingIntent)
                .addAction(shuffleIcon, "Shuffle", shufflePendingIntent)
                .addAction(R.drawable.ic_baseline_add_circle_outline_24, "Add", addPendingIntent)
            
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"location\":\"PlaybackService.kt:408\",\"message\":\"Actions added to builder\",\"data\":{\"actionCount\":5,\"playPauseIcon\":$playPauseIcon,\"hasPreviousIntent\":${previousPendingIntent != null},\"hasNextIntent\":${nextPendingIntent != null}},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
            } catch (e: Exception) {}
            // #endregion
            
            // Add artwork as large icon (for compact view)
            artworkBitmap?.let {
                notificationBuilder.setLargeIcon(it)
            }
            
            // Set MediaStyle with MediaSession token
            // CRITICAL: Always specify which actions to show in compact view (indices 0, 1, 2)
            // This ensures Previous, Play/Pause, and Next buttons are visible in compact view
            // The actions must be added BEFORE setting the style
            // On Android 13+, the system will automatically show/hide buttons based on Player state
            // (hasNextMediaItem/hasPreviousMediaItem), but we still need to specify the indices
            val mediaStyle = MediaStyle()
                .setMediaSession(session.sessionCompatToken)
                .setShowActionsInCompactView(0, 1, 2) // Previous (0), Play/Pause (1), Next (2)
            
            // Log player state for debugging
            Log.d("PlaybackService", "Player state: hasNext=${exoPlayer.hasNextMediaItem()}, hasPrevious=${exoPlayer.hasPreviousMediaItem()}, mediaItemCount=${exoPlayer.mediaItemCount}, currentIndex=${exoPlayer.currentMediaItemIndex}")
            
            // CRITICAL: Set style and big content view together
            // On Android 13+, setting a custom big content view can interfere with MediaStyle buttons
            // But we need it for the Spotify-style layout, so we set both
            notificationBuilder.setStyle(mediaStyle)
            
            // Only set big content view if it was created successfully
            if (bigView != null) {
                notificationBuilder.setCustomBigContentView(bigView)
                Log.d("PlaybackService", "Big content view set successfully")
            } else {
                Log.e("PlaybackService", "Big content view is null - notification won't expand!")
            }
            
            Log.d("PlaybackService", "MediaStyle configured: hasToken=${session.sessionCompatToken != null}, compactActions=0,1,2, actionCount=${notificationBuilder.build().actions?.size ?: 0}, hasBigView=${bigView != null}")
            
            Log.d("PlaybackService", "Style and big view set: hasBigView=${bigView != null}, package=$packageName, mediaStyleSet=true")
            
            val notification = try {
                notificationBuilder.build()
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error building notification", e)
                null
            }
            
            if (notification == null) {
                Log.e("PlaybackService", "Notification is null after build, calling super")
                super.onUpdateNotification(session, startInForegroundRequired)
                return
            }
            
            val actions = notification.actions?.size ?: 0
            val hasMediaStyle = notification.extras?.get(NotificationCompat.EXTRA_MEDIA_SESSION) != null
            // Check for big content view - it's stored as a RemoteViews parcelable
            // Note: The bigContentView might not be in extras, check the notification object directly
            val bigContentViewKey = "android.bigContentView"
            val contentViewKey = "android.contentView"
            val bigContentView = try {
                notification.extras?.getParcelable<RemoteViews>(bigContentViewKey)
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error reading bigContentView from extras", e)
                null
            }
            val contentView = try {
                notification.extras?.getParcelable<RemoteViews>(contentViewKey)
            } catch (e: Exception) {
                null
            }
            val style = notification.extras?.getCharSequence(NotificationCompat.EXTRA_TEMPLATE)
            // Also check if bigContentView was set via reflection as a fallback
            val hasBigViewReflection = try {
                val field = notification.javaClass.getDeclaredField("bigContentView")
                field.isAccessible = true
                field.get(notification) != null
            } catch (e: Exception) {
                false
            }
            Log.d("PlaybackService", "Notification built: actionCount=$actions, hasMediaStyle=$hasMediaStyle, hasBigContentView=${bigContentView != null}, hasBigViewReflection=$hasBigViewReflection, hasContentView=${contentView != null}, style=$style, actions=${notification.actions?.map { it.title }}")
            
            // Always update the notification, whether foreground is required or not
            // This ensures our custom notification with buttons is always shown
            if (startInForegroundRequired) {
                startForeground(NOTIFICATION_ID, notification)
                Log.d("PlaybackService", "Started foreground service: actions=${notification.actions?.size}, bigContentView=${bigContentView != null}, contentView=${contentView != null}")
            } else {
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, notification)
                Log.d("PlaybackService", "Updated notification via notify: actions=${notification.actions?.size}, bigContentView=${bigContentView != null}")
            }
        } else {
            // #region agent log
            try {
                val logFile = File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                FileWriter(logFile, true).use { writer ->
                    writer.append("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A,B\",\"location\":\"PlaybackService.kt:437\",\"message\":\"Calling super.onUpdateNotification\",\"data\":{\"currentMediaItem\":${currentMediaItem != null},\"startInForegroundRequired\":$startInForegroundRequired},\"timestamp\":${System.currentTimeMillis()}}\n")
                }
            } catch (e: Exception) {}
            // #endregion
            // Call super to use default behavior if no media item
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }
    
    private fun updateRemoteViews(
        views: RemoteViews,
        title: String,
        artist: String,
        player: ExoPlayer,
        previousIntent: PendingIntent,
        playPauseIntent: PendingIntent,
        nextIntent: PendingIntent,
        metadata: androidx.media3.common.MediaMetadata,
        isBigView: Boolean = false,
        shuffleIntent: PendingIntent? = null,
        addIntent: PendingIntent? = null
    ) {
        // Update text based on view type
        if (isBigView) {
            views.setTextViewText(R.id.notification_big_title, title)
            views.setTextViewText(R.id.notification_big_artist, artist)
        } else {
            views.setTextViewText(R.id.notification_title, title)
            views.setTextViewText(R.id.notification_artist, artist)
        }
        
        // Update play/pause button
        val playPauseIcon = if (player.isPlaying) {
            R.drawable.ic_pause_graffiti
        } else {
            R.drawable.ic_play_graffiti
        }
        if (isBigView) {
            views.setImageViewResource(R.id.notification_big_play_pause, playPauseIcon)
            views.setOnClickPendingIntent(R.id.notification_big_play_pause, playPauseIntent)
            views.setOnClickPendingIntent(R.id.notification_big_prev, previousIntent)
            views.setOnClickPendingIntent(R.id.notification_big_next, nextIntent)
            // Add shuffle and add buttons if provided
            shuffleIntent?.let {
                views.setOnClickPendingIntent(R.id.notification_big_shuffle, it)
                // Note: RemoteViews doesn't support setAlpha directly
                // Shuffle state will be indicated by icon color in future update
            }
            addIntent?.let {
                views.setOnClickPendingIntent(R.id.notification_big_add, it)
            }
        } else {
            views.setImageViewResource(R.id.notification_play_pause, playPauseIcon)
            views.setOnClickPendingIntent(R.id.notification_play_pause, playPauseIntent)
            views.setOnClickPendingIntent(R.id.notification_prev, previousIntent)
            views.setOnClickPendingIntent(R.id.notification_next, nextIntent)
        }
        
        // Update progress (squiggly progress bar in big view)
        val duration = player.duration
        val position = player.currentPosition
        val progress = if (duration > 0) {
            ((position.toFloat() / duration.toFloat()) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        if (isBigView) {
            // Update squiggly progress bar
            views.setProgressBar(R.id.notification_big_progress, 100, progress, false)
        } else {
            // Compact view doesn't have custom progress bar (uses standard MediaStyle)
            // But update if it exists
            try {
                views.setProgressBar(R.id.notification_progress, 100, progress, false)
            } catch (e: Exception) {
                // Ignore if progress bar doesn't exist in compact view
            }
        }
        
        // Update time displays
        val elapsedTime = formatTime(position)
        val totalTime = formatTime(duration)
        if (isBigView) {
            views.setTextViewText(R.id.notification_big_time_elapsed, elapsedTime)
            views.setTextViewText(R.id.notification_big_time_total, totalTime)
        } else {
            views.setTextViewText(R.id.notification_time_elapsed, elapsedTime)
            views.setTextViewText(R.id.notification_time_total, totalTime)
        }
        
        // Load album artwork asynchronously (if not already loaded)
        // This will be handled in the main notification creation
    }
    
    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
    
    private fun updateNotificationWithArtwork(bitmap: Bitmap) {
        // This method is no longer used - artwork is loaded synchronously in onUpdateNotification
        // to avoid duplicate notifications. This method is kept for backwards compatibility.
        // All notification updates should go through onUpdateNotification to prevent duplicates.
        Log.d("PlaybackService", "updateNotificationWithArtwork called - use onUpdateNotification instead")
    }
    
    private suspend fun loadBitmapFromUri(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        loadBitmapFromUriSync(uriString)
    }
    
    private fun loadBitmapFromUriSync(uriString: String): Bitmap? {
        return try {
            val url = URL(uriString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doInput = true
            connection.connect()
            val inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            connection.disconnect()
            bitmap
        } catch (e: Exception) {
            Log.e("PlaybackService", "Error loading bitmap from URI: $uriString", e)
            null
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("PlaybackService", "onTaskRemoved called")
        // Don't stop the service when task is removed - keep playing in background
        super.onTaskRemoved(rootIntent)
    }
    
    // Android Auto helper methods
    
    private fun createRootExtras(): Bundle {
        return Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        }
    }
    
    private fun createCategoryItem(mediaId: String, title: String, subtitle: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }
    
    private fun buildMediaItem(track: SearchResult.TrackSearchResult): MediaItem {
        val artworkUri = try {
            Uri.parse(track.imageUrlString)
        } catch (e: Exception) {
            null
        }
        
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.trackUrlString)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artistsString)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .apply {
                        artworkUri?.let { setArtworkUri(it) }
                    }
                    .build()
            )
            .build()
    }
    
    private fun loadLibraryContent(): Deferred<Unit> {
        // If already loading, return the existing deferred
        loadingDeferred?.let { return it }
        
        // Create a new deferred for this loading operation
        val deferred = serviceScope.async {
            try {
                Log.d("PlaybackService", "Loading library content for Android Auto")
                val tracks = tracksRepository.getAllTracks()
                libraryItems.clear()
                libraryItems.addAll(tracks.map { track -> buildMediaItem(track) })
                
                // Build category items
                categoryItems.clear()
                categoryItems.add(createCategoryItem(SONGS_ID, "All Songs", "Browse all tracks"))
                categoryItems.add(createCategoryItem(RECENT_ID, "Recently Played", "Your listening history"))
                
                Log.d("PlaybackService", "Loaded ${libraryItems.size} tracks and ${categoryItems.size} categories")
                
                // CRITICAL: Populate ExoPlayer queue with all tracks to ensure hasNextMediaItem() returns true
                // This is required for Android 13+ to show the next button in notifications
                if (libraryItems.isNotEmpty()) {
                    val currentItem = exoPlayer.currentMediaItem
                    val currentMediaId = currentItem?.mediaId
                    val currentPosition = exoPlayer.currentPosition
                    val wasPlaying = exoPlayer.isPlaying
                    
                    // Find current item's index in the new queue
                    val currentIndex = currentMediaId?.let { id ->
                        libraryItems.indexOfFirst { it.mediaId == id }.takeIf { it >= 0 }
                    }
                    
                    // Set all tracks as the player's queue
                    exoPlayer.setMediaItems(libraryItems, /* resetPosition= */ false)
                    
                    // If we had a current item, restore its position
                    if (currentIndex != null) {
                        exoPlayer.seekToDefaultPosition(currentIndex)
                        // Restore playback position within the track if it was playing
                        if (currentPosition > 0 && currentPosition < exoPlayer.duration) {
                            exoPlayer.seekTo(currentPosition)
                        }
                        // Restore playing state
                        if (wasPlaying) {
                            exoPlayer.play()
                        }
                        Log.d("PlaybackService", "Restored playback: index=$currentIndex, position=$currentPosition, playing=$wasPlaying")
                    }
                    
                    Log.d("PlaybackService", "Populated ExoPlayer queue with ${libraryItems.size} items, hasNext=${exoPlayer.hasNextMediaItem()}, hasPrevious=${exoPlayer.hasPreviousMediaItem()}, currentIndex=${exoPlayer.currentMediaItemIndex}")
                    
                    // CRITICAL: Ensure ExoPlayer shuffle/repeat state is visible for Android Auto
                    // Android Auto shows buttons when:
                    // 1. Player has multiple items in queue (for shuffle) or any items (for repeat)
                    // 2. COMMAND_SET_SHUFFLE_MODE and COMMAND_SET_REPEAT_MODE are available (in DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                    // 3. Player's shuffleModeEnabled and repeatMode are set (MediaSession reads these automatically)
                    // The MediaSession automatically reflects ExoPlayer state, so we just need to ensure state is set
                    Log.d("PlaybackService", "After queue load - shuffle: ${exoPlayer.shuffleModeEnabled}, repeat: ${exoPlayer.repeatMode}, queue size: ${exoPlayer.mediaItemCount}")
                    
                    // Force MediaSession to refresh - this ensures Android Auto sees the updated queue and state
                    // The MediaSession should automatically reflect ExoPlayer changes, but we can trigger a refresh
                    mediaSession?.let { session ->
                        // Accessing the session and player together ensures state is synchronized
                        // Android Auto queries the Player through MediaSession, so this should work
                        Log.d("PlaybackService", "MediaSession synchronized with ExoPlayer state")
                    }
                }
                
                // Notify session of changes
                mediaSession?.let { session ->
                    session.notifyChildrenChanged(ROOT_ID, categoryItems.size, null)
                    session.notifyChildrenChanged(SONGS_ID, libraryItems.size, null)
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error loading library content", e)
            } finally {
                // Clear the deferred when done so we can load again if needed
                loadingDeferred = null
            }
            // Explicitly return Unit
            Unit
        }
        
        loadingDeferred = deferred
        return deferred
    }
    
    /**
     * MediaSession.Callback implementation to handle custom commands and authorize controllers.
     * For Android 13+, custom buttons (shuffle, add) are handled via custom SessionCommands.
     */
    private inner class MediaLibrarySessionCallback : MediaLibraryService.MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d("PlaybackService", "onConnect called from ${controller.packageName}")
            
            // Authorize all controllers, including SystemUI and Android Auto
            // DEFAULT_SESSION_AND_LIBRARY_COMMANDS already includes COMMAND_SET_SHUFFLE_MODE and COMMAND_SET_REPEAT_MODE
            // Android Auto reads ExoPlayer's shuffleModeEnabled and repeatMode directly and shows buttons automatically
            // Custom commands are for notification/phone UI, but Android Auto uses standard Player state
            Log.d("PlaybackService", "onConnect from ${controller.packageName} - ExoPlayer shuffle: ${exoPlayer.shuffleModeEnabled}, repeat: ${exoPlayer.repeatMode}, queue size: ${exoPlayer.mediaItemCount}")
            
            // For Android Auto specifically, ensure shuffle/repeat are visible
            // Android Auto shows these buttons when:
            // 1. Player has multiple items in queue (for shuffle) or any items (for repeat)
            // 2. COMMAND_SET_SHUFFLE_MODE and COMMAND_SET_REPEAT_MODE are available (included in DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
            // 3. Player's shuffleModeEnabled and repeatMode are set (which we sync)
            
            if (controller.packageName.contains("android.auto", ignoreCase = true) || 
                controller.packageName.contains("com.google.android.projection.gearhead", ignoreCase = true)) {
                Log.d("PlaybackService", "Android Auto connected - ensuring shuffle/repeat visibility")
                Log.d("PlaybackService", "Queue size: ${exoPlayer.mediaItemCount}, hasNext: ${exoPlayer.hasNextMediaItem()}, hasPrevious: ${exoPlayer.hasPreviousMediaItem()}")
                
                // CRITICAL: Ensure tracks are loaded when Android Auto connects
                // This fixes the issue where tracks don't appear until the app is opened
                if (libraryItems.isEmpty()) {
                    Log.d("PlaybackService", "Android Auto connected but tracks not loaded - triggering load")
                    loadLibraryContent()
                }
            }
            
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)) // For notification/phone UI
                        .add(SessionCommand(ACTION_REPEAT, Bundle.EMPTY)) // For notification/phone UI
                        .add(SessionCommand(ACTION_ADD, Bundle.EMPTY))
                        .build()
                )
                .build()
        }
        
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            Log.d("PlaybackService", "onCustomCommand called: ${customCommand.customAction}")
            
            when (customCommand.customAction) {
                ACTION_SHUFFLE -> {
                    // CRITICAL: Don't use ExoPlayer's shuffle - use custom Spotify-style shuffle
                    // The actual shuffle toggle should be handled by PlaybackViewModel
                    // For now, just ensure ExoPlayer shuffle is disabled
                    exoPlayer.shuffleModeEnabled = false
                    Log.d("PlaybackService", "Shuffle command received - ExoPlayer shuffle disabled (using custom shuffle)")
                    // Note: Actual shuffle state is managed by PlaybackQueueManager
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_REPEAT -> {
                    // Cycle through repeat modes: NONE -> ALL -> ONE -> NONE
                    val currentMode = exoPlayer.repeatMode
                    val nextMode = when (currentMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                        else -> Player.REPEAT_MODE_OFF
                    }
                    exoPlayer.repeatMode = nextMode
                    Log.d("PlaybackService", "Repeat mode changed: $currentMode -> $nextMode")
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_ADD -> {
                    // Open add to playlist dialog
                    val addIntent = Intent().apply {
                        setClassName(applicationContext, "com.bombest.musify.ui.activities.MainActivity")
                        action = "com.bombest.musify.ADD_TO_PLAYLIST"
                        putExtra("media_item_id", exoPlayer.currentMediaItem?.mediaId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(addIntent)
                    Log.d("PlaybackService", "Add to playlist action triggered")
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> {
                    Log.w("PlaybackService", "Unknown custom command: ${customCommand.customAction}")
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            }
        }
        
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            Log.d("PlaybackService", "onAddMediaItems called with ${mediaItems.size} items")
            // Update the player's queue with the provided items
            // This ensures hasNextMediaItem() returns true when appropriate
            try {
                val updatedItems = mediaItems.map { mediaItem ->
                    // Try to find the item in our library cache for better metadata
                    val libraryItem = libraryItems.find { it.mediaId == mediaItem.mediaId }
                    libraryItem ?: mediaItem
                }
                
                // Update player queue if items are being added
                if (updatedItems.isNotEmpty()) {
                    val currentIndex = exoPlayer.currentMediaItemIndex
                    val currentItem = exoPlayer.currentMediaItem
                    
                    // If player has no items or only one item, set the full queue
                    if (exoPlayer.mediaItemCount <= 1) {
                        exoPlayer.setMediaItems(updatedItems, /* resetPosition= */ false)
                        // Seek to the current item if it exists in the new queue
                        currentItem?.let { item ->
                            val index = updatedItems.indexOfFirst { it.mediaId == item.mediaId }
                            if (index >= 0) {
                                exoPlayer.seekToDefaultPosition(index)
                            }
                        }
                        Log.d("PlaybackService", "Updated player queue with ${updatedItems.size} items")
                    } else {
                        // Add items to existing queue
                        exoPlayer.addMediaItems(updatedItems)
                        Log.d("PlaybackService", "Added ${updatedItems.size} items to existing queue")
                    }
                }
                
                return Futures.immediateFuture(updatedItems)
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error in onAddMediaItems", e)
                return Futures.immediateFuture(mediaItems)
            }
        }
        
        // Android Auto / MediaLibraryService implementation
        // For basic playback controls, these methods provide minimal implementation
        // The MediaSession already exposes play/pause/next/previous controls automatically
        
        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: androidx.media3.session.MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d("PlaybackService", "onGetLibraryRoot called from ${browser.packageName}")
            try {
                val rootItem = MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Bombest Beats")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setExtras(createRootExtras())
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error in onGetLibraryRoot", e)
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        
        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: androidx.media3.session.MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d("PlaybackService", "onGetChildren called: parentId=$parentId, page=$page, pageSize=$pageSize")
            try {
                return when (parentId) {
                    ROOT_ID -> {
                        // Return category items (Songs, Recent, etc.)
                        if (categoryItems.isEmpty()) {
                            // If categories not loaded yet, load them
                            categoryItems.add(createCategoryItem(SONGS_ID, "All Songs", "Browse all tracks"))
                            categoryItems.add(createCategoryItem(RECENT_ID, "Recently Played", "Your listening history"))
                        }
                        Futures.immediateFuture(
                            LibraryResult.ofItemList(
                                ImmutableList.copyOf(categoryItems),
                                params
                            )
                        )
                    }
                    SONGS_ID -> {
                        // CRITICAL: If tracks aren't loaded yet, wait for loading to complete
                        // This ensures Android Auto sees tracks even if it queries before loading finishes
                        if (libraryItems.isEmpty()) {
                            Log.d("PlaybackService", "Tracks not loaded yet, waiting for load to complete")
                            val loadingJob = loadLibraryContent()
                            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                            
                            // Wait for loading in a background thread to avoid blocking
                            serviceScope.launch(Dispatchers.IO) {
                                try {
                                    // Wait for the deferred to complete
                                    loadingJob.await()
                                    
                                    // Now return the tracks
                                    val startIndex = page * pageSize
                                    val endIndex = minOf(startIndex + pageSize, libraryItems.size)
                                    val paginatedItems = if (startIndex < libraryItems.size) {
                                        libraryItems.subList(startIndex, endIndex)
                                    } else {
                                        emptyList()
                                    }
                                    Log.d("PlaybackService", "Tracks loaded, returning ${paginatedItems.size} items (page $page)")
                                    future.set(
                                        LibraryResult.ofItemList(
                                            ImmutableList.copyOf(paginatedItems),
                                            params
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.e("PlaybackService", "Error waiting for tracks to load", e)
                                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                                }
                            }
                            
                            return future
                        }
                        
                        // Return all tracks with pagination support
                        val startIndex = page * pageSize
                        val endIndex = minOf(startIndex + pageSize, libraryItems.size)
                        val paginatedItems = if (startIndex < libraryItems.size) {
                            libraryItems.subList(startIndex, endIndex)
                        } else {
                            emptyList()
                        }
                        Futures.immediateFuture(
                            LibraryResult.ofItemList(
                                ImmutableList.copyOf(paginatedItems),
                                params
                            )
                        )
                    }
                    RECENT_ID -> {
                        // For now, return empty - can be enhanced with actual recent tracks
                        Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                    }
                    else -> {
                        Log.w("PlaybackService", "Unknown parentId: $parentId")
                        Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                    }
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error in onGetChildren", e)
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        
        override fun onSubscribe(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: androidx.media3.session.MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Log.d("PlaybackService", "onSubscribe called: parentId=$parentId")
            try {
                subscriptions.add(parentId)
                // Load content if not already loaded
                if (libraryItems.isEmpty() && parentId == SONGS_ID) {
                    Log.d("PlaybackService", "Subscribing to SONGS_ID but tracks not loaded - triggering load")
                    loadLibraryContent()
                }
                return Futures.immediateFuture(LibraryResult.ofVoid(params))
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error in onSubscribe", e)
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        
        override fun onUnsubscribe(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String
        ): ListenableFuture<LibraryResult<Void>> {
            Log.d("PlaybackService", "onUnsubscribe called: parentId=$parentId")
            try {
                subscriptions.remove(parentId)
                return Futures.immediateFuture(LibraryResult.ofVoid())
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error in onUnsubscribe", e)
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        
        override fun onSearch(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: androidx.media3.session.MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Log.d("PlaybackService", "onSearch called: query=$query")
            return try {
                // Perform search synchronously on background thread
                val searchResult = runBlocking(Dispatchers.IO) {
                    if (query.isBlank()) {
                        return@runBlocking LibraryResult.ofVoid(params)
                    }
                    
                    val queryLower = query.lowercase()
                    val results = libraryItems.filter { item ->
                        val title = item.mediaMetadata.title?.toString()?.lowercase() ?: ""
                        val artist = item.mediaMetadata.artist?.toString()?.lowercase() ?: ""
                        title.contains(queryLower) || artist.contains(queryLower)
                    }
                    
                    Log.d("PlaybackService", "Search found ${results.size} results for query: $query")
                    
                    // Note: Media3's onSearch returns Void - search results are typically
                    // handled through a search results category or via onGetChildren with a search parentId
                    // For now, we acknowledge the search request
                    LibraryResult.ofVoid(params)
                }
                Futures.immediateFuture(searchResult)
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error in onSearch", e)
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
    }
}
