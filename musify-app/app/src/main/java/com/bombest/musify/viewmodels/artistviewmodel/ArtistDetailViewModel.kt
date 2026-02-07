package com.bombest.musify.viewmodels.artistviewmodel

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.bombest.musify.data.download.DownloadManager
import com.bombest.musify.data.repositories.albumsrepository.AlbumsRepository
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.ui.navigation.MusifyNavigationDestinations
import com.bombest.musify.usecases.getCurrentlyPlayingTrackUseCase.GetCurrentlyPlayingTrackUseCase
import com.bombest.musify.usecases.getPlaybackLoadingStatusUseCase.GetPlaybackLoadingStatusUseCase
import com.bombest.musify.viewmodels.getCountryCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A sealed class hierarchy consisting of all UI states that are related to a screen
 * displaying the details of an artist.
 */
sealed class ArtistDetailScreenUiState {
    object Idle : ArtistDetailScreenUiState()
    object Loading : ArtistDetailScreenUiState()
    data class Error(private val message: String) : ArtistDetailScreenUiState()
}

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    albumsRepository: AlbumsRepository,
    getCurrentlyPlayingTrackUseCase: GetCurrentlyPlayingTrackUseCase,
    getPlaybackLoadingStatusUseCase: GetPlaybackLoadingStatusUseCase,
    private val tracksRepository: TracksRepository,
    val downloadManager: DownloadManager
) : AndroidViewModel(application) {

    private val _popularTracks = mutableStateOf<List<SearchResult.TrackSearchResult>>(emptyList())
    val popularTracks = _popularTracks as State<List<SearchResult.TrackSearchResult>>

    private val _uiState = mutableStateOf<ArtistDetailScreenUiState>(ArtistDetailScreenUiState.Idle)
    val uiState = _uiState as State<ArtistDetailScreenUiState>

    private val artistId =
        savedStateHandle.get<String>(MusifyNavigationDestinations.ArtistDetailScreen.NAV_ARG_ARTIST_ID)!!

    val currentlyPlayingTrackStream = getCurrentlyPlayingTrackUseCase.currentlyPlayingTrackStream

    val albumsOfArtistFlow = albumsRepository.getPaginatedStreamForAlbumsOfArtist(
        artistId = artistId,
        countryCode = getCountryCode()
    ).cachedIn(viewModelScope)

    init {
        viewModelScope.launch { fetchAndAssignPopularTracks() }
        getPlaybackLoadingStatusUseCase.loadingStatusStream.onEach { isPlaybackLoading ->
            if (isPlaybackLoading && _uiState.value !is ArtistDetailScreenUiState.Loading) {
                _uiState.value = ArtistDetailScreenUiState.Loading
                return@onEach
            }
            if (!isPlaybackLoading && _uiState.value is ArtistDetailScreenUiState.Loading) {
                _uiState.value = ArtistDetailScreenUiState.Idle
                return@onEach
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun fetchAndAssignPopularTracks() {
        _uiState.value = ArtistDetailScreenUiState.Loading
        val fetchResult = tracksRepository.fetchTopTenTracksForArtistWithId(
            artistId = artistId,
            countryCode = getCountryCode()
        )
        when (fetchResult) {
            is FetchedResource.Failure -> {
                _uiState.value =
                    ArtistDetailScreenUiState.Error("Error loading tracks, please check internet connection")
            }
            is FetchedResource.Success -> {
                _popularTracks.value = fetchResult.data
                _uiState.value = ArtistDetailScreenUiState.Idle
            }
        }
    }

    fun downloadTrack(track: SearchResult.TrackSearchResult) {
        downloadManager.addToQueue(track)
    }

    fun downloadAllTracks() {
        val list = _popularTracks.value
        if (list.isNotEmpty()) {
            downloadManager.addAllToQueue(list)
        }
    }

    fun cancelDownload(track: SearchResult.TrackSearchResult) {
        downloadManager.cancel(track)
    }

    fun retryDownload(track: SearchResult.TrackSearchResult) {
        downloadManager.retry(track)
    }
}