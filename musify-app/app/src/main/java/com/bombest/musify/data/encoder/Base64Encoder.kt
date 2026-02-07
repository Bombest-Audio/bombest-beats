package com.bombest.musify.data.encoder

fun interface Base64Encoder {
    fun encodeToString(input: ByteArray): String
}