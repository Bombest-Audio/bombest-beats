package com.bombest.music.model

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class TrackItem(
    val id: Int,
    val title: String,
    val artist: String? = null,
    val streamUrl: String
) : Parcelable


