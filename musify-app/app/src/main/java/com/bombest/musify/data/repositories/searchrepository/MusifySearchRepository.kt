package com.bombest.musify.data.repositories.searchrepository

import androidx.paging.PagingData
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.MusifyErrorType
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.domain.SearchResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class MusifySearchRepository @Inject constructor(
    private val tracksRepository: TracksRepository
) : SearchRepository {

    private suspend fun getAllTracks(): Result<List<SearchResult.TrackSearchResult>> {
        return try {
            Result.success(tracksRepository.getAllTracks())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun filterTracks(query: String, tracks: List<SearchResult.TrackSearchResult>): List<SearchResult.TrackSearchResult> {
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty()) return tracks
        
        return tracks.filter { track ->
            track.name.lowercase().contains(lowerQuery) ||
            track.artistsString.lowercase().contains(lowerQuery)
        }
    }

    override suspend fun fetchSearchResultsForQuery(
        searchQuery: String,
        countryCode: String
    ): FetchedResource<SearchResults, MusifyErrorType> {
        return getAllTracks().fold(
            onSuccess = { allTracks ->
                val filteredTracks = filterTracks(searchQuery, allTracks)
                
                // Extract unique artists from filtered tracks
                val artists = filteredTracks
                    .flatMap { it.artistsString.split(",").map { it.trim() } }
                    .distinct()
                    .filter { it.lowercase().contains(searchQuery.lowercase()) }
                    .take(20)
                    .map { artistName ->
                        SearchResult.ArtistSearchResult(
                            id = artistName,
                            name = artistName,
                            imageUrlString = null
                        )
                    }
                
                // Group tracks by album (for S3, we use a default album)
                val albums = filteredTracks
                    .groupBy { "S3 Cloud Library" }
                    .map { (albumName, tracks) ->
                        SearchResult.AlbumSearchResult(
                            id = albumName,
                            name = albumName,
                            artistsString = tracks.firstOrNull()?.artistsString ?: "",
                            albumArtUrlString = tracks.firstOrNull()?.imageUrlString ?: "",
                            yearOfReleaseString = ""
                        )
                    }
                    .take(20)
                
                val searchResults = SearchResults(
                    tracks = filteredTracks.take(20),
                    artists = artists,
                    albums = albums,
                    playlists = emptyList(), // Local playlists handled separately
                    shows = emptyList(), // Podcasts not available in S3
                    episodes = emptyList() // Episodes not available in S3
                )
                
                FetchedResource.Success(searchResults)
            },
            onFailure = {
                FetchedResource.Failure(
                    cause = MusifyErrorType.NETWORK_ERROR,
                    data = null
                )
            }
        )
    }

    override fun getPaginatedSearchStreamForAlbums(
        searchQuery: String,
        countryCode: String
    ): Flow<PagingData<SearchResult.AlbumSearchResult>> {
        // For S3, return empty - albums are derived from tracks
        return flowOf(PagingData.empty())
    }

    override fun getPaginatedSearchStreamForArtists(
        searchQuery: String,
        countryCode: String
    ): Flow<PagingData<SearchResult.ArtistSearchResult>> {
        // For S3, return empty - artists are derived from tracks
        return flowOf(PagingData.empty())
    }

    override fun getPaginatedSearchStreamForTracks(
        searchQuery: String,
        countryCode: String
    ): Flow<PagingData<SearchResult.TrackSearchResult>> {
        // Return filtered tracks as a flow
        // Note: This is a simplified implementation - in production, you'd want proper paging
        return kotlinx.coroutines.flow.flow {
            val result = getAllTracks()
            result.onSuccess { tracks ->
                val filtered = filterTracks(searchQuery, tracks)
                emit(PagingData.from(filtered))
            }.onFailure {
                emit(PagingData.empty())
            }
        }
    }

    override fun getPaginatedSearchStreamForPlaylists(
        searchQuery: String,
        countryCode: String
    ): Flow<PagingData<SearchResult.PlaylistSearchResult>> {
        // Local playlists handled by PlaylistRepository
        return flowOf(PagingData.empty())
    }

    override fun getPaginatedSearchStreamForPodcasts(
        searchQuery: String,
        countryCode: String
    ): Flow<PagingData<SearchResult.PodcastSearchResult>> {
        // Podcasts not available in S3
        return flowOf(PagingData.empty())
    }

    override fun getPaginatedSearchStreamForEpisodes(
        searchQuery: String,
        countryCode: String
    ): Flow<PagingData<SearchResult.EpisodeSearchResult>> {
        // Episodes not available in S3
        return flowOf(PagingData.empty())
    }
}
