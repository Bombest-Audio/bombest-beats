package com.bombest.music.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bombest.music.data.api.Playlist
import com.bombest.music.data.api.PlaylistApi
import com.bombest.music.data.api.PlaylistsResponse
import com.bombest.music.data.api.Track
import com.bombest.music.data.api.TracksResponse
import com.bombest.music.data.cache.AppDatabase
import com.bombest.music.data.cache.PlaylistCacheDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the cache-aside behaviour of [PlaylistRepository] using an in-memory Room DB.
 *
 * The behaviours that matter in prod (and that we regress on without this test):
 *   - First network hit populates the cache.
 *   - Second call with backend down serves the cache (not empty).
 *   - A successful fetch REPLACES cached rows — stale playlists deleted server-side
 *     must disappear from Auto browse on the next online fetch.
 *   - Null API (pre-login, no bearer yet) serves from cache without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaylistRepositoryCacheTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PlaylistCacheDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.playlistCacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getPlaylists populates cache on first network success`() = runBlocking {
        val playlists = listOf(
            playlist(id = 1, name = "Road Trip"),
            playlist(id = 2, name = "Chill"),
        )
        val repo = PlaylistRepository(FakePlaylistApi(playlists = playlists), dao)

        val result = repo.getPlaylists()
        assertEquals(listOf("Road Trip", "Chill"), result.map { it.name })

        val cached = dao.getAllPlaylists()
        assertEquals(2, cached.size)
        // Room DAO sorts by name COLLATE NOCASE, so Chill comes before Road Trip.
        assertEquals(listOf("Chill", "Road Trip"), cached.map { it.name })
    }

    @Test
    fun `getPlaylists falls back to cache when network fails`() = runBlocking {
        // Seed the cache via a successful fetch.
        PlaylistRepository(
            FakePlaylistApi(playlists = listOf(playlist(id = 1, name = "Seeded"))),
            dao,
        ).getPlaylists()

        // Now swap in an API that throws — repo should serve from cache.
        val offlineRepo = PlaylistRepository(FailingPlaylistApi, dao)
        val result = offlineRepo.getPlaylists()
        assertEquals(listOf("Seeded"), result.map { it.name })
    }

    @Test
    fun `successful network fetch replaces stale cached playlists`() = runBlocking {
        PlaylistRepository(
            FakePlaylistApi(playlists = listOf(playlist(id = 1, name = "OldRemoved"))),
            dao,
        ).getPlaylists()
        assertEquals(1, dao.getAllPlaylists().size)

        val fresh = listOf(playlist(id = 2, name = "NewA"), playlist(id = 3, name = "NewB"))
        PlaylistRepository(FakePlaylistApi(playlists = fresh), dao).getPlaylists()

        val cached = dao.getAllPlaylists()
        assertEquals(2, cached.size)
        assertEquals(setOf(2, 3), cached.map { it.id }.toSet())
    }

    @Test
    fun `getPlaylistTracks caches per-playlist tracks`() = runBlocking {
        val tracks = listOf(
            track(id = 10, title = "A"),
            track(id = 11, title = "B"),
            track(id = 12, title = "C"),
        )
        val repo = PlaylistRepository(
            FakePlaylistApi(tracksByPlaylist = mapOf(1 to tracks)),
            dao,
        )
        val result = repo.getPlaylistTracks(1)
        assertEquals(listOf("A", "B", "C"), result.map { it.title })

        val cached = dao.getTracksForPlaylist(1)
        assertEquals(3, cached.size)
        // Position preserved so Auto playback order matches server order.
        assertEquals(listOf(0, 1, 2), cached.map { it.position })
    }

    @Test
    fun `getPlaylistTracks falls back to cached order on network failure`() = runBlocking {
        val tracks = listOf(track(id = 10, title = "First"), track(id = 11, title = "Second"))
        PlaylistRepository(
            FakePlaylistApi(tracksByPlaylist = mapOf(7 to tracks)),
            dao,
        ).getPlaylistTracks(7)

        val offline = PlaylistRepository(FailingPlaylistApi, dao)
        val result = offline.getPlaylistTracks(7)
        assertEquals(listOf("First", "Second"), result.map { it.title })
    }

    @Test
    fun `null api serves only from cache without crashing`() = runBlocking {
        // Pre-login scenario: no bearer yet. Cache is empty, result should be empty (not throw).
        val repo = PlaylistRepository(api = null, dao = dao)
        assertNotNull(repo.getPlaylists())
        assertEquals(0, repo.getPlaylists().size)
    }

    // -------- helpers --------

    private fun playlist(id: Int, name: String) = Playlist(
        id = id,
        name = name,
        count = 0,
        user_id = null,
        is_public = false,
        is_system = false,
        art_url = null,
        share_token = null,
    )

    private fun track(id: Int, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = null,
        duration = 123.0,
        path = "path/$id.flac",
    )

    private class FakePlaylistApi(
        private val playlists: List<Playlist> = emptyList(),
        private val tracksByPlaylist: Map<Int, List<Track>> = emptyMap(),
    ) : PlaylistApi {
        override suspend fun getPlaylists() = PlaylistsResponse(playlists)
        override suspend fun getPlaylistTracks(id: Int) =
            TracksResponse(tracksByPlaylist[id].orEmpty())

        // The rest of the interface is not exercised by these tests.
        override suspend fun createPlaylist(request: com.bombest.music.data.api.CreatePlaylistRequest) = error("not used")
        override suspend fun updatePlaylist(id: Int, request: com.bombest.music.data.api.CreatePlaylistRequest) = error("not used")
        override suspend fun deletePlaylist(id: Int) = error("not used")
        override suspend fun setPlaylistArt(id: Int, image: okhttp3.MultipartBody.Part) = error("not used")
        override suspend fun addTracksToPlaylist(id: Int, request: com.bombest.music.data.api.AddTracksRequest) = error("not used")
        override suspend fun removeTracksFromPlaylist(id: Int, request: com.bombest.music.data.api.AddTracksRequest) = error("not used")
        override suspend fun toggleFavorite(id: Int, request: com.bombest.music.data.api.FavoriteToggleRequest) = error("not used")
        override suspend fun initializeSystemPlaylists() = error("not used")
        override suspend fun reorderPlaylistTracks(id: Int, request: com.bombest.music.data.api.AddTracksRequest) = error("not used")
        override suspend fun searchPlaylist(id: Int, query: String) = error("not used")
        override suspend fun sharePlaylist(id: Int) = error("not used")
        override suspend fun unsharePlaylist(id: Int) = error("not used")
    }

    private object FailingPlaylistApi : PlaylistApi {
        override suspend fun getPlaylists(): PlaylistsResponse = throw java.io.IOException("simulated offline")
        override suspend fun getPlaylistTracks(id: Int): TracksResponse = throw java.io.IOException("simulated offline")
        override suspend fun createPlaylist(request: com.bombest.music.data.api.CreatePlaylistRequest) = error("not used")
        override suspend fun updatePlaylist(id: Int, request: com.bombest.music.data.api.CreatePlaylistRequest) = error("not used")
        override suspend fun deletePlaylist(id: Int) = error("not used")
        override suspend fun setPlaylistArt(id: Int, image: okhttp3.MultipartBody.Part) = error("not used")
        override suspend fun addTracksToPlaylist(id: Int, request: com.bombest.music.data.api.AddTracksRequest) = error("not used")
        override suspend fun removeTracksFromPlaylist(id: Int, request: com.bombest.music.data.api.AddTracksRequest) = error("not used")
        override suspend fun toggleFavorite(id: Int, request: com.bombest.music.data.api.FavoriteToggleRequest) = error("not used")
        override suspend fun initializeSystemPlaylists() = error("not used")
        override suspend fun reorderPlaylistTracks(id: Int, request: com.bombest.music.data.api.AddTracksRequest) = error("not used")
        override suspend fun searchPlaylist(id: Int, query: String) = error("not used")
        override suspend fun sharePlaylist(id: Int) = error("not used")
        override suspend fun unsharePlaylist(id: Int) = error("not used")
    }
}
