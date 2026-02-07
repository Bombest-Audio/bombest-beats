package com.bombest.musify.data.repositories.podcastsrepository

import androidx.paging.PagingData
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.MusifyErrorType
import com.bombest.musify.domain.PodcastEpisode
import com.bombest.musify.domain.PodcastShow
import kotlinx.coroutines.flow.Flow

/**
 * A repository that contains all methods related to podcasts.
 */
interface PodcastsRepository {
    suspend fun fetchPodcastEpisode(
        episodeId: String,
        countryCode: String
    ): FetchedResource<PodcastEpisode, MusifyErrorType>

    suspend fun fetchPodcastShow(
        showId: String,
        countryCode: String
    ): FetchedResource<PodcastShow, MusifyErrorType>
    
    fun getPodcastEpisodesStreamForPodcastShow(
        showId: String,
        countryCode: String
    ): Flow<PagingData<PodcastEpisode>>
}