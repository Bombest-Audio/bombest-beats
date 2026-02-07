package com.bombest.musify.data.repositories.searchrepository

import com.bombest.musify.data.encoder.TestBase64Encoder
import com.bombest.musify.data.remote.musicservice.SpotifyService
import com.bombest.musify.data.remote.token.tokenmanager.TokenManager
import com.bombest.musify.data.repositories.tokenrepository.SpotifyTokenRepository
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.di.PagingConfigModule
import com.bombest.musify.utils.defaultMusifyJacksonConverterFactory
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class MusifySearchRepositoryTest {
    private lateinit var musifySearchRepository: MusifySearchRepository

    @Before
    fun setUp() {
        val spotifyService = Retrofit.Builder()
            .baseUrl("https://api.spotify.com/")
            .addConverterFactory(defaultMusifyJacksonConverterFactory)
            .build()
            .create(SpotifyService::class.java)
        val tokenManager = Retrofit.Builder()
            .baseUrl("https://accounts.spotify.com/")
            .addConverterFactory(defaultMusifyJacksonConverterFactory)
            .build()
            .create(TokenManager::class.java)
        musifySearchRepository = MusifySearchRepository(
            tokenRepository = SpotifyTokenRepository(
                tokenManager,
                TestBase64Encoder()
            ),
            spotifyService = spotifyService,
            pagingConfig = PagingConfigModule.provideDefaultPagingConfig()
        )
    }

    @Test
    fun fetchSearchResultsTest_validSearchQuery_isSuccessfullyFetched() {
        // given an valid search query
        val query = "Dull Knives"
        // when fetching search results for the query
        val result = runBlocking {
            musifySearchRepository.fetchSearchResultsForQuery(query, "IN")
        }
        // the return type must be of type FetchedResource.Success
        assert(result is FetchedResource.Success)
    }
}