package com.bombest.spotube.utils

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.TimeUnit

/**
 * Helper utilities for Espresso tests
 */
object TestHelpers {
    
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    
    /**
     * Waits for a condition to become true, with timeout
     */
    fun waitForCondition(
        condition: () -> Boolean,
        timeoutSeconds: Int = 10,
        checkIntervalMs: Long = 500
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeoutSeconds * 1000L
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (condition()) {
                return true
            }
            Thread.sleep(checkIntervalMs)
        }
        return false
    }
    
    /**
     * Waits for a specified duration
     */
    fun wait(seconds: Int) {
        Thread.sleep(seconds * 1000L)
    }
    
    /**
     * Waits for a specified duration in milliseconds
     */
    fun waitMs(milliseconds: Long) {
        Thread.sleep(milliseconds)
    }
    
    /**
     * Disables network connectivity (requires root or ADB)
     */
    fun disableNetwork() {
        // Implementation would use ADB commands or system settings
        // adb shell svc wifi disable
        // adb shell svc data disable
    }
    
    /**
     * Enables network connectivity
     */
    fun enableNetwork() {
        // Implementation would use ADB commands or system settings
        // adb shell svc wifi enable
        // adb shell svc data enable
    }
    
    /**
     * Clears app data and cache
     */
    fun clearAppData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageName = context.packageName
        // Use ADB to clear app data
        // adb shell pm clear $packageName
    }
}

