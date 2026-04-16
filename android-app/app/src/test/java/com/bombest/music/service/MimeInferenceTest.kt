package com.bombest.music.service

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Pure-JVM parametrized test for extension→MIME mapping. Covers the cases we saw bite users
 * in prod (FLAC served with `audio/mp4` → silent playback failure) plus the fall-through
 * defaults.
 *
 * Must agree with the backend's `_PASS_THROUGH_CONTENT_TYPE` in upload_server.py — if that
 * map changes, this test's expected column needs to move with it.
 */
@RunWith(Parameterized::class)
class MimeInferenceTest(
    private val path: String?,
    private val expected: String,
    private val description: String,
) {
    @Test
    fun `extension maps to expected MIME`() {
        val actual = MimeInference.inferRemoteMimeType(path)
        assertEquals(description, expected, actual)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{2}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("track.flac", MimeTypes.AUDIO_FLAC, "flac → AUDIO_FLAC"),
            arrayOf("TRACK.FLAC", MimeTypes.AUDIO_FLAC, "case-insensitive flac"),
            arrayOf("song.mp3", MimeTypes.AUDIO_MPEG, "mp3 → AUDIO_MPEG"),
            arrayOf("song.m4a", MimeTypes.AUDIO_MP4, "m4a → AUDIO_MP4"),
            arrayOf("song.mp4", MimeTypes.AUDIO_MP4, "mp4 → AUDIO_MP4"),
            arrayOf("song.aac", MimeTypes.AUDIO_MP4, "aac → AUDIO_MP4"),
            arrayOf("song.wav", MimeTypes.AUDIO_WAV, "wav → AUDIO_WAV"),
            arrayOf("song.ogg", MimeTypes.AUDIO_OGG, "ogg → AUDIO_OGG"),
            arrayOf("song.oga", MimeTypes.AUDIO_OGG, "oga → AUDIO_OGG"),
            arrayOf("song.xyz", MimeTypes.AUDIO_MPEG, "unknown → AUDIO_MPEG fallback"),
            arrayOf("noextension", MimeTypes.AUDIO_MPEG, "no extension → AUDIO_MPEG fallback"),
            arrayOf(null, MimeTypes.AUDIO_MPEG, "null → AUDIO_MPEG fallback"),
            arrayOf("", MimeTypes.AUDIO_MPEG, "empty string → AUDIO_MPEG fallback"),
            arrayOf("/path/to/nested/song.flac", MimeTypes.AUDIO_FLAC, "nested path still resolves"),
        )
    }
}
