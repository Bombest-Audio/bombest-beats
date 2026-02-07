package com.bombest.musify.ui.screens.libraryscreen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.ui.components.DefaultMusifyErrorMessage
import com.bombest.musify.ui.components.DefaultMusifyLoadingAnimation
import com.bombest.musify.ui.components.EditMetadataDialog
import com.bombest.musify.ui.components.MusifyBottomNavigationConstants
import com.bombest.musify.ui.components.MusifyCompactTrackCard
import com.bombest.musify.ui.components.MusifyMiniPlayerConstants
import com.bombest.musify.viewmodels.libraryviewmodel.LibraryViewModel

@ExperimentalFoundationApi
@ExperimentalMaterialApi
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    currentlyPlayingTrack: SearchResult.TrackSearchResult?,
    onTrackClicked: (SearchResult.TrackSearchResult) -> Unit
) {
    val tracks by viewModel.tracks.collectAsState()
    val uiState by viewModel.uiState
    val currentlyPlaying by viewModel.currentlyPlayingTrack.collectAsState(initial = null)
    var trackToEdit by remember { mutableStateOf<SearchResult.TrackSearchResult?>(null) }
    val metadataMessage by viewModel.metadataUpdateMessage.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(metadataMessage) {
        metadataMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMetadataUpdateMessage()
        }
    }

    trackToEdit?.let { track ->
        EditMetadataDialog(
            track = track,
            onDismiss = { trackToEdit = null },
            onSave = { title, artist, album ->
                val backendId = track.backendTrackId?.toString() ?: track.id
                viewModel.updateTrackMetadata(backendId, title, artist, album)
                trackToEdit = null
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        when (uiState) {
            LibraryViewModel.LibraryUiState.LOADING -> {
                DefaultMusifyLoadingAnimation(
                    modifier = Modifier.align(Alignment.Center),
                    isVisible = true
                )
            }
            LibraryViewModel.LibraryUiState.ERROR -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DefaultMusifyErrorMessage(
                        title = "Oops! Something doesn't look right",
                        subtitle = "Please check the internet connection",
                        onRetryButtonClicked = { viewModel.refreshTracks() }
                    )
                }
            }
            LibraryViewModel.LibraryUiState.IDLE -> {
                if (tracks.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No tracks found",
                            style = MaterialTheme.typography.h6,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Check your S3 bucket configuration",
                            style = MaterialTheme.typography.subtitle2
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = MusifyBottomNavigationConstants.navigationHeight + MusifyMiniPlayerConstants.miniPlayerHeight
                        )
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Library",
                                    style = MaterialTheme.typography.h5,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { viewModel.refreshTracks() }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh"
                                    )
                                }
                            }
                        }
                        items(tracks) { track ->
                            val isCurrentlyPlaying = track.id == currentlyPlaying?.id
                            MusifyCompactTrackCard(
                                track = track,
                                onClick = onTrackClicked,
                                isLoadingPlaceholderVisible = false,
                                isCurrentlyPlaying = isCurrentlyPlaying,
                                isAlbumArtVisible = true,
                                subtitleTextStyle = LocalTextStyle.current.copy(
                                    fontWeight = FontWeight.Thin,
                                    color = MaterialTheme.colors.onBackground.copy(
                                        alpha = ContentAlpha.disabled
                                    )
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                downloadManager = viewModel.downloadManager,
                                onDownloadClick = { viewModel.downloadTrack(track) },
                                onCancelDownload = { viewModel.cancelDownload(track) },
                                onRetryDownload = { viewModel.retryDownload(track) },
                                onEditMetadataClick = { t -> if (t.backendTrackId != null) trackToEdit = t }
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
