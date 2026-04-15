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
import org.junit.Assume.assumeTrue
import org.junit.Before

abstract class BaseE2ETest {

    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    val testUsername: String get() = BuildConfig.TEST_USERNAME
    val testPassword: String get() = BuildConfig.TEST_PASSWORD

    @Before
    fun setup() {
        assumeTrue(
            "Skipping E2E test: set test.username and test.password in local.properties",
            testUsername.isNotEmpty() && testPassword.isNotEmpty()
        )
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = ctx.packageName

        // Clear stored auth token so LoginActivity always shows the login form
        runBlocking { ctx.authDataStore.edit { it.clear() } }

        // Go to home screen then relaunch via am start (guarantees foreground on Android 16)
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am start -n $pkg/.LoginActivity")

        // Dismiss Android 16 "16KB page size" compatibility warning dialog if it appears
        val compatWarning: UiObject2? = device.wait(Until.findObject(By.text("Android App Compatibility")), 4_000)
        if (compatWarning != null) {
            device.findObject(By.text("Don't Show Again"))?.click()
            device.waitForIdle()
        }

        // Wait for our package to be visible in the foreground
        device.wait(Until.hasObject(By.pkg(pkg)), 10_000)
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
