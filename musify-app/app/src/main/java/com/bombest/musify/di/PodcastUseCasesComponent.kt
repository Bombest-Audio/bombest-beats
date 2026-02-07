package com.bombest.musify.di

import com.bombest.musify.usecases.getCurrentlyPlayingEpisodePlaybackStateUseCase.GetCurrentlyPlayingEpisodePlaybackStateUseCase
import com.bombest.musify.usecases.getCurrentlyPlayingEpisodePlaybackStateUseCase.MusifyGetCurrentlyPlayingEpisodePlaybackStateUseCase
import com.bombest.musify.usecases.getCurrentlyPlayingStreamableUseCase.GetCurrentlyPlayingStreamableUseCase
import com.bombest.musify.usecases.getCurrentlyPlayingStreamableUseCase.MusifyGetCurrentlyPlayingStreamableUseCase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
abstract class PodcastUseCasesComponent {
    @Binds
    abstract fun bindGetCurrentlyPlayingStreamableUseCase(
        impl: MusifyGetCurrentlyPlayingStreamableUseCase
    ): GetCurrentlyPlayingStreamableUseCase

    @Binds
    abstract fun bindGetEpisodePlaybackStateUseCase(
        impl: MusifyGetCurrentlyPlayingEpisodePlaybackStateUseCase
    ): GetCurrentlyPlayingEpisodePlaybackStateUseCase
}