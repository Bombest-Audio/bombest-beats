package com.bombest.musify.di

import com.bombest.musify.data.repositories.albumsrepository.AlbumsRepository
import com.bombest.musify.data.repositories.albumsrepository.MusifyAlbumsRepository
import com.bombest.musify.data.repositories.genresrepository.GenresRepository
import com.bombest.musify.data.repositories.genresrepository.MusifyGenresRepository
import com.bombest.musify.data.repositories.homefeedrepository.HomeFeedRepository
import com.bombest.musify.data.repositories.homefeedrepository.MusifyHomeFeedRepository
import com.bombest.musify.data.repositories.playlistrepository.MusifyPlaylistRepository
import com.bombest.musify.data.repositories.playlistrepository.PlaylistRepository
import com.bombest.musify.data.repositories.podcastsrepository.MusifyPodcastsRepository
import com.bombest.musify.data.repositories.podcastsrepository.PodcastsRepository
import com.bombest.musify.data.repositories.searchrepository.MusifySearchRepository
import com.bombest.musify.data.repositories.searchrepository.SearchRepository
import com.bombest.musify.data.repositories.tracksrepository.MusifyTracksRepository
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(ViewModelComponent::class, SingletonComponent::class)
abstract class MusicRepositoriesModule {
    @Binds
    abstract fun bindTracksRepository(impl: MusifyTracksRepository): TracksRepository

    @Binds
    abstract fun bindAlbumsRepository(impl: MusifyAlbumsRepository): AlbumsRepository

    @Binds
    abstract fun bindGeneresRepository(impl: MusifyGenresRepository): GenresRepository

    @Binds
    abstract fun bindSearchRepository(impl: MusifySearchRepository): SearchRepository

    @Binds
    abstract fun bindHomeFeedRepository(impl: MusifyHomeFeedRepository): HomeFeedRepository

    @Binds
    abstract fun bindPlaylistRepository(impl: MusifyPlaylistRepository): PlaylistRepository

    @Binds
    abstract fun bindPodcastsRepository(impl: MusifyPodcastsRepository): PodcastsRepository
}