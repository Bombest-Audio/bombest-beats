package com.bombest.music.service

import androidx.media3.common.MimeTypes

/**
 * Pure function: map a remote audio path's file extension to the Media3 MIME type that
 * ExoPlayer should present to its extractor. Extracted from [BombestMediaService] so it
 * can be unit-tested without instantiating the whole service.
 *
 * This MUST agree with the backend's `_PASS_THROUGH_CONTENT_TYPE` map in upload_server.py;
 * a mismatch causes ExoPlayer to pick the wrong extractor and playback silently fails
 * (observed in field reports where FLAC files were sent with `audio/mp4`).
 *
 * Falls back to MPEG (MP3) for unknown extensions — the legacy library is mostly MP3,
 * and MPEG is what the server sends in `?transcode=aac` responses packaged as MP4 anyway
 * (ExoPlayer's MP4 extractor handles both).
 */
object MimeInference {
    fun inferRemoteMimeType(path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "flac" -> MimeTypes.AUDIO_FLAC
            "mp3" -> MimeTypes.AUDIO_MPEG
            "m4a", "mp4", "aac" -> MimeTypes.AUDIO_MP4
            "wav" -> MimeTypes.AUDIO_WAV
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            else -> MimeTypes.AUDIO_MPEG
        }
    }
}
