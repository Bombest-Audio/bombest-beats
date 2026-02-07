package com.bombest.musify.ui.screens.homescreen

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bombest.musify.domain.Streamable
import com.bombest.musify.ui.components.MusifyMiniPlayer
import com.bombest.musify.ui.screens.NowPlayingScreen
import com.bombest.musify.viewmodels.PlaybackViewModel
import kotlinx.coroutines.flow.Flow

/**
 * A mini player that can expand to fill the entire screen when clicked.
 * This composable also contains a snack bar. The snack bar will be
 * displayed on top of the mini player if it is collapsed. If the
 * mine player is expanded, then the snack bar will be displayed
 * at the bottom of the screen.
 *
 * @param streamable the [Streamable] to be displayed.
 * @param onPauseButtonClicked the lambda to execute when the pause button
 * is clicked.
 * @param onPlayButtonClicked the lambda to execute when the play button
 * is clicked.
 * @param isPlaybackPaused indicates whether the playback is paused. Based on
 * this, the play/pause button will be shown.
 * @param timeElapsedStringFlow a [Flow] that emits a stream of strings
 * that represent the time elapsed.
 * @param playbackProgressFlow a [Flow] that emits the current playback progress.
 * @param totalDurationOfCurrentTrackText represents the total duration of the
 * currently playing track as a string.
 * @param modifier the modifier to be applied to the composable.
 * @param snackbarHostState the [SnackbarHostState] that will be used for
 * handing the snackbar used in this composable.
 */
@ExperimentalAnimationApi
@Composable
fun ExpandableMiniPlayerWithSnackbar(
    streamable: Streamable,
    onPauseButtonClicked: () -> Unit,
    onPlayButtonClicked: (Streamable) -> Unit,
    isPlaybackPaused: Boolean,
    timeElapsedStringFlow: Flow<String>,
    playbackProgressFlow: Flow<Float>,
    totalDurationOfCurrentTrackText: String,
    onSeekTo: (Float) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    isShuffled: Boolean,
    repeatMode: com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode,
    isNowPlayingScreenVisible: Boolean,
    onNowPlayingScreenVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onScrubStart: (() -> Unit)? = null,
    onScrubProgress: ((Float) -> Unit)? = null,
    onScrubEnd: (() -> Unit)? = null,
    audioSessionId: Int? = null
) {
    AnimatedContent(
        modifier = modifier,
        targetState = isNowPlayingScreenVisible,
        transitionSpec = {
            slideInVertically(animationSpec = tween()) with shrinkOut(animationSpec = tween())
        }
    ) { isFullScreenVisible ->
        if (isFullScreenVisible) {
            Box {
                NowPlayingScreen(
                    streamable = streamable,
                    isPlaybackPaused = isPlaybackPaused,
                    timeElapsedStringFlow = timeElapsedStringFlow,
                    totalDurationOfCurrentTrackProvider = { totalDurationOfCurrentTrackText },
                    playbackDurationRange = PlaybackViewModel.PLAYBACK_PROGRESS_RANGE,
                    playbackProgressFlow = playbackProgressFlow,
                    onCloseButtonClicked = { onNowPlayingScreenVisibilityChanged(false) },
                    onShuffleButtonClicked = onShuffle,
                    onSkipPreviousButtonClicked = onSkipPrevious,
                    onPlayButtonClicked = { onPlayButtonClicked(streamable) },
                    onPauseButtonClicked = onPauseButtonClicked,
                    onSkipNextButtonClicked = onSkipNext,
                    onRepeatButtonClicked = onRepeat,
                    onSliderValueChange = onSeekTo,
                    isShuffled = isShuffled,
                    repeatMode = repeatMode,
                    onScrubStart = onScrubStart,
                    onScrubProgress = onScrubProgress,
                    onScrubEnd = onScrubEnd,
                    audioSessionId = audioSessionId
                )
                SnackbarHost(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                    hostState = snackbarHostState
                )
            }

        } else {
            Column {
                SnackbarHost(
                    modifier = Modifier.padding(8.dp),
                    hostState = snackbarHostState
                )
                MusifyMiniPlayer(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { onNowPlayingScreenVisibilityChanged(true) },
                    isPlaybackPaused = isPlaybackPaused,
                    streamable = streamable,
                    onPlayButtonClicked = { onPlayButtonClicked(streamable) },
                    onPauseButtonClicked = onPauseButtonClicked
                )
            }
        }
    }
}

