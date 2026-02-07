package com.bombest.musify.data.repositories.podcastsrepository

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

class MusifyPodcastsRepositoryTest {
    private lateinit var podcastsRepository: PodcastsRepository

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
        podcastsRepository = MusifyPodcastsRepository(
            tokenRepository = SpotifyTokenRepository(
                tokenManager,
                TestBase64Encoder()
            ),
            spotifyService = spotifyService,
            pagingConfig = PagingConfigModule.provideDefaultPagingConfig()
        )
    }

    @Test
    fun fetchPodcastEpisodeTest_validEpisodeId_successfullyFetchesPodcastEpisode() = runBlocking {
        val validEpisodeId = "5pLYyCItRvIc2SEbuJ3eO8"
        val fetchedResource = podcastsRepository.fetchPodcastEpisode(
            episodeId = validEpisodeId,
            countryCode = "IN"
        )
        assert(fetchedResource is FetchedResource.Success)
    }

    @Test
    fun fetchPodcastShowTest_validPodcastShowId_successfullyFetchesShow() = runBlocking {
        val validShowId = "6o81QuW22s5m2nfcXWjucc"
        val fetchedResource = podcastsRepository.fetchPodcastShow(
            showId = validShowId,
            countryCode = "IN"
        )
        assert(fetchedResource is FetchedResource.Success)
    }
}