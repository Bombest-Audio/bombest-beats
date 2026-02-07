package com.bombest.musify.data.repositories.genresrepository

import com.bombest.musify.domain.Genre
import javax.inject.Inject

class MusifyGenresRepository @Inject constructor() : GenresRepository {
    // For S3, we don't have genre information, return empty list
    override fun fetchAvailableGenres(): List<Genre> = emptyList()
}