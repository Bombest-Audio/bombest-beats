package com.bombest.music.pages

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

class LibraryPage(private val device: UiDevice) {

    fun assertVisible() = apply {
        check(device.wait(Until.hasObject(By.desc("Library")), 10_000)) {
            "Library screen did not appear within 10 seconds"
        }
    }

    fun tapFirstTrack(): PlayerPage {
        // Wait for track items to load, then tap the first album art (inside the clickable Card)
        device.wait(Until.hasObject(By.desc("Album Art")), 10_000)
        device.findObject(By.desc("Album Art"))?.click()
        return PlayerPage(device)
    }

    fun tapMenu(): LibraryPage = apply {
        device.findObject(By.desc("Menu"))?.click()
        device.waitForIdle()
    }

    fun tapMenuItemPlaylists(): PlaylistsPage {
        device.findObject(By.text("Playlists"))?.click()
        device.waitForIdle()
        return PlaylistsPage(device)
    }
}
