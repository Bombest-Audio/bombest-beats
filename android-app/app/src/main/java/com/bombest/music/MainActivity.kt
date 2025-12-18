package com.bombest.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bombest.music.data.authDataStore
import com.bombest.music.ui.screens.*
import com.bombest.music.ui.theme.BombestBeatsTheme
import com.bombest.music.ui.viewmodel.MainViewModel
import com.bombest.music.ui.viewmodel.PlaylistViewModel
import kotlinx.coroutines.launch
import com.bombest.music.data.PasskeyManager
import com.bombest.music.data.repository.AuthRepository
import com.bombest.music.data.NetworkModule
import com.bombest.music.data.FavoritesManager
import android.widget.Toast


enum class Screen {
    LIBRARY, PLAYLISTS, PLAYLIST_DETAIL, DASHBOARD, UPLOAD, ACCOUNT
}

class MainActivity : ComponentActivity() {
    
    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var passkeyManager: PasskeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize PasskeyManager
        val authRepository = AuthRepository(this, NetworkModule.authApi)
        passkeyManager = PasskeyManager(this, authRepository)
        
        // Initialize DownloadManager
        mainViewModel.initDownloadManager(this)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        setContent {
            BombestBeatsTheme {
                MainContent(
                    viewModel = mainViewModel,
                    onShare = { title, artist -> shareTrack(title, artist) },
                    onLogout = {
                        // Stop playback first
                        mainViewModel.stop()
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
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val playlistViewModel: PlaylistViewModel = viewModel()
    
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
                            title = { Text("bombest beats", fontWeight = FontWeight.Bold) },
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
                                    // Switch to Playlists screen for rich UI
                                    playlistViewModel.loadPlaylists()
                                    currentScreen = Screen.PLAYLISTS
                                } else if (item.mediaMetadata.isBrowsable == true) {
                                    // Browse folder
                                    viewModel.browseId(item.mediaId)
                                } else {
                                    // Play track
                                    viewModel.playMedia(item) 
                                }
                            },
                            isRefreshing = viewModel.isRefreshing.value,
                            onRefresh = { viewModel.refreshLibrary() },
                            onDelete = { item -> viewModel.playlist.remove(item) }
                        )
                        
                        // Handle back button for library navigation
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
                    onCreatePlaylist = { name -> playlistViewModel.createPlaylist(name) },
                    onPlaylistClick = { id ->
                        selectedPlaylistId = id
                        val playlist = playlistViewModel.playlists.find { it.id == id }
                        playlistViewModel.loadPlaylistTracks(id, playlist?.name ?: "")
                        currentScreen = Screen.PLAYLIST_DETAIL
                    },
                    onDeletePlaylist = { id -> playlistViewModel.deletePlaylist(id) },
                    onBack = { currentScreen = Screen.LIBRARY }
                )
            }
            
            Screen.PLAYLIST_DETAIL -> {
                PlaylistDetailScreen(
                    playlistId = selectedPlaylistId ?: 0,
                    playlistName = playlistViewModel.currentPlaylistName.value,
                    tracks = playlistViewModel.currentPlaylistTracks,
                    allTracks = playlistViewModel.allTracks,  // Use ViewModel's allTracks from API
                    isLoading = playlistViewModel.isLoading.value,
                    onTrackClick = { track ->
                        // TODO: Play track from playlist
                    },
                    onRemoveTrack = { trackId ->
                        selectedPlaylistId?.let { playlistViewModel.removeTrackFromPlaylist(it, trackId) }
                    },
                    onAddTracks = { trackIds ->
                        selectedPlaylistId?.let { playlistViewModel.addTracksToPlaylist(it, trackIds) }
                    },
                    onBack = { currentScreen = Screen.PLAYLISTS }
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
                    onRegisterPasskey = onRegisterPasskey
                )
            }
        }
        
        if (isMenuOpen) {
            MenuOverlay(
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
                // Observe FavoritesManager state reactively
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
                    onClose = { isPlayerOpen = false }
                )
            }
        }
        
        // Global Mini Player - appears on all screens except fullscreen player
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
                    else 0f
                )
            }
        }
    }
}

@Composable
fun MenuOverlay(
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
            modifier = Modifier.fillMaxSize().padding(24.dp)
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
