package com.bombest.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class S3Repository(
    private val bucketBaseUrl: String = "https://bombest-beats-music.s3.us-west-2.amazonaws.com",
    private val client: OkHttpClient = OkHttpClient()
) {

    suspend fun fetchTracks(): List<Track> = withContext(Dispatchers.IO) {
        val url = "$bucketBaseUrl/?list-type=2&prefix=music/"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("S3 list failed: ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            parseListObjects(body)
        }
    }

    private fun parseListObjects(xml: String): List<Track> {
        val tracks = mutableListOf<Track>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var eventType = parser.eventType
        var currentKey: String? = null
        var size: Long? = null
        var lastModified: Long? = null

        fun flush() {
            val key = currentKey ?: return
            if (key.endsWith("/")) return // skip directories
            val title = key.substringAfterLast("/").substringBeforeLast(".mp3", key.substringAfterLast("/"))
            val url = "$bucketBaseUrl/$key"
            tracks.add(
                Track(
                    id = key,
                    title = title,
                    s3Url = url,
                    size = size,
                    lastModified = lastModified
                )
            )
            currentKey = null
            size = null
            lastModified = null
        }

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "Contents" -> {
                            currentKey = null
                            size = null
                            lastModified = null
                        }
                        "Key" -> currentKey = parser.nextText()
                        "Size" -> size = parser.nextText().toLongOrNull()
                        "LastModified" -> lastModified = parser.nextText()?.let { parseIso8601(it) }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "Contents") {
                    flush()
                }
            }
            eventType = parser.next()
        }
        return tracks
    }

    private fun parseIso8601(value: String?): Long? {
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}


