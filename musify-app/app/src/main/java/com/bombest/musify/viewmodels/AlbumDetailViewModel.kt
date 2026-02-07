package com.bombest.musify.viewmodels

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bombest.musify.data.download.DownloadManager
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.ui.navigation.MusifyNavigationDestinations
import com.bombest.musify.usecases.getCurrentlyPlayingTrackUseCase.GetCurrentlyPlayingTrackUseCase
import com.bombest.musify.usecases.getPlaybackLoadingStatusUseCase.GetPlaybackLoadingStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AlbumDetailUiState {
    object Idle : AlbumDetailUiState()
    object Loading : AlbumDetailUiState()
    data class Error(private val message: String) : AlbumDetailUiState()
}

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    getCurrentlyPlayingTrackUseCase: GetCurrentlyPlayingTrackUseCase,
    getPlaybackLoadingStatusUseCase: GetPlaybackLoadingStatusUseCase,
    private val tracksRepository: TracksRepository,
    val downloadManager: DownloadManager
) : AndroidViewModel(application) {

    private val _tracks = mutableStateOf<List<SearchResult.TrackSearchResult>>(emptyList())
    val tracks = _tracks as State<List<SearchResult.TrackSearchResult>>

    private val _uiState = mutableStateOf<AlbumDetailUiState>(AlbumDetailUiState.Idle)
    val uiState = _uiState as State<AlbumDetailUiState>

    private val albumId =
        savedStateHandle.get<String>(MusifyNavigationDestinations.AlbumDetailScreen.NAV_ARG_ALBUM_ID)!!
    val currentlyPlayingTrackStream = getCurrentlyPlayingTrackUseCase.currentlyPlayingTrackStream

    init {
        fetchAndAssignTrackList()
        getPlaybackLoadingStatusUseCase
            .loadingStatusStream
            .onEach { isPlaybackLoading ->
                if (isPlaybackLoading && _uiState.value !is AlbumDetailUiState.Loading) {
                    _uiState.value = AlbumDetailUiState.Loading
                    return@onEach
                }
                if (!isPlaybackLoading && _uiState.value is AlbumDetailUiState.Loading) {
                    _uiState.value = AlbumDetailUiState.Idle
                    return@onEach
                }
            }.launchIn(viewModelScope)
    }

    private fun fetchAndAssignTrackList() {
        viewModelScope.launch {
            _uiState.value = AlbumDetailUiState.Loading
            val result = tracksRepository.fetchTracksForAlbumWithId(
                albumId = albumId,
                countryCode = getCountryCode()
            )
            if (result is FetchedResource.Success) {
                _tracks.value = result.data
                _uiState.value = AlbumDetailUiState.Idle
            } else {
                _uiState.value =
                    AlbumDetailUiState.Error("Unable to fetch tracks. Please check internet connection.")
            }
        }
    }

    fun downloadTrack(track: SearchResult.TrackSearchResult) {
        downloadManager.addToQueue(track)
    }

    fun downloadAllTracks() {
        val list = _tracks.value
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