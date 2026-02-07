package com.bombest.musify.data.repositories.tracksrepository

import androidx.paging.PagingData
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.Genre
import com.bombest.musify.domain.MusifyErrorType
import com.bombest.musify.domain.SearchResult
import kotlinx.coroutines.flow.Flow

/**
 * A repository that contains methods related to tracks. **All methods
 * of this interface will always return an instance of [SearchResult.TrackSearchResult]**
 * encapsulated inside [FetchedResource.Success] if the resource was
 * fetched successfully. This ensures that the return value of all the
 * methods of [TracksRepository] will always return [SearchResult.TrackSearchResult]
 * in the case of a successful fetch operation.
 */
interface TracksRepository {
    /**
     * Get all tracks from S3 bucket
     */
    suspend fun getAllTracks(): List<SearchResult.TrackSearchResult>

    suspend fun fetchTopTenTracksForArtistWithId(
        artistId: String,
        countryCode: String
    ): FetchedResource<List<SearchResult.TrackSearchResult>, MusifyErrorType>

    suspend fun fetchTracksForGenre(
        genre: Genre,
        countryCode: String
    ): FetchedResource<List<SearchResult.TrackSearchResult>, MusifyErrorType>

    suspend fun fetchTracksForAlbumWithId(
        albumId: String,
        countryCode: String
    ): FetchedResource<List<SearchResult.TrackSearchResult>, MusifyErrorType>

    fun getPaginatedStreamForPlaylistTracks(
        playlistId: String,
        countryCode: String,
    ): Flow<PagingData<SearchResult.TrackSearchResult>>
}