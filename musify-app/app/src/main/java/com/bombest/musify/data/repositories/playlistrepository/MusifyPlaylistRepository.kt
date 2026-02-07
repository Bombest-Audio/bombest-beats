package com.bombest.musify.data.repositories.playlistrepository

import com.bombest.musify.data.local.PlaylistDao
import com.bombest.musify.data.local.PlaylistEntity
import com.bombest.musify.data.local.PlaylistTrackDao
import com.bombest.musify.data.local.PlaylistTrackEntity
import com.bombest.musify.data.repositories.tracksrepository.TracksRepository
import com.bombest.musify.domain.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class MusifyPlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val tracksRepository: TracksRepository
) : PlaylistRepository {

    override fun getAllPlaylists(): Flow<List<SearchResult.PlaylistSearchResult>> {
        return playlistDao.getAllPlaylists().map { entities ->
            entities.map { entity ->
                entity.toPlaylistSearchResult()
            }
        }
    }

    override suspend fun getPlaylistById(playlistId: String): SearchResult.PlaylistSearchResult? {
        val entity = playlistDao.getPlaylistById(playlistId) ?: return null
        return entity.toPlaylistSearchResult()
    }

    override fun getTracksForPlaylist(playlistId: String): Flow<List<SearchResult.TrackSearchResult>> {
        return playlistTrackDao.getTracksForPlaylist(playlistId).map { trackEntities ->
            val allTracks = tracksRepository.getAllTracks()
            val trackMap = allTracks.associateBy { it.id }
            trackEntities.mapNotNull { trackEntity ->
                trackMap[trackEntity.trackId]
            }
        }
    }

    override suspend fun createPlaylist(name: String, description: String?): String {
        val playlistId = UUID.randomUUID().toString()
        val playlist = PlaylistEntity(
            id = playlistId,
            name = name,
            description = description,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        playlistDao.insertPlaylist(playlist)
        return playlistId
    }

    override suspend fun updatePlaylist(playlistId: String, name: String?, description: String?) {
        val existing = playlistDao.getPlaylistById(playlistId) ?: return
        val updated = existing.copy(
            name = name ?: existing.name,
            description = description ?: existing.description,
            updatedAt = System.currentTimeMillis()
        )
        playlistDao.updatePlaylist(updated)
    }

    override suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylistById(playlistId)
        // Tracks will be deleted automatically due to CASCADE foreign key
    }

    override suspend fun addTrackToPlaylist(playlistId: String, track: SearchResult.TrackSearchResult) {
        val existingTracks = playlistTrackDao.getTracksForPlaylistSync(playlistId)
        val nextPosition = existingTracks.size
        
        val playlistTrack = PlaylistTrackEntity(
            id = "${playlistId}_${track.id}",
            playlistId = playlistId,
            trackId = track.id,
            position = nextPosition
        )
        playlistTrackDao.insertTrack(playlistTrack)
        
        // Update playlist's updatedAt timestamp
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        val track = playlistTrackDao.getTracksForPlaylistSync(playlistId)
            .find { it.trackId == trackId } ?: return
        
        playlistTrackDao.deleteTrackFromPlaylist(playlistId, trackId)
        playlistTrackDao.shiftPositionsAfter(playlistId, track.position)
        
        // Update playlist's updatedAt timestamp
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun reorderTracks(playlistId: String, fromPosition: Int, toPosition: Int) {
        val tracks = playlistTrackDao.getTracksForPlaylistSync(playlistId)
        if (fromPosition < 0 || fromPosition >= tracks.size || 
            toPosition < 0 || toPosition >= tracks.size) {
            return
        }
        
        // Simple reorder: swap positions
        val fromTrack = tracks[fromPosition]
        val toTrack = tracks[toPosition]
        
        playlistTrackDao.insertTrack(fromTrack.copy(position = toPosition))
        playlistTrackDao.insertTrack(toTrack.copy(position = fromPosition))
        
        // Update playlist's updatedAt timestamp
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
    }

    private fun PlaylistEntity.toPlaylistSearchResult(): SearchResult.PlaylistSearchResult {
        return SearchResult.PlaylistSearchResult(
            id = id,
            name = name,
            imageUrlString = null, // Local playlists don't have images
            ownerName = "You",
            totalNumberOfTracks = "0" // Will be calculated when needed
        )
    }
}
