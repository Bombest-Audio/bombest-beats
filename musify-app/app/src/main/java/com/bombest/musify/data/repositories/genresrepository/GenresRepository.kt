package com.bombest.musify.data.repositories.genresrepository

import com.bombest.musify.domain.Genre

/**
 * A repository that contains all methods related to genres.
 */
interface GenresRepository {
    fun fetchAvailableGenres(): List<Genre>
}