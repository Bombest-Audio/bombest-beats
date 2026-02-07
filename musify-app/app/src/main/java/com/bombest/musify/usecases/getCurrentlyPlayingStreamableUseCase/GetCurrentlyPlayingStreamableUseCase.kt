package com.bombest.musify.usecases.getCurrentlyPlayingStreamableUseCase

import com.bombest.musify.domain.Streamable
import kotlinx.coroutines.flow.Flow

interface GetCurrentlyPlayingStreamableUseCase {
    val currentlyPlayingStreamableStream: Flow<Streamable>
}