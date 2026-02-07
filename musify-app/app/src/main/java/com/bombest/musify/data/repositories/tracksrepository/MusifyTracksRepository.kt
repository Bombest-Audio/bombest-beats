package com.bombest.musify.data.repositories.tracksrepository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.bombest.musify.data.backend.BackendLibraryApi
import com.bombest.musify.data.s3.S3Repository
import com.bombest.musify.data.s3.normalizePathForMatch
import com.bombest.musify.data.s3.toTrackSearchResults
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.Genre
import com.bombest.musify.domain.MusifyErrorType
import com.bombest.musify.domain.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class MusifyTracksRepository @Inject constructor(
    private val backendLibraryApi: BackendLibraryApi,
    private val s3Repository: S3Repository,
    private val pagingConfig: PagingConfig
) : TracksRepository {

    // Cache for all tracks (S3 listing with optional backend path→id map for metadata editing)
    private var cachedTracks: List<SearchResult.TrackSearchResult>? = null

    /**
     * Clear the tracks cache and force a refresh on next fetch
     */
    fun clearCache() {
        cachedTracks = null
    }

    override suspend fun getAllTracks(): List<SearchResult.TrackSearchResult> {
        if (cachedTracks != null) {
            return cachedTracks!!
        }
        val s3Result = s3Repository.fetchTracks()
        val pathToBackendId = backendLibraryApi.getLibrary().getOrNull()?.let { items ->
            items.mapNotNull { item -> item.path?.let { normalizePathForMatch(it) to item.id } }.toMap()
        }
        return s3Result.fold(
            onSuccess = { s3Tracks ->
                val tracks = s3Tracks.toTrackSearchResults(pathToBackendId)
                cachedTracks = tracks
                tracks
            },
            onFailure = {
                emptyList()
            }
        )
    }

    override suspend fun fetchTopTenTracksForArtistWithId(
        artistId: String,
        countryCode: String
    ): FetchedResource<List<SearchResult.TrackSearchResult>, MusifyErrorType> {
        return try {
            val tracks = getAllTracks()
            // Filter tracks by artist ID (which is the artist name for S3 tracks)
            val artistTracks = tracks.filter { 
                it.artistsString.equals(artistId, ignoreCase = true) 
            }.take(10)
            FetchedResource.Success(artistTracks)
        } catch (e: Exception) {
            FetchedResource.Failure(
                cause = MusifyErrorType.NETWORK_ERROR,
                data = null
            )
        }
    }

    override suspend fun fetchTracksForGenre(
        genre: Genre,
        countryCode: String
    ): FetchedResource<List<SearchResult.TrackSearchResult>, MusifyErrorType> {
        // For S3, we don't have genre information, so return all tracks
        // In the future, this could be filtered by metadata if available
        return try {
            val tracks = getAllTracks()
            FetchedResource.Success(tracks)
        } catch (e: Exception) {
            FetchedResource.Failure(
                cause = MusifyErrorType.NETWORK_ERROR,
                data = null
            )
        }
    }

    override suspend fun fetchTracksForAlbumWithId(
        albumId: String,
        countryCode: String
    ): FetchedResource<List<SearchResult.TrackSearchResult>, MusifyErrorType> {
        // For S3, we don't have album IDs, so return all tracks
        // In the future, this could filter by album name if extracted from metadata
        return try {
            val tracks = getAllTracks()
            FetchedResource.Success(tracks)
        } catch (e: Exception) {
            FetchedResource.Failure(
                cause = MusifyErrorType.NETWORK_ERROR,
                data = null
            )
        }
    }

    override fun getPaginatedStreamForPlaylistTracks(
        playlistId: String,
        countryCode: String
    ): Flow<PagingData<SearchResult.TrackSearchResult>> {
        // For S3, playlists are local-only (handled by PlaylistRepository)
        // Return empty flow for now - will be implemented with local playlist storage
        return flowOf(PagingData.empty())
    }
}