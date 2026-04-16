package com.bombest.music.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NetworkModule] failover state machine.
 *
 * We can't easily advance time in these tests (NetworkModule reads
 * [System.currentTimeMillis] directly), so we exercise the observable transitions:
 *   - primary → failover via [NetworkModule.failover]
 *   - failover → primary via [NetworkModule.resetToPrimary]
 *   - canFailover() bounds
 *
 * Time-based cooldown verification lives in the E2E suite (JwtRefreshFlowTest and
 * friends exercise it indirectly).
 */
class NetworkModuleFailoverTest {

    @Before
    fun setUp() {
        // Leave a clean slate — prior tests may have failed to reset.
        NetworkModule.resetToPrimary()
    }

    @After
    fun tearDown() {
        NetworkModule.resetToPrimary()
    }

    @Test
    fun `starts on primary URL`() {
        val url = NetworkModule.currentBaseUrl
        assertTrue("Expected primary URL, got $url", url.contains("beats.bom.best"))
        assertFalse(url.contains("beats-aws"))
    }

    @Test
    fun `canFailover returns true while primary is active`() {
        assertTrue(NetworkModule.canFailover())
    }

    @Test
    fun `failover switches to secondary URL`() {
        val before = NetworkModule.currentBaseUrl
        NetworkModule.failover()
        val after = NetworkModule.currentBaseUrl
        assertNotEquals("failover should change the active URL", before, after)
        assertTrue("expected failover URL, got $after", after.contains("beats-aws.bom.best"))
    }

    @Test
    fun `canFailover returns false after exhausting URL list`() {
        NetworkModule.failover()
        assertFalse(
            "only 2 URLs are configured; after one failover there's nothing left",
            NetworkModule.canFailover(),
        )
    }

    @Test
    fun `second failover is a no-op once list is exhausted`() {
        NetworkModule.failover()
        val urlAfterFirst = NetworkModule.currentBaseUrl
        NetworkModule.failover()
        assertEquals(urlAfterFirst, NetworkModule.currentBaseUrl)
    }

    @Test
    fun `resetToPrimary returns to primary URL`() {
        NetworkModule.failover()
        assertTrue(NetworkModule.currentBaseUrl.contains("beats-aws"))
        NetworkModule.resetToPrimary()
        assertTrue(NetworkModule.currentBaseUrl.contains("beats.bom.best"))
        assertFalse(NetworkModule.currentBaseUrl.contains("beats-aws"))
    }
}
