package com.bombest.music.pages

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

class LibraryPage(private val device: UiDevice) {

    fun assertVisible() = apply {
        // "bombest beats" is the TopAppBar title — always visible once on the library screen,
        // regardless of library-loading state. Using By.text() is more reliable than
        // By.desc() on a non-interactive Compose Box on API 29 emulators.
        check(device.wait(Until.hasObject(By.text("bombest beats")), 60_000)) {
            "Library screen did not appear within 60 seconds"
        }
    }

    fun tapFirstTrack(): PlayerPage {
        // Wait for track items to load, then tap the first album art (inside the clickable Card)
        device.wait(Until.hasObject(By.desc("Album Art")), 30_000)
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
