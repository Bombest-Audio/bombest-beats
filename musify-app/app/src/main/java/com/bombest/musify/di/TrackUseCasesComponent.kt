package com.bombest.musify.di

import com.bombest.musify.usecases.downloadDrawableFromUrlUseCase.DownloadDrawableFromUrlUseCase
import com.bombest.musify.usecases.downloadDrawableFromUrlUseCase.MusifyDownloadDrawableFromUrlUseCase
import com.bombest.musify.usecases.getCurrentlyPlayingTrackUseCase.GetCurrentlyPlayingTrackUseCase
import com.bombest.musify.usecases.getCurrentlyPlayingTrackUseCase.MusifyGetCurrentlyPlayingTrackUseCase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
abstract class TrackUseCasesComponent {
    @Binds
    abstract fun bindDownloadDrawableFromUrlUseCase(
        impl: MusifyDownloadDrawableFromUrlUseCase
    ): DownloadDrawableFromUrlUseCase

    @Binds
    abstract fun bindGetCurrentlyPlayingTrackUseCase(
        impl: MusifyGetCurrentlyPlayingTrackUseCase
    ): GetCurrentlyPlayingTrackUseCase
}