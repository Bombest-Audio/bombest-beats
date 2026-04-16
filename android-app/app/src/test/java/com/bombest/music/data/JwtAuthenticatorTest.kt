package com.bombest.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + MockWebServer tests for [JwtAuthenticator]. Verifies:
 *   - Recursion guards (priorResponse, /auth/refresh path, /auth/login path)
 *   - Happy path: 401 → refresh → retry with new token
 *   - Refresh failure → null (propagates original 401 to caller)
 *   - Missing refresh token → null (no infinite retry)
 *
 * We build synthetic [Response] objects to hand to `authenticate()` rather than driving a
 * full OkHttp call stack; that keeps tests focused on the Authenticator's decision logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JwtAuthenticatorTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        // Start every test with a clean auth store.
        runBlocking {
            context.authDataStore.edit { it.clear() }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns null when priorResponse is set (already retried once)`() {
        val auth = JwtAuthenticator(context) { server.url("/").toString() }
        val original = fakeResponse(url = server.url("/tracks").toString())
        val retry = original.newBuilder()
            .priorResponse(original)  // mark as "this is already a retry"
            .build()
        val result = auth.authenticate(route = null, response = retry)
        assertNull("already-retried 401 must not refresh again", result)
    }

    @Test
    fun `returns null for _auth_refresh requests`() {
        val auth = JwtAuthenticator(context) { server.url("/").toString() }
        val response = fakeResponse(url = server.url("/auth/refresh").toString())
        assertNull(auth.authenticate(route = null, response = response))
    }

    @Test
    fun `returns null for _auth_login requests`() {
        val auth = JwtAuthenticator(context) { server.url("/").toString() }
        val response = fakeResponse(url = server.url("/auth/login").toString())
        assertNull(auth.authenticate(route = null, response = response))
    }

    @Test
    fun `returns null when no refresh token is stored`() {
        val auth = JwtAuthenticator(context) { server.url("/").toString() }
        // DataStore is empty (set up by @Before)
        val response = fakeResponse(url = server.url("/tracks").toString())
        assertNull(auth.authenticate(route = null, response = response))
    }

    @Test
    fun `refreshes access token and retries original request on 401`() {
        runBlocking {
            context.authDataStore.edit { prefs ->
                prefs[AuthPreferences.REFRESH_TOKEN_KEY] = "refresh-abc"
            }
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"new-access-xyz","expires_in":2592000}""")
                .setHeader("Content-Type", "application/json"),
        )

        val auth = JwtAuthenticator(context) { server.url("/").toString().trimEnd('/') }
        val original = fakeResponse(url = server.url("/tracks").toString())
        val retry = auth.authenticate(route = null, response = original)

        assertNotNull("authenticator should produce a retry request", retry)
        assertEquals("Bearer new-access-xyz", retry?.header("Authorization"))

        // Verify stored access token was updated.
        val storedToken = runBlocking {
            context.authDataStore.data.first()[AuthPreferences.TOKEN_KEY]
        }
        assertEquals("new-access-xyz", storedToken)

        // And confirm MockWebServer saw the refresh with the stored refresh token.
        val recorded = server.takeRequest()
        assertEquals("/auth/refresh", recorded.path)
        assertEquals("Bearer refresh-abc", recorded.getHeader("Authorization"))
    }

    @Test
    fun `returns null when refresh server responds with failure`() {
        runBlocking {
            context.authDataStore.edit { prefs ->
                prefs[AuthPreferences.REFRESH_TOKEN_KEY] = "refresh-abc"
            }
        }
        server.enqueue(MockResponse().setResponseCode(401))

        val auth = JwtAuthenticator(context) { server.url("/").toString().trimEnd('/') }
        val original = fakeResponse(url = server.url("/tracks").toString())
        val retry = auth.authenticate(route = null, response = original)

        assertNull("refresh failure must propagate original 401 to caller", retry)
    }

    @Test
    fun `concurrent 401s trigger only one refresh round-trip`() {
        // Seed refresh token + a stale access token.
        runBlocking {
            context.authDataStore.edit { prefs ->
                prefs[AuthPreferences.REFRESH_TOKEN_KEY] = "refresh-abc"
                prefs[AuthPreferences.TOKEN_KEY] = "stale-access"
            }
        }
        // Only one refresh response is enqueued. If single-flight is broken, the
        // second (and later) threads will hit MockWebServer with no response and
        // throw, or call takeRequest twice.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"fresh-access","expires_in":2592000}""")
                .setHeader("Content-Type", "application/json"),
        )

        val auth = JwtAuthenticator(context) { server.url("/").toString().trimEnd('/') }

        // Build N synthetic 401 responses, all using the same stale token.
        val n = 5
        val staleAuth = "Bearer stale-access"
        val requests = (1..n).map {
            val req = Request.Builder()
                .url(server.url("/tracks/$it").toString())
                .header("Authorization", staleAuth)
                .build()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .build()
        }

        // Fire them in parallel threads and join.
        val results = java.util.Collections.synchronizedList(mutableListOf<Request?>())
        val threads = requests.map { resp ->
            Thread { results.add(auth.authenticate(route = null, response = resp)) }.also { it.start() }
        }
        threads.forEach { it.join(5_000) }

        // Every thread should end up with the same fresh bearer token.
        assertEquals(n, results.size)
        results.forEach { r ->
            assertNotNull(r)
            assertEquals("Bearer fresh-access", r?.header("Authorization"))
        }

        // Exactly one hit to /auth/refresh.
        val firstRefresh = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("first thread should refresh", firstRefresh)
        assertEquals("/auth/refresh", firstRefresh?.path)
        val secondRefresh = server.takeRequest(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNull("later threads must replay with the stored token, not refresh again", secondRefresh)
    }

    /** Build a minimal 401 Response with the given URL for the Authenticator to inspect. */
    private fun fakeResponse(url: String): Response {
        val req = Request.Builder().url(url).build()
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }
}

