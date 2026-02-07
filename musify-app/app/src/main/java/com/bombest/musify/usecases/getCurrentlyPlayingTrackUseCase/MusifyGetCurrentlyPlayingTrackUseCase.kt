package com.bombest.musify.usecases.getCurrentlyPlayingTrackUseCase

import com.bombest.musify.domain.SearchResult
import com.bombest.musify.usecases.getCurrentlyPlayingStreamableUseCase.GetCurrentlyPlayingStreamableUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import javax.inject.Inject

class MusifyGetCurrentlyPlayingTrackUseCase @Inject constructor(
    getCurrentlyPlayingStreamableUseCase: GetCurrentlyPlayingStreamableUseCase
) : GetCurrentlyPlayingTrackUseCase {
    @Suppress("RemoveExplicitTypeArguments")
    override val currentlyPlayingTrackStream: Flow<SearchResult.TrackSearchResult> =
        getCurrentlyPlayingStreamableUseCase
            .currentlyPlayingStreamableStream
            .filterIsInstance<SearchResult.TrackSearchResult>()
}