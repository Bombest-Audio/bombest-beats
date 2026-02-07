package com.bombest.musify.data.repositories.albumsrepository

import androidx.paging.PagingData
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.MusifyErrorType
import com.bombest.musify.domain.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class MusifyAlbumsRepository @Inject constructor(
    private val tracksRepository: TracksRepository
) : AlbumsRepository {

    private suspend fun getAllTracks(): Result<List<SearchResult.TrackSearchResult>> {
        return try {
            Result.success(tracksRepository.getAllTracks())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchAlbumsOfArtistWithId(
        artistId: String,
        countryCode: String
    ): FetchedResource<List<SearchResult.AlbumSearchResult>, MusifyErrorType> {
        // For S3, return a single default album for the artist
        return getAllTracks().fold(
            onSuccess = { tracks ->
                val artistTracks = tracks.filter { 
                    it.artistsString.equals(artistId, ignoreCase = true) 
                }
                if (artistTracks.isEmpty()) {
                    FetchedResource.Success(emptyList())
                } else {
                    val album = SearchResult.AlbumSearchResult(
                        id = "s3_album_$artistId",
                        name = "S3 Cloud Library",
                        artistsString = artistId,
                        albumArtUrlString = artistTracks.firstOrNull()?.imageUrlString ?: "",
                        yearOfReleaseString = ""
                    )
                    FetchedResource.Success(listOf(album))
                }
            },
            onFailure = {
                FetchedResource.Failure(
                    cause = MusifyErrorType.NETWORK_ERROR,
                    data = null
                )
            }
        )
    }

    override suspend fun fetchAlbumWithId(
        albumId: String,
        countryCode: String
    ): FetchedResource<SearchResult.AlbumSearchResult, MusifyErrorType> {
        // For S3, return default album
        return getAllTracks().fold(
            onSuccess = { tracks ->
                if (tracks.isEmpty()) {
                    FetchedResource.Failure(
                        cause = MusifyErrorType.RESOURCE_NOT_FOUND,
                        data = null
                    )
                } else {
                    val album = SearchResult.AlbumSearchResult(
                        id = albumId,
                        name = "S3 Cloud Library",
                        artistsString = tracks.firstOrNull()?.artistsString ?: "",
                        albumArtUrlString = tracks.firstOrNull()?.imageUrlString ?: "",
                        yearOfReleaseString = ""
                    )
                    FetchedResource.Success(album)
                }
            },
            onFailure = {
                FetchedResource.Failure(
                    cause = MusifyErrorType.NETWORK_ERROR,
                    data = null
                )
            }
        )
    }

    override fun getPaginatedStreamForAlbumsOfArtist(
        artistId: String,
        countryCode: String
    ): Flow<PagingData<SearchResult.AlbumSearchResult>> {
        // For S3, return empty - albums are derived from tracks
        return flowOf(PagingData.empty())
    }
}