package com.bombest.musify.viewmodels.libraryviewmodel

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bombest.musify.data.backend.BackendLibraryApi
import com.bombest.musify.data.download.DownloadManager
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.usecases.getCurrentlyPlayingTrackUseCase.GetCurrentlyPlayingTrackUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    application: Application,
    private val tracksRepository: TracksRepository,
    private val backendLibraryApi: BackendLibraryApi,
    val downloadManager: DownloadManager,
    getCurrentlyPlayingTrackUseCase: GetCurrentlyPlayingTrackUseCase
) : AndroidViewModel(application) {

    private val _tracks = MutableStateFlow<List<SearchResult.TrackSearchResult>>(emptyList())
    val tracks: StateFlow<List<SearchResult.TrackSearchResult>> = _tracks.asStateFlow()

    private val _uiState = mutableStateOf(LibraryUiState.LOADING)
    val uiState: State<LibraryUiState> = _uiState

    val currentlyPlayingTrack = getCurrentlyPlayingTrackUseCase.currentlyPlayingTrackStream

    init {
        loadTracks()
    }

    fun loadTracks() {
        viewModelScope.launch {
            _uiState.value = LibraryUiState.LOADING
            try {
                val allTracks = tracksRepository.getAllTracks()
                _tracks.value = allTracks
                _uiState.value = LibraryUiState.IDLE
            } catch (e: Exception) {
                _uiState.value = LibraryUiState.ERROR
            }
        }
    }

    fun refreshTracks() {
        (tracksRepository as? com.bombest.musify.data.repositories.tracksrepository.MusifyTracksRepository)?.clearCache()
        loadTracks()
    }

    fun isTrackDownloaded(trackId: String): Boolean {
        return downloadManager.isDownloaded(trackId)
    }

    fun downloadTrack(track: SearchResult.TrackSearchResult) {
        downloadManager.addToQueue(track)
    }

    fun downloadAllTracks() {
        viewModelScope.launch {
            val list = _tracks.value
            if (list.isNotEmpty()) {
                downloadManager.addAllToQueue(list)
            }
        }
    }

    fun cancelDownload(track: SearchResult.TrackSearchResult) {
        downloadManager.cancel(track)
    }

    fun retryDownload(track: SearchResult.TrackSearchResult) {
        downloadManager.retry(track)
    }

    private val _metadataUpdateMessage = MutableStateFlow<String?>(null)
    val metadataUpdateMessage: StateFlow<String?> = _metadataUpdateMessage.asStateFlow()

    fun clearMetadataUpdateMessage() {
        _metadataUpdateMessage.value = null
    }

    fun updateTrackMetadata(trackId: String, title: String, artist: String, album: String) {
        viewModelScope.launch {
            val id = trackId.toIntOrNull() ?: return@launch
            backendLibraryApi.updateTrack(id, title, artist, album).fold(
                onSuccess = {
                    (tracksRepository as? com.bombest.musify.data.repositories.tracksrepository.MusifyTracksRepository)?.clearCache()
                    loadTracks()
                    _metadataUpdateMessage.value = "Metadata updated"
                },
                onFailure = {
                    _metadataUpdateMessage.value = "Update failed: ${it.message}"
                }
            )
        }
    }

    enum class LibraryUiState {
        LOADING,
        IDLE,
        ERROR
    }
}
