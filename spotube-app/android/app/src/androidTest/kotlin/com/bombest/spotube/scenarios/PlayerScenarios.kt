package com.bombest.spotube.scenarios

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.bombest.spotube.pages.BombestBeatsPage
import com.bombest.spotube.pages.PlayerPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test scenarios for player functionality
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlayerScenarios {
    
    /**
     * Test: Track advances when next is tapped
     * Example from user requirements
     */
    @Test
    fun testTrackAdvancesOnNext() {
        BombestBeatsPage.launchBombestBeats()
            .ensureLibraryLoads()
            .tapSongAtIndex(2)
            .enterFullScreenPlayer()
            .tapNext()
            .verifyTrackAdvances()
    }
    
    /**
     * Test: Playback starts when track is tapped
     */
    @Test
    fun testPlaybackStartsOnTrackTap() {
        BombestBeatsPage.launchBombestBeats()
            .ensureLibraryLoads()
            .tapSongAtIndex(0)
            .enterFullScreenPlayer()
            .waitForPlaybackStart()
            .verifyPlaying()
    }
    
    /**
     * Test: Play/pause toggle works
     */
    @Test
    fun testPlayPauseToggle() {
        val playerPage = BombestBeatsPage.launchBombestBeats()
            .ensureLibraryLoads()
            .tapSongAtIndex(0)
            .enterFullScreenPlayer()
            .waitForPlaybackStart()
        
        // Pause
        playerPage.tapPlayPause()
            .verifyPaused()
        
        // Resume
        playerPage.tapPlayPause()
            .verifyPlaying()
    }
}

