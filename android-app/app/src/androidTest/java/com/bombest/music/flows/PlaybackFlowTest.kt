package com.bombest.music.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bombest.music.base.BaseE2ETest
import com.bombest.music.pages.LoginPage
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackFlowTest : BaseE2ETest() {

    @Test
    fun loginThenSelectTrack_playerControlsVisible() {
        // Arrange
        val login = LoginPage(device)

        // Act
        val player = login
            .enterUsername(testUsername)
            .enterPassword(testPassword)
            .tapLogin()
            .tapFirstTrack()

        // Assert
        player.assertPlayerVisible()
        player.assertPlayOrPauseVisible()
    }
}
