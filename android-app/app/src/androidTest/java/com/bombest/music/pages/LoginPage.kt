package com.bombest.music.pages

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

class LoginPage(private val device: UiDevice) {

    fun enterUsername(value: String) = apply {
        // Wait for EditText fields to appear (Compose exposes label as text, not hint)
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 30_000)
        val field = device.findObjects(By.clazz("android.widget.EditText")).getOrNull(0)
        checkNotNull(field) { "Username field not found" }
        field.setText(value)
    }

    fun enterPassword(value: String) = apply {
        val field = device.findObjects(By.clazz("android.widget.EditText")).getOrNull(1)
        checkNotNull(field) { "Password field not found" }
        field.setText(value)
    }

    fun tapLogin(): LibraryPage {
        device.findObject(By.text("Sign In"))?.click()
        // Dismiss any runtime permission dialogs (e.g. RECORD_AUDIO for visualizer).
        // Use short 1.5s timeout so the wait is cheap when the dialog is already gone.
        repeat(3) {
            when {
                device.wait(Until.hasObject(By.text("While using the app")), 1_500) -> {
                    device.findObject(By.text("While using the app"))?.click()
                    device.waitForIdle()
                }
                device.findObject(By.text("Allow")) != null -> {
                    device.findObject(By.text("Allow"))?.click()
                    device.waitForIdle()
                }
            }
        }
        return LibraryPage(device)
    }

    fun assertErrorVisible(message: String) = apply {
        check(device.wait(Until.hasObject(By.text(message)), 5_000)) {
            "Expected error message not found: $message"
        }
    }
}
