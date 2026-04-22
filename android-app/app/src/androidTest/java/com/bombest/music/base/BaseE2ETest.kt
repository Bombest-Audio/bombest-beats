package com.bombest.music.base

import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.bombest.music.BuildConfig
import com.bombest.music.data.authDataStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before

abstract class BaseE2ETest {

    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun getTestCredential(argumentKey: String, fallbackValue: String): String {
        val argumentValue = InstrumentationRegistry.getArguments().getString(argumentKey)
        return argumentValue?.takeIf { it.isNotBlank() } ?: fallbackValue
    }

    val testUsername: String get() = getTestCredential("test.username", BuildConfig.TEST_USERNAME)
    val testPassword: String get() = getTestCredential("test.password", BuildConfig.TEST_PASSWORD)

    @Before
    fun setup() {
        assumeTrue(
            "Skipping E2E test: provide test.username and test.password via instrumentation runner arguments or local.properties",
            testUsername.isNotEmpty() && testPassword.isNotEmpty()
        )
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = ctx.packageName

        // Clear stored auth token so LoginActivity always shows the login form
        runBlocking { ctx.authDataStore.edit { it.clear() } }

        // Go to home screen then bring LoginActivity to the foreground.
        // tearDown() below fires an am-start after each test so that LoginActivity is
        // already rendering (or rendered) by the time the next setup() runs. Reusing
        // an already-rendered instance is <1 s; a cold start on this API 29 swiftshader
        // emulator takes 30-60 s due to JIT compilation + GC pressure.
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am start -n $pkg/.LoginActivity")

        // Dismiss Android 16 "16KB page size" compatibility warning dialog if it appears
        val compatWarning: UiObject2? = device.wait(Until.findObject(By.text("Android App Compatibility")), 4_000)
        if (compatWarning != null) {
            device.findObject(By.text("Don't Show Again"))?.click()
            device.waitForIdle()
        }

        // Wait for our package to be visible in the foreground. LoginActivity can take 6-10s
        // to first-render on a cold API 29 swiftshader emulator; 30s gives plenty of headroom.
        device.wait(Until.hasObject(By.pkg(pkg)), 30_000)
    }

    @After
    fun tearDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = ctx.packageName
        // Clear auth state so the next test always starts from the login screen.
        runBlocking { ctx.authDataStore.edit { it.clear() } }
        // Fire-and-forget am start: kick off LoginActivity so it begins rendering in the
        // background while JUnit transitions to the next test. By the time setup() runs
        // its 30-second wait, LoginActivity is already rendered or well underway —
        // avoiding the 30-60 s cold-start penalty on this slow emulator.
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am start -n $pkg/.LoginActivity")
        // Give LoginActivity a head start. This short idle let the process begin composing
        // before the next test's setup() fires.
        device.waitForIdle()
    }

    // ---------------------------------------------------------------------------
    // API helpers for test teardown — clean up server-side data without the UI
    // ---------------------------------------------------------------------------

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val apiBase: String get() = "https://beats.bom.best"

    /** Returns a JWT token, or null if login fails. */
    fun loginApi(): String? = try {
        val body = """{"username":"$testUsername","password":"$testPassword"}"""
            .toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url("$apiBase/auth/login").post(body).build()
        val resp = httpClient.newCall(req).execute()
        resp.body?.string()?.let { JSONObject(it).optString("access_token") }?.ifEmpty { null }
    } catch (e: Exception) {
        android.util.Log.w("BaseE2ETest", "loginApi failed: ${e.message}")
        null
    }

    /** Deletes all user-owned playlists whose name starts with [prefix]. */
    fun deletePlaylistsByPrefix(prefix: String) {
        val token = loginApi() ?: return
        try {
            val listReq = Request.Builder()
                .url("$apiBase/playlists")
                .header("Authorization", "Bearer $token")
                .build()
            val listResp = httpClient.newCall(listReq).execute()
            val playlists = JSONObject(listResp.body?.string() ?: return)
                .getJSONArray("playlists")
            for (i in 0 until playlists.length()) {
                val pl = playlists.getJSONObject(i)
                if (pl.getString("name").startsWith(prefix) && !pl.optBoolean("is_system")) {
                    val id = pl.getInt("id")
                    val delReq = Request.Builder()
                        .url("$apiBase/playlists/$id")
                        .header("Authorization", "Bearer $token")
                        .delete()
                        .build()
                    httpClient.newCall(delReq).execute().close()
                    android.util.Log.i("BaseE2ETest", "Deleted test playlist $id '${pl.getString("name")}'")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BaseE2ETest", "deletePlaylistsByPrefix failed: ${e.message}")
        }
    }
}
