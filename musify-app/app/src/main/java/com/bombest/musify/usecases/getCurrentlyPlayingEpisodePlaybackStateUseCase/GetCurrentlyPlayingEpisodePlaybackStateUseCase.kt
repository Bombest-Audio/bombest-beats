package com.bombest.musify.usecases.getCurrentlyPlayingEpisodePlaybackStateUseCase

import com.bombest.musify.domain.PodcastEpisode
import kotlinx.coroutines.flow.Flow

interface GetCurrentlyPlayingEpisodePlaybackStateUseCase {
    sealed interface PlaybackState {
        data class Playing(val playingEpisode: PodcastEpisode) : PlaybackState
        data class Paused(val pausedEpisode: PodcastEpisode) : PlaybackState
        object Loading : PlaybackState
        object Ended : PlaybackState
    }

    val currentlyPlayingEpisodePlaybackStateStream: Flow<PlaybackState>
}