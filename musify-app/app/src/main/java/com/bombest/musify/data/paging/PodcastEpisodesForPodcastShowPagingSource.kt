package com.bombest.musify.data.paging

import com.bombest.musify.data.remote.musicservice.SpotifyService
import com.bombest.musify.data.remote.response.toPodcastEpisode
import com.bombest.musify.data.repositories.tokenrepository.TokenRepository
import com.bombest.musify.domain.PodcastEpisode
import retrofit2.HttpException
import java.io.IOException

class PodcastEpisodesForPodcastShowPagingSource(
    showId: String,
    countryCode: String,
    tokenRepository: TokenRepository,
    spotifyService: SpotifyService
) : SpotifyPagingSource<PodcastEpisode>(
    loadBlock = { limit, offset ->
        try {
            val showResponse = spotifyService.getShowWithId(
                token = tokenRepository.getValidBearerToken(),
                id = showId,
                market = countryCode,
            )
            val episodes = spotifyService.getEpisodesForShowWithId(
                token = tokenRepository.getValidBearerToken(),
                id = showId,
                market = countryCode,
                limit = limit,
                offset = offset
            )
                .items
                .map {
                    it.toPodcastEpisode(showResponse)
                }
            SpotifyLoadResult.PageData(episodes)
        } catch (httpException: HttpException) {
            SpotifyLoadResult.Error(httpException)
        } catch (ioException: IOException) {
            SpotifyLoadResult.Error(ioException)
        }
    }
)