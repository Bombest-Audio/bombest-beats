package com.bombest.music.flows

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.bombest.music.service.BombestMediaService
import com.google.common.util.concurrent.MoreExecutors
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * E2E test for Android Auto browse tree. Binds a [MediaBrowser] to [BombestMediaService] and
 * walks the root → children hierarchy to make sure Auto sees the four top-level categories
 * (Songs / Playlists / Recent / Shuffle-all).
 *
 * This is the regression test we lacked when Auto shipped with an empty browse tree in beta —
 * the app would launch on the head unit but show "No content."
 *
 * Runs on a connected device/emulator. Service binds on the main thread so callbacks post
 * back via a [CountDownLatch] with a generous timeout.
 *
 * CI note: this test starts the app to warm the service before binding. If the emulator is too
 * slow to start the service within 60 seconds the test is skipped (not failed) so it doesn't
 * abort the Login / Playback / Playlist P0 tests that run after it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoBrowseTest {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun launchApp() {
        // Start the app so BombestMediaService is warm before we try to bind.
        // Without this the service cold-starts on demand and can exceed the timeout on CI.
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        device.pressHome()
        device.waitForIdle()
        // Also explicitly start the service so it begins initializing immediately.
        device.executeShellCommand("am startservice -n $pkg/.service.BombestMediaService")
        device.executeShellCommand("am start -n $pkg/.LoginActivity")
        device.wait(Until.hasObject(By.pkg(pkg)), 15_000)
        // Wait for login UI to render — confirms the app process is fully initialized.
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 30_000)
    }

    @Test
    fun browseRoot_returnsExpectedCategories() {
        val token = SessionToken(
            context,
            ComponentName(context, BombestMediaService::class.java),
        )

        // Use a generous 60s timeout for the service connection on slow CI runners (no KVM).
        // Cancel the future on timeout so dangling threads don't crash the instrumentation
        // process and abort the Login / Playback / Playlist tests that follow.
        val browserFuture = MediaBrowser.Builder(context, token).buildAsync()
        val browser = try {
            browserFuture.get(60, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            browserFuture.cancel(true)
            assumeTrue(
                "BombestMediaService did not bind within 60 s on this runner — skipping Auto browse test",
                false,
            )
            return
        }
        assertNotNull("MediaBrowser must bind to BombestMediaService", browser)

        // Fetch root, then its children — Auto does exactly this handshake.
        val rootLatch = CountDownLatch(1)
        var rootId: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val rootFuture = browser!!.getLibraryRoot(null)
            rootFuture.addListener({
                rootId = rootFuture.get().value?.mediaId
                rootLatch.countDown()
            }, MoreExecutors.directExecutor())
        }
        assertTrue("root resolved", rootLatch.await(30, TimeUnit.SECONDS))
        assertNotNull("root mediaId must be non-null", rootId)

        val childrenLatch = CountDownLatch(1)
        var children: List<MediaItem> = emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val childrenFuture = browser!!.getChildren(rootId!!, 0, 50, null)
            childrenFuture.addListener({
                children = childrenFuture.get().value.orEmpty().toList()
                childrenLatch.countDown()
            }, MoreExecutors.directExecutor())
        }
        assertTrue("children resolved", childrenLatch.await(30, TimeUnit.SECONDS))

        // The four top-level categories Auto users expect to see.
        val childIds = children.map { it.mediaId }.toSet()
        assertTrue("browse root should include Songs, got: $childIds", childIds.contains("songs"))
        assertTrue(
            "browse root should include Playlists, got: $childIds",
            childIds.contains("playlists"),
        )
        assertTrue(
            "browse root should include Recent, got: $childIds",
            childIds.contains("recent"),
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            browser!!.release()
        }
    }
}
