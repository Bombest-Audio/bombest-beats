package com.bombest.musify.ui.activities

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.bombest.musify.domain.Streamable
import com.bombest.musify.ui.navigation.MusifyBottomNavigationConnectedWithBackStack
import com.bombest.musify.ui.navigation.MusifyBottomNavigationDestinations
import com.bombest.musify.ui.navigation.MusifyNavigation
import com.bombest.musify.ui.screens.homescreen.ExpandableMiniPlayerWithSnackbar
import com.bombest.musify.ui.theme.BombestBeatsTheme
import com.bombest.musify.viewmodels.PlaybackViewModel
import dagger.hilt.android.AndroidEntryPoint

@ExperimentalAnimationApi
@ExperimentalMaterialApi
@ExperimentalComposeUiApi
@ExperimentalFoundationApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            BombestBeatsTheme {
                Surface(modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                    content = { MusifyApp() })
            }
        }
    }
}

@ExperimentalAnimationApi
@ExperimentalFoundationApi
@ExperimentalComposeUiApi
@ExperimentalMaterialApi
@Composable
private fun MusifyApp() {
    val playbackViewModel = hiltViewModel<PlaybackViewModel>()
    val playbackState by playbackViewModel.playbackState
    val snackbarHostState = remember { SnackbarHostState() }
    val playbackEvent: PlaybackViewModel.Event? by playbackViewModel.playbackEventsFlow.collectAsState(
        initial = null
    )
    val miniPlayerStreamable = remember(playbackState) {
        playbackState.currentlyPlayingStreamable ?: playbackState.previouslyPlayingStreamable
    }
    var isNowPlayingScreenVisible by rememberSaveable { mutableStateOf(false) }
    
    // Track previous playing streamable to detect new track starts
    var previousPlayingStreamable by remember { mutableStateOf<Streamable?>(null) }
    
    // Automatically open full screen player when a new track starts playing
    LaunchedEffect(playbackState) {
        val currentlyPlaying = playbackState.currentlyPlayingStreamable
        if (playbackState is PlaybackViewModel.PlaybackState.Playing && 
            currentlyPlaying != null && 
            currentlyPlaying != previousPlayingStreamable) {
            // New track started - open full screen player
            isNowPlayingScreenVisible = true
            previousPlayingStreamable = currentlyPlaying
        } else if (currentlyPlaying != null) {
            // Update previous playing streamable even if not opening (for next track detection)
            previousPlayingStreamable = currentlyPlaying
        }
    }
    
    // Observe shuffle and repeat state reactively
    val isShuffled by playbackViewModel.isShuffledFlow.collectAsState()
    val repeatMode by playbackViewModel.repeatModeFlow.collectAsState()
    LaunchedEffect(key1 = playbackEvent) {
        if (playbackEvent !is PlaybackViewModel.Event.PlaybackError) return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = (playbackEvent as PlaybackViewModel.Event.PlaybackError).errorMessage,
        )
    }
    val isPlaybackPaused = remember(playbackState) {
        playbackState is PlaybackViewModel.PlaybackState.Paused || playbackState is PlaybackViewModel.PlaybackState.PlaybackEnded
    }

    BackHandler(isNowPlayingScreenVisible) {
        isNowPlayingScreenVisible = false
    }
    val bottomNavigationItems = remember {
        listOf(
            MusifyBottomNavigationDestinations.Home,
            MusifyBottomNavigationDestinations.Search,
            MusifyBottomNavigationDestinations.Library,
            MusifyBottomNavigationDestinations.Premium
        )
    }
    val navController = rememberNavController()
    Box(modifier = Modifier.fillMaxSize()) {
        // the playbackState.currentlyPlayingTrack will automatically be set
        // to null when the playback is stopped
        MusifyNavigation(
            navController = navController,
            playStreamable = playbackViewModel::playStreamable,
            isFullScreenNowPlayingOverlayScreenVisible = isNowPlayingScreenVisible,
            onPausePlayback = playbackViewModel::pauseCurrentlyPlayingTrack
        )
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            AnimatedContent(
                modifier = Modifier.fillMaxWidth(),
                targetState = miniPlayerStreamable != null
            ) { hasTrack ->
                if (!hasTrack) {
                    SnackbarHost(hostState = snackbarHostState)
                } else {
                    ExpandableMiniPlayerWithSnackbar(
                        modifier = Modifier
                            .animateEnterExit(
                                enter = fadeIn() + slideInVertically { it },
                                exit = fadeOut() + slideOutVertically { -it }
                            ),
                        streamable = miniPlayerStreamable!!,
                        onPauseButtonClicked = playbackViewModel::pauseCurrentlyPlayingTrack,
                        onPlayButtonClicked = playbackViewModel::resumeIfPausedOrPlay,
                        isPlaybackPaused = isPlaybackPaused,
                        timeElapsedStringFlow = playbackViewModel.flowOfProgressTextOfCurrentTrack.value,
                        playbackProgressFlow = playbackViewModel.flowOfProgressOfCurrentTrack.value,
                        totalDurationOfCurrentTrackText = playbackViewModel.totalDurationOfCurrentTrackTimeText.value,
                        onSeekTo = playbackViewModel::seekTo,
                        onSkipNext = playbackViewModel::skipToNext,
                        onSkipPrevious = playbackViewModel::skipToPrevious,
                        onShuffle = playbackViewModel::toggleShuffle,
                        onRepeat = playbackViewModel::toggleRepeat,
                        isShuffled = isShuffled,
                        repeatMode = repeatMode,
                        isNowPlayingScreenVisible = isNowPlayingScreenVisible,
                        onNowPlayingScreenVisibilityChanged = { isNowPlayingScreenVisible = it },
                        snackbarHostState = snackbarHostState,
                        onScrubStart = playbackViewModel::startScrubbing,
                        onScrubProgress = playbackViewModel::updateScrubPosition,
                        onScrubEnd = playbackViewModel::endScrubbing,
                        audioSessionId = playbackViewModel.audioSessionId.takeIf { it > 0 }
                    )
                }
            }

            MusifyBottomNavigationConnectedWithBackStack(
                navController = navController,
                modifier = Modifier.navigationBarsPadding(),
                navigationItems = bottomNavigationItems,
            )
        }
    }
}

