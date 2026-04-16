package com.bombest.music.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bombest.music.base.BaseE2ETest
import com.bombest.music.pages.LoginPage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistFlowTest : BaseE2ETest() {

    @After
    fun teardown() {
        // Delete all "E2E Test *" playlists via the API so failed runs don't accumulate them.
        deletePlaylistsByPrefix("E2E Test")
    }

    @Test
    fun createAndDeletePlaylist_endToEnd() {
        val playlistName = "E2E Test ${System.currentTimeMillis()}"

        // Arrange
        val login = LoginPage(device)

        // Act — create
        val playlists = login
            .enterUsername(testUsername)
            .enterPassword(testPassword)
            .tapLogin()
            .assertVisible()   // wait for Library screen so PlaylistViewModel fully initialises
            .tapMenu()
            .tapMenuItemPlaylists()
            .assertVisible()
            .tapCreate()
            .enterName(playlistName)
            .confirmCreate()

        // Assert — created
        playlists.assertPlaylistVisible(playlistName)

        // Act — delete
        playlists.deletePlaylist(playlistName)

        // Assert — gone
        playlists.assertPlaylistGone(playlistName)
    }
}
