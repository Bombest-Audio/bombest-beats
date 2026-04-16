package com.bombest.music.pages

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice

class PlayerPage(private val device: UiDevice) {

    fun assertPlayerVisible() = apply {
        val deadline = System.currentTimeMillis() + 10_000
        var visible = false
        while (!visible && System.currentTimeMillis() < deadline) {
            visible = device.findObject(By.desc("Play")) != null ||
                device.findObject(By.desc("Pause")) != null
            if (!visible) Thread.sleep(200)
        }
        check(visible) { "Expected Play or Pause button to be visible within 10 seconds" }
    }

    fun assertPlayOrPauseVisible() = apply {
        val playVisible = device.findObject(By.desc("Play")) != null
        val pauseVisible = device.findObject(By.desc("Pause")) != null
        check(playVisible || pauseVisible) {
            "Expected Play or Pause button to be visible"
        }
    }

    fun tapPause() = apply {
        device.findObject(By.desc("Pause"))?.click()
        device.waitForIdle()
    }

    fun tapPlay() = apply {
        device.findObject(By.desc("Play"))?.click()
        device.waitForIdle()
    }
}
