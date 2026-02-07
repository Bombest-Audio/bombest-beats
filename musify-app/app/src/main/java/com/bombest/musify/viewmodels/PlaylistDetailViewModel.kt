package com.bombest.musify.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.bombest.musify.data.download.DownloadManager
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.ui.navigation.MusifyNavigationDestinations
import com.bombest.musify.usecases.getCurrentlyPlayingTrackUseCase.GetCurrentlyPlayingTrackUseCase
import com.bombest.musify.usecases.getPlaybackLoadingStatusUseCase.GetPlaybackLoadingStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    tracksRepository: TracksRepository,
    getCurrentlyPlayingTrackUseCase: GetCurrentlyPlayingTrackUseCase,
    getPlaybackLoadingStatusUseCase: GetPlaybackLoadingStatusUseCase,
    val downloadManager: DownloadManager
) : AndroidViewModel(application) {
    private val playlistId =
        savedStateHandle.get<String>(MusifyNavigationDestinations.PlaylistDetailScreen.NAV_ARG_PLAYLIST_ID)!!
    val playbackLoadingStateStream = getPlaybackLoadingStatusUseCase.loadingStatusStream
    val currentlyPlayingTrackStream = getCurrentlyPlayingTrackUseCase.currentlyPlayingTrackStream
    val tracks = tracksRepository.getPaginatedStreamForPlaylistTracks(
        playlistId = playlistId,
        countryCode = getCountryCode()
    ).cachedIn(viewModelScope)

    fun downloadTrack(track: SearchResult.TrackSearchResult) {
        downloadManager.addToQueue(track)
    }

    suspend fun downloadAllTracks(loadedTracks: List<SearchResult.TrackSearchResult>) {
        if (loadedTracks.isNotEmpty()) {
            downloadManager.addAllToQueue(loadedTracks)
        }
    }

    fun cancelDownload(track: SearchResult.TrackSearchResult) {
        downloadManager.cancel(track)
    }

    fun retryDownload(track: SearchResult.TrackSearchResult) {
        downloadManager.retry(track)
    }
}