package com.bombest.musify.ui.navigation

import com.bombest.musify.R

sealed class MusifyBottomNavigationDestinations(
    val route: String,
    val label: String,
    val outlinedIconVariantResourceId: Int,
    val filledIconVariantResourceId: Int
) {
    object Home : MusifyBottomNavigationDestinations(
        route = "com.bombest.musify.ui.navigation.bottom.home",
        label = "Home",
        outlinedIconVariantResourceId = R.drawable.ic_outline_home_24,
        filledIconVariantResourceId = R.drawable.ic_filled_home_24
    )

    object Search : MusifyBottomNavigationDestinations(
        route = "com.bombest.musify.ui.navigation.bottom.search",
        label = "Search",
        outlinedIconVariantResourceId = R.drawable.ic_outline_search_24,
        filledIconVariantResourceId = R.drawable.ic_outline_search_24
    )

    object Premium : MusifyBottomNavigationDestinations(
        route = "com.bombest.musify.ui.navigation.bottom.premium",
        label = "Premium",
        outlinedIconVariantResourceId = R.drawable.ic_spotify_premium,
        filledIconVariantResourceId = R.drawable.ic_spotify_premium
    )

    object Library : MusifyBottomNavigationDestinations(
        route = "com.bombest.musify.ui.navigation.bottom.library",
        label = "Library",
        outlinedIconVariantResourceId = R.drawable.ic_outline_library_24,
        filledIconVariantResourceId = R.drawable.ic_filled_library_24
    )
}
