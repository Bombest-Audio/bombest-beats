package com.bombest.musify.data.remote.token

import com.bombest.musify.BuildConfig
import com.bombest.musify.data.encoder.Base64Encoder

/**
 * A function that uses the [base64Encoder] to get an encoded
 * spotify client secret.
 */
fun getSpotifyClientSecret(base64Encoder: Base64Encoder): String {
    // S3-only mode: Spotify credentials not needed
    val clientId = ""
    val clientSecret = ""
    val encodedString = base64Encoder.encodeToString("$clientId:$clientSecret".toByteArray())
    return "Basic $encodedString"
}
