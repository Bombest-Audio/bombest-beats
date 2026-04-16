package com.bombest.music.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bombest.music.base.BaseE2ETest
import com.bombest.music.pages.LoginPage
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginFlowTest : BaseE2ETest() {

    @Test
    fun validCredentials_navigatesToLibrary() {
        // Arrange
        val login = LoginPage(device)

        // Act
        val library = login
            .enterUsername(testUsername)
            .enterPassword(testPassword)
            .tapLogin()

        // Assert
        library.assertVisible()
    }
}
