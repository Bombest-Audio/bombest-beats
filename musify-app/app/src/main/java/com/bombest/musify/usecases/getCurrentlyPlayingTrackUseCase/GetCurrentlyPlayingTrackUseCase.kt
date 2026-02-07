package com.bombest.musify.usecases.getCurrentlyPlayingTrackUseCase

import com.bombest.musify.domain.SearchResult
import kotlinx.coroutines.flow.Flow

interface GetCurrentlyPlayingTrackUseCase {
    val currentlyPlayingTrackStream:Flow<SearchResult.TrackSearchResult?>
}