package com.bombest.musify.data.repositories.homefeedrepository

import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.FeaturedPlaylists
import com.bombest.musify.domain.MusifyErrorType
import com.bombest.musify.domain.PlaylistsForCategory
import com.bombest.musify.domain.SearchResult
import javax.inject.Inject

class MusifyHomeFeedRepository @Inject constructor(
    private val tracksRepository: TracksRepository
) : HomeFeedRepository {

    private suspend fun getAllTracks(): Result<List<SearchResult.TrackSearchResult>> {
        return try {
            Result.success(tracksRepository.getAllTracks())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchNewlyReleasedAlbums(
        countryCode: String
    ): FetchedResource<List<SearchResult.AlbumSearchResult>, MusifyErrorType> {
        // For S3, return a single "album" representing all tracks
        return getAllTracks().fold(
            onSuccess = { tracks ->
                if (tracks.isEmpty()) {
                    FetchedResource.Success(emptyList())
                } else {
                    val album = SearchResult.AlbumSearchResult(
                        id = "s3_album",
                        name = "S3 Cloud Library",
                        artistsString = tracks.firstOrNull()?.artistsString ?: "",
                        albumArtUrlString = tracks.firstOrNull()?.imageUrlString ?: "",
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

    override suspend fun fetchFeaturedPlaylistsForCurrentTimeStamp(
        timestampMillis: Long,
        countryCode: String,
        languageCode: ISO6391LanguageCode,
    ): FetchedResource<FeaturedPlaylists, MusifyErrorType> {
        // For S3, return empty featured playlists
        // Local playlists will be handled by PlaylistRepository
        return FetchedResource.Success(
            FeaturedPlaylists(
                playlistsDescription = "",
                playlists = emptyList()
            )
        )
    }

    override suspend fun fetchPlaylistsBasedOnCategoriesAvailableForCountry(
        countryCode: String,
        languageCode: ISO6391LanguageCode,
    ): FetchedResource<List<PlaylistsForCategory>, MusifyErrorType> {
        // For S3, we don't have categories, return empty list
        return FetchedResource.Success(emptyList())
    }
}
