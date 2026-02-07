package com.bombest.musify.data.repositories.podcastsrepository

import androidx.paging.PagingData
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.MusifyErrorType
import com.bombest.musify.domain.PodcastEpisode
import com.bombest.musify.domain.PodcastShow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * PodcastsRepository implementation for S3
 * Podcasts are not available in S3 bucket, so all methods return empty/error
 */
class MusifyPodcastsRepository @Inject constructor() : PodcastsRepository {

    override suspend fun fetchPodcastEpisode(
        episodeId: String,
        countryCode: String
    ): FetchedResource<PodcastEpisode, MusifyErrorType> {
        return FetchedResource.Failure(
            cause = MusifyErrorType.RESOURCE_NOT_FOUND,
            data = null
        )
    }

    override suspend fun fetchPodcastShow(
        showId: String,
        countryCode: String
    ): FetchedResource<PodcastShow, MusifyErrorType> {
        return FetchedResource.Failure(
            cause = MusifyErrorType.RESOURCE_NOT_FOUND,
            data = null
        )
    }

    override fun getPodcastEpisodesStreamForPodcastShow(
        showId: String,
        countryCode: String
    ): Flow<PagingData<PodcastEpisode>> {
        return flowOf(PagingData.empty())
    }
}
