package com.bombest.musify.data.paging

import com.bombest.musify.data.remote.musicservice.SpotifyService
import com.bombest.musify.data.remote.response.toTrackSearchResult
import com.bombest.musify.data.repositories.tokenrepository.TokenRepository
import com.bombest.musify.domain.SearchResult
import retrofit2.HttpException
import java.io.IOException

class PlaylistTracksPagingSource(
    playlistId: String,
    countryCode: String,
    tokenRepository: TokenRepository,
    spotifyService: SpotifyService
) : SpotifyPagingSource<SearchResult.TrackSearchResult>(
    loadBlock = { limit, offset ->
        try {
            val data = spotifyService.getTracksForPlaylist(
                playlistId = playlistId,
                market = countryCode,
                token = tokenRepository.getValidBearerToken(),
                limit = limit,
                offset = offset
            ).items.map { it.track.toTrackSearchResult() }
            SpotifyLoadResult.PageData(data)
        } catch (httpException: HttpException) {
            SpotifyLoadResult.Error(httpException)
        } catch (ioException: IOException) {
            SpotifyLoadResult.Error(ioException)
        }
    }
)