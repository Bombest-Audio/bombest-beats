package com.bombest.music

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bombest.music.data.DownloadManager
import com.bombest.music.data.authDataStore
import com.bombest.music.ui.screens.*
import com.bombest.music.ui.theme.BombestBeatsTheme
import com.bombest.music.ui.viewmodel.MainViewModel
import com.bombest.music.ui.viewmodel.PlaylistViewModel
import com.bombest.music.utils.BugReportManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.bombest.music.data.PasskeyManager
import com.bombest.music.data.repository.AuthRepository
import com.bombest.music.data.NetworkModule
import com.bombest.music.data.FavoritesManager
import com.bombest.music.data.api.Track
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import android.widget.Toast
import com.bombest.music.haptics.HapticGrooveEngine


enum class Screen {
    LIBRARY, PLAYLISTS, PLAYLIST_DETAIL, DASHBOARD, UPLOAD, ACCOUNT
}

/** Builds a MediaItem from an API Track for playlist playback. Optional artworkUri (e.g. playlist art) overrides track art. */
private fun createImagePart(context: android.content.Context, uri: Uri): MultipartBody.Part? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val body = bytes.toRequestBody("image/*".toMediaTypeOrNull(), 0, bytes.size)
            MultipartBody.Part.createFormData("image", "image.jpg", body)
        }
    } catch (e: Exception) {
        null
    }
}

private fun trackToMediaItem(
    track: Track,
    downloadManager: DownloadManager,
    artworkUri: Uri? = null
): MediaItem {
    val baseUrl = NetworkModule.getStreamBaseUrl()
    val streamUrl = "$baseUrl/stream/${track.id}"
    val tid = track.id.toString()
    val playUri = downloadManager.playbackUriForTrack(tid, streamUrl)
    val artUrl = artworkUri?.toString() ?: "$baseUrl/track/${track.id}/art"
    return MediaItem.Builder()
        .setMediaId(tid)
        .setUri(playUri)
        .setMimeType(downloadManager.mimeTypeForPlayback(tid, track.path))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album ?: "")
                .setArtworkUri(Uri.parse(artUrl))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()
}

class MainActivity : AppCompatActivity() {
    
    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var passkeyManager: PasskeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val authRepository = AuthRepository(this, NetworkModule.authApi)
        passkeyManager = PasskeyManager(this, authRepository)
        
        mainViewModel.initDownloadManager(this)
        
        HapticGrooveEngine.initialize(this)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        setContent {
            BombestBeatsTheme {
                MainContent(
                    viewModel = mainViewModel,
                    onShare = { title, artist -> shareTrack(title, artist) },
                    onLogout = {
                        DownloadManager.getInstance(this@MainActivity).setAuthToken(null)
                        mainViewModel.stop()
                        stopService(Intent(this@MainActivity, com.bombest.music.service.BombestMediaService::class.java))
                        lifecycleScope.launch {
                            authDataStore.edit { it.clear() }
                            val intent = Intent(this@MainActivity, LoginActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        }
                    },
                    onSMS = { title -> sendSMS(title) },
                    onRegisterPasskey = {
                        lifecycleScope.launch {
                            val result = passkeyManager.registerPasskey()
                            if (result.isSuccess) {
                                Toast.makeText(this@MainActivity, "Passkey registered!", Toast.LENGTH_SHORT).show()
                            } else {
                                val error = result.exceptionOrNull()?.message ?: "Registration failed"
                                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }
    }
    
    private fun shareTrack(title: String, artist: String) {
        val currentItem = mainViewModel.currentMediaItem.value ?: return
        val trackId = currentItem.mediaId
        val shareUrl = "https://bom.best/beats/track/$trackId"
        
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Check out \"$title\" by $artist on Bombest Beats!\n$shareUrl")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share Song"))
    }
    
    private fun sendSMS(title: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("sms:")
            putExtra("sms_body", "hey thomas, i'm interested in $title")
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    viewModel: MainViewModel,
    onShare: (title: String, artist: String) -> Unit,
    onLogout: () -> Unit,
    onSMS: (title: String) -> Unit,
    onRegisterPasskey: () -> Unit = {}
) {
    var isPlayerOpen by rememberSaveable { mutableStateOf(false) }
    var isMenuOpen by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf(Screen.LIBRARY) }
    var selectedPlaylistId by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val downloadManager = remember { DownloadManager.getInstance(context) }

    var showBugReportConfirm by remember { mutableStateOf(false) }
    var isBugReportRunning by remember { mutableStateOf(false) }
    var bugReportResult by remember { mutableStateOf<BugReportManager.Result?>(null) }
    
    val playlistViewModel: PlaylistViewModel = viewModel()

    LaunchedEffect(Unit) {
        playlistViewModel.userMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.onPermissionGranted()
    }
    
    LaunchedEffect(Unit) {
        viewModel.initializeController(context)
        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        playlistViewModel.initialize(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            Screen.LIBRARY -> {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    "bombest beats",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.pointerInput(Unit) {
                                        detectTapGestures(onLongPress = { showBugReportConfirm = true })
                                    }
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { 
                                    if (viewModel.canNavigateUp.value) {
                                        viewModel.navigateUp()
                                    } else {
                                        isMenuOpen = true 
                                    }
                                }) {
                                    if (viewModel.canNavigateUp.value) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                                    } else {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(0xFF15192A),
                                titleContentColor = Color.White
                            )
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        LibraryScreen(
                            playlist = viewModel.playlist,
                            currentMediaItem = viewModel.currentMediaItem.value,
                            onTrackClick = { item ->
                                if (item.mediaId == "playlists") {
                                    playlistViewModel.loadPlaylists()
                                    currentScreen = Screen.PLAYLISTS
                                } else if (item.mediaMetadata.isBrowsable == true) {
                                    viewModel.browseId(item.mediaId)
                                } else {
                                    viewModel.playMedia(item)
                                }
                            },
                            isRefreshing = viewModel.isRefreshing.value,
                            onRefresh = { viewModel.refreshLibrary() },
                            onDelete = { item -> viewModel.playlist.remove(item) },
                            libraryState = viewModel.libraryState.value,
                            playbackError = viewModel.playbackError.value,
                            onDismissError = { viewModel.playbackError.value = null }
                        )
                        
                        BackHandler(enabled = viewModel.canNavigateUp.value) {
                            viewModel.navigateUp()
                        }
                    }
                }
            }
            
            Screen.PLAYLISTS -> {
                PlaylistsScreen(
                    playlists = playlistViewModel.playlists,
                    isLoading = playlistViewModel.isLoading.value,
                    showCreateButton = playlistViewModel.currentUserId.value != null,
                    onCreatePlaylist = { name -> playlistViewModel.createPlaylist(name) },
                    onPlaylistClick = { id ->
                        selectedPlaylistId = id
                        val playlist = playlistViewModel.playlists.find { it.id == id }
                        playlistViewModel.loadPlaylistTracks(id, playlist?.name ?: "")
                        currentScreen = Screen.PLAYLIST_DETAIL
                    },
                    onPlaylistPlayClick = { playlist ->
                        scope.launch {
                            val tracks = playlistViewModel.getPlaylistTracksOnce(playlist.id)
                            if (tracks.isNotEmpty()) {
                                val playlistArtUri = playlist.art_url?.let { url -> Uri.parse(NetworkModule.getStreamBaseUrl() + url) }
                                val items = tracks.map { trackToMediaItem(it, downloadManager, playlistArtUri) }
                                viewModel.setPlaylistAndPlay(items)
                                isPlayerOpen = true
                            }
                        }
                    },
                    onDeletePlaylist = { id -> playlistViewModel.deletePlaylist(id) },
                    canDeletePlaylist = { pl ->
                        playlistViewModel.canEditPlaylist(pl) && !pl.is_system
                    },
                    onRefresh = { playlistViewModel.loadPlaylists() },
                    onBack = { currentScreen = Screen.LIBRARY }
                )
            }
            
            Screen.PLAYLIST_DETAIL -> {
                val currentPlaylist = playlistViewModel.playlists.find { it.id == selectedPlaylistId }
                val detailTracks = playlistViewModel.currentPlaylistTracks
                val canEditDetail = when {
                    currentPlaylist == null -> false
                    currentPlaylist.is_system -> playlistViewModel.userRole.value == "admin"
                    else -> playlistViewModel.canEditPlaylist(currentPlaylist)
                }
                PlaylistDetailScreen(
                    playlistId = selectedPlaylistId ?: 0,
                    playlistName = playlistViewModel.currentPlaylistName.value,
                    tracks = detailTracks,
                    allTracks = playlistViewModel.allTracks,
                    isLoading = playlistViewModel.isLoading.value,
                    canEdit = canEditDetail,
                    playlistArtUrl = currentPlaylist?.art_url,
                    onPlayAll = {
                        if (detailTracks.isNotEmpty()) {
                            val items = detailTracks.map { trackToMediaItem(it, downloadManager, currentPlaylist?.art_url?.let { url -> Uri.parse(NetworkModule.getStreamBaseUrl() + url) }) }
                            viewModel.setPlaylistAndPlay(items)
                            isPlayerOpen = true
                        }
                    },
                    onTrackClick = { track ->
                        if (detailTracks.isNotEmpty()) {
                            val playlistArtUri = currentPlaylist?.art_url?.let { url -> Uri.parse(NetworkModule.getStreamBaseUrl() + url) }
                            val items = detailTracks.map { trackToMediaItem(it, downloadManager, playlistArtUri) }
                            val index = detailTracks.indexOfFirst { it.id == track.id }
                            if (index >= 0) {
                                viewModel.setPlaylistAndPlayFrom(items, index)
                                isPlayerOpen = true
                            }
                        }
                    },
                    onRemoveTrack = { trackId ->
                        selectedPlaylistId?.let { playlistViewModel.removeTrackFromPlaylist(it, trackId) }
                    },
                    onAddTracks = { trackIds ->
                        selectedPlaylistId?.let { playlistViewModel.addTracksToPlaylist(it, trackIds) }
                    },
                    onImageSelected = { uri ->
                        val part = createImagePart(context, uri)
                        if (part != null && selectedPlaylistId != null) {
                            playlistViewModel.uploadPlaylistArt(selectedPlaylistId!!, part)
                        }
                    },
                    onSharePlaylist = {
                        selectedPlaylistId?.let { id ->
                            scope.launch {
                                val shareUrl = playlistViewModel.sharePlaylist(id)
                                shareUrl?.let { url ->
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Check out this playlist on Bombest Beats!\n$url")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Playlist"))
                                }
                            }
                        }
                    },
                    onRename = { newName ->
                        selectedPlaylistId?.let { playlistViewModel.renamePlaylist(it, newName) }
                    },
                    onReorderTrack = { fromIndex, direction ->
                        selectedPlaylistId?.let {
                            playlistViewModel.reorderTrack(it, fromIndex, direction)
                        }
                    },
                    onRefresh = {
                        selectedPlaylistId?.let { id ->
                            playlistViewModel.loadPlaylistTracks(id, playlistViewModel.currentPlaylistName.value)
                            playlistViewModel.refreshAllTracks()
                        }
                    },
                    onBack = {
                        playlistViewModel.loadPlaylists()
                        currentScreen = Screen.PLAYLISTS
                    }
                )
            }
            
            Screen.DASHBOARD -> {
                DashboardScreen(
                    onBack = { currentScreen = Screen.LIBRARY }
                )
            }
            
            Screen.UPLOAD -> {
                UploadScreen(
                    onBack = { currentScreen = Screen.LIBRARY }
                )
            }
            
            Screen.ACCOUNT -> {
                AccountScreen(
                    onBack = { currentScreen = Screen.LIBRARY },
                    onRegisterPasskey = onRegisterPasskey,
                    onRefreshLibrary = {
                        viewModel.refreshLibrary()
                        playlistViewModel.refreshAllTracks()
                    },
                    onSignOut = onLogout
                )
            }
        }
        
        if (isMenuOpen) {
            val bottomPaddingForMiniplayer = if (!isPlayerOpen && viewModel.currentMediaItem.value != null) 88.dp else 0.dp
            MenuOverlay(
                bottomPadding = bottomPaddingForMiniplayer,
                onDismiss = { isMenuOpen = false },
                onLibrary = {
                    isMenuOpen = false
                    currentScreen = Screen.LIBRARY
                },
                onPlaylists = {
                    isMenuOpen = false
                    playlistViewModel.loadPlaylists()
                    currentScreen = Screen.PLAYLISTS
                },
                onDashboard = {
                    isMenuOpen = false
                    currentScreen = Screen.DASHBOARD
                },
                onUpload = {
                    isMenuOpen = false
                    currentScreen = Screen.UPLOAD
                },
                onAccount = {
                    isMenuOpen = false
                    currentScreen = Screen.ACCOUNT
                },
                onLogout = {
                    isMenuOpen = false
                    onLogout()
                }
            )
        }
        
        AnimatedVisibility(
            visible = isPlayerOpen,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            viewModel.currentMediaItem.value?.let { mediaItem ->
                val favoriteTrackIds by FavoritesManager.favoritedTrackIds.collectAsState()
                val trackId = mediaItem.mediaId.toIntOrNull()
                val isFavorite = trackId?.let { favoriteTrackIds.contains(it) } ?: viewModel.favorites.value.contains(mediaItem.mediaId)
                
                PlayerScreen(
                    currentMediaItem = mediaItem,
                    isPlaying = viewModel.isPlaying.value,
                    onPlayPause = viewModel::playPause,
                    onNext = viewModel::skipNext,
                    onPrevious = viewModel::skipPrevious,
                    onShuffle = viewModel::toggleShuffle,
                    onRepeat = viewModel::toggleRepeat,
                    isShuffleEnabled = viewModel.isShuffleEnabled.value,
                    repeatMode = viewModel.repeatMode.value,
                    currentPosition = viewModel.currentPosition.value,
                    duration = viewModel.duration.value,
                    onSeek = viewModel::seekTo,
                    amplitudes = viewModel.visualizerAmplitudes,
                    isFavorite = isFavorite,
                    isDownloaded = viewModel.downloads.value.contains(mediaItem.mediaId),
                    onFavorite = viewModel::onFavorite,
                    onDownload = viewModel::onDownload,
                    onRemoveDownload = viewModel::onRemoveDownload,
                    onShare = {
                        val title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown"
                        val artist = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown"
                        onShare(title, artist)
                    },
                    onRegisterPasskey = onRegisterPasskey,
                    onClose = { isPlayerOpen = false },
                    loopStartMs = viewModel.loopStartMs.value,
                    loopEndMs = viewModel.loopEndMs.value,
                    bpm = viewModel.bpm.value,
                    onSetLoopStart = viewModel::setLoopStart,
                    onSetLoopEnd = viewModel::setLoopEnd,
                    onClearLoop = viewModel::clearLoop,
                    isBuffering = viewModel.isBuffering.value,
                    isCastConnected = viewModel.isCastConnected
                )
            }
        }
        
        if (!isPlayerOpen && viewModel.currentMediaItem.value != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                PlayerBar(
                    currentMediaItem = viewModel.currentMediaItem.value,
                    isPlaying = viewModel.isPlaying.value,
                    onPlayPause = viewModel::playPause,
                    onNext = viewModel::skipNext,
                    onPrevious = viewModel::skipPrevious,
                    onClick = { isPlayerOpen = true },
                    progress = if (viewModel.duration.value > 0)
                        viewModel.currentPosition.value.toFloat() / viewModel.duration.value.toFloat()
                    else 0f,
                    showCastButton = true
                )
            }
        }

        // Bug report dialogs
        if (showBugReportConfirm) {
            BugReportConfirmDialog(
                onConfirm = {
                    showBugReportConfirm = false
                    isBugReportRunning = true
                    scope.launch {
                        bugReportResult = BugReportManager.generate(context, view)
                        isBugReportRunning = false
                    }
                },
                onDismiss = { showBugReportConfirm = false }
            )
        }

        if (isBugReportRunning) {
            BugReportProgressDialog()
        }

        bugReportResult?.let { result ->
            BugReportResultDialog(
                result = result,
                onOpenIssue = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                onDismiss = { bugReportResult = null }
            )
        }
    }
}

@Composable
fun BugReportConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate Bug Report?") },
        text = { Text("Collects the last 500 lines of app logs and takes a screenshot, then files a GitHub issue.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Generate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun BugReportProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Generating Report…") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Collecting logs and taking screenshot…")
            }
        },
        confirmButton = {}
    )
}

@Composable
fun BugReportResultDialog(
    result: BugReportManager.Result,
    onOpenIssue: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result.issueUrl != null) "Bug Report Filed" else "Report Saved Locally") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.issueUrl != null) {
                    Text("GitHub issue created successfully.")
                } else {
                    Text("No GitHub token configured — logs saved locally only.")
                }
                result.screenshotFile?.let {
                    Text(
                        "Screenshot: ${it.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (result.issueUrl != null) {
                TextButton(onClick = { onOpenIssue(result.issueUrl) }) { Text("Open Issue") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (result.issueUrl != null) {
            { TextButton(onClick = onDismiss) { Text("Dismiss") } }
        } else null
    )
}

@Composable
fun MenuOverlay(
    bottomPadding: Dp = 0.dp,
    onDismiss: () -> Unit,
    onLibrary: () -> Unit,
    onPlaylists: () -> Unit,
    onDashboard: () -> Unit,
    onUpload: () -> Unit,
    onAccount: () -> Unit,
    onLogout: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF15192A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .padding(bottom = bottomPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Menu", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            MenuItem("Library", onClick = onLibrary)
            MenuItem("Playlists", onClick = onPlaylists)
            MenuItem("Dashboard", onClick = onDashboard)
            MenuItem("Upload", onClick = onUpload)
            MenuItem("Account Settings", onClick = onAccount)
            
            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A))
            ) {
                Text("Sign Out", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun MenuItem(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp)
    ) {
        Text(text = title, fontSize = 18.sp, color = Color.White)
    }
    Divider(color = Color(0xFF2A2A2A))
}
