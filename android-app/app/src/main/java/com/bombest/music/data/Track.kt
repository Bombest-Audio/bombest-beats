package com.bombest.music.data

data class Track(
    val id: String,
    val title: String,
    val s3Url: String,
    val size: Long? = null,
    val lastModified: Long? = null
)


