package com.bombest.music.pages

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

class PlaylistsPage(private val device: UiDevice) {

    fun assertVisible() = apply {
        check(device.wait(Until.hasObject(By.text("Playlists")), 5_000)) {
            "Playlists screen did not appear"
        }
        // Wait for initial loadPlaylists() to complete before any mutations.
        // Navigation's onPlaylists callback triggers loadPlaylists(); if we proceed
        // before it finishes, its response arrives after createPlaylist()'s optimistic
        // add and calls playlists.clear(), wiping the new playlist from the list.
        device.wait(Until.gone(By.desc("Loading playlists")), 8_000)
    }

    fun tapCreate() = apply {
        device.findObject(By.desc("Create Playlist"))?.click()
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 8_000)
        device.waitForIdle()
    }

    fun enterName(name: String) = apply {
        // Compose TextField in AlertDialog: ACTION_SET_TEXT updates Compose state directly
        val field = device.findObject(By.clazz("android.widget.EditText"))
        checkNotNull(field) { "Playlist name field not found" }
        field.setText(name)
        device.waitForIdle()
    }

    fun confirmCreate() = apply {
        device.findObject(By.text("Create"))?.click()
        // Sleep to allow viewModelScope coroutine (createPlaylist + loadPlaylists) to run
        // UIAutomator accessibility queries block the app's main thread, starving coroutines.
        Thread.sleep(5_000)
    }

    fun assertPlaylistVisible(name: String) = apply {
        // Use By.desc() because the PlaylistItem card has contentDescription=playlist.name,
        // which is accessible even when the Text node is in a transitional animation state.
        check(device.wait(Until.hasObject(By.desc(name)), 10_000)) {
            "Playlist '$name' did not appear within 10 seconds"
        }
    }

    fun deletePlaylist(name: String) = apply {
        // Prefer direct accessibility lookup (works for items with stable Compose composition
        // slots). For items in a fresh composition slot (e.g. newly added to LazyColumn),
        // child accessibility content is null or unstable. Fall back to coordinate-based click.
        val directDelete = device.findObject(By.desc("Delete $name"))
        if (directDelete != null) {
            directDelete.click()
        } else {
            val card = checkNotNull(device.findObject(By.desc(name))) {
                "Card '$name' not found in accessibility tree"
            }
            val cardBounds = card.visibleBounds
            // Delete button center from card.right:
            //   card.right - 4dp_end_padding - 24dp_half_button = card.right - 28dp
            // At density 2.625 (Pixel 9): 28dp = 73.5px ≈ 74px
            val deleteX = cardBounds.right - 74
            val cardCenterY = (cardBounds.top + cardBounds.bottom) / 2
            // Compose LazyColumn fresh-slot items need TWO taps: the first touch initialises
            // the gesture detector (no onClick fires), the second touch fires onClick.
            device.click(deleteX, cardCenterY) // primer tap
            Thread.sleep(1_500)
            if (device.findObject(By.text("Delete playlist?")) == null) {
                device.click(deleteX, cardCenterY) // actual trigger
            }
        }
        device.waitForIdle()
        // Confirm deletion in the dialog
        device.wait(Until.hasObject(By.text("Delete playlist?")), 5_000)
        device.findObject(By.text("Delete"))?.click()
        Thread.sleep(3_000)
    }

    fun assertPlaylistGone(name: String) = apply {
        check(device.wait(Until.gone(By.desc(name)), 8_000)) {
            "Playlist '$name' is still visible after deletion"
        }
    }
}
