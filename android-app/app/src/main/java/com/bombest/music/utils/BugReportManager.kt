package com.bombest.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.view.View
import androidx.core.view.drawToBitmap
import com.bombest.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object BugReportManager {

    private const val REPO = "Bombest-Audio/bombest-beats"
    private const val LOG_CHAR_LIMIT = 30_000

    data class Result(
        val issueUrl: String?,
        val screenshotFile: File?,
        val error: String? = null
    )

    suspend fun generate(context: Context, view: View): Result = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val logs = collectLogcat()
        val bitmap = withContext(Dispatchers.Main) { captureView(view) }
        val screenshotFile = bitmap?.let { saveBitmap(context, it, timestamp) }
        val body = buildIssueBody(context, logs, screenshotFile)

        val issueUrl = runCatching {
            postGitHubIssue("Bug Report – $timestamp", body)
        }.getOrNull()

        Result(issueUrl = issueUrl, screenshotFile = screenshotFile)
    }

    private fun collectLogcat(): String = runCatching {
        val pid = android.os.Process.myPid().toString()
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500", "--pid=$pid"))
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        text.takeLast(LOG_CHAR_LIMIT)
    }.getOrElse { "Failed to collect logcat: ${it.message}" }

    private fun captureView(view: View): Bitmap? = runCatching {
        view.drawToBitmap()
    }.getOrNull()

    private fun saveBitmap(context: Context, bitmap: Bitmap, timestamp: String): File? = runCatching {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return@runCatching null
        val file = File(dir, "bug_report_$timestamp.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    }.getOrNull()

    private fun buildIssueBody(context: Context, logs: String, screenshot: File?): String {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return buildString {
            appendLine("## Device Info")
            appendLine("| Field | Value |")
            appendLine("|---|---|")
            appendLine("| Device | ${Build.MANUFACTURER} ${Build.MODEL} |")
            appendLine("| Android | ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |")
            appendLine("| App version | ${pkg?.versionName} (${pkg?.longVersionCode}) |")
            if (screenshot != null) appendLine("| Screenshot | Saved on device: `${screenshot.absolutePath}` |")
            appendLine()
            appendLine("## Logcat")
            appendLine("```")
            appendLine(logs)
            appendLine("```")
        }
    }

    private fun postGitHubIssue(title: String, body: String): String? {
        val token = BuildConfig.GITHUB_BUG_REPORT_TOKEN
        if (token.isBlank()) return null

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
            put("labels", JSONArray().apply { put("bug"); put("auto-report") })
        }.toString()

        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/issues")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) JSONObject(resp.body!!.string()).getString("html_url") else null
        }
    }
}
