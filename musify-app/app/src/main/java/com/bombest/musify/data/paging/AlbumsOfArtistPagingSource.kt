package com.bombest.musify.data.paging

import com.bombest.musify.data.remote.musicservice.SpotifyService
import com.bombest.musify.data.remote.response.toAlbumSearchResult
import com.bombest.musify.data.repositories.tokenrepository.TokenRepository
import com.bombest.musify.domain.SearchResult
import retrofit2.HttpException
import java.io.IOException

class AlbumsOfArtistPagingSource(
    private val artistId: String,
    private val market: String,
    private val tokenRepository: TokenRepository,
    private val spotifyService: SpotifyService
) : SpotifyPagingSource<SearchResult.AlbumSearchResult>(
    loadBlock = { limit, offset ->
        try {
            val albumsMetadataResponse = spotifyService.getAlbumsOfArtistWithId(
                artistId = artistId,
                market = market,
                token = tokenRepository.getValidBearerToken(),
                limit = limit,
                offset = offset,
            )
            val data = albumsMetadataResponse.items.map { it.toAlbumSearchResult() }
            SpotifyLoadResult.PageData(data)
        } catch (httpException: HttpException) {
            SpotifyLoadResult.Error(httpException)
        } catch (ioException: IOException) {
            SpotifyLoadResult.Error(ioException)
        }
    }
)