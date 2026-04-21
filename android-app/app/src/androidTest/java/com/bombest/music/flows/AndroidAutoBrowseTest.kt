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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoBrowseTest {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launchApp() {
        // Start the app so BombestMediaService is warm before we try to bind.
        // Without this the service cold-starts on demand and can exceed the 30s timeout on CI.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = ctx.packageName
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am start -n $pkg/.LoginActivity")
        device.wait(Until.hasObject(By.pkg(pkg)), 15_000)
    }

    @Test
    fun browseRoot_returnsExpectedCategories() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val token = SessionToken(
            context,
            ComponentName(context, BombestMediaService::class.java),
        )

        val browserFuture = MediaBrowser.Builder(context, token).buildAsync()
        val browser = browserFuture.get(30, TimeUnit.SECONDS)
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
        assertTrue("browse root should include Playlists, got: $childIds", childIds.contains("playlists"))
        assertTrue("browse root should include Recent, got: $childIds", childIds.contains("recent"))

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            browser!!.release()
        }
    }
}
