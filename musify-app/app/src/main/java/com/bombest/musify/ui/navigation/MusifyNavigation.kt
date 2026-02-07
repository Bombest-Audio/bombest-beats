package com.bombest.musify.ui.navigation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.bombest.musify.domain.HomeFeedCarouselCardInfo
import com.bombest.musify.domain.HomeFeedFilters
import com.bombest.musify.domain.SearchResult
import com.bombest.musify.domain.Streamable
import com.bombest.musify.ui.dynamicTheme.dynamicbackgroundmodifier.DynamicBackgroundResource
import com.bombest.musify.ui.dynamicTheme.dynamicbackgroundmodifier.dynamicBackground
import com.bombest.musify.ui.screens.GetPremiumScreen
import com.bombest.musify.ui.screens.homescreen.HomeScreen
import com.bombest.musify.ui.screens.libraryscreen.LibraryScreen
import com.bombest.musify.ui.screens.searchscreen.PagingItemsForSearchScreen
import com.bombest.musify.ui.screens.searchscreen.SearchScreen
import com.bombest.musify.viewmodels.homefeedviewmodel.HomeFeedViewModel
import com.bombest.musify.viewmodels.libraryviewmodel.LibraryViewModel
import com.bombest.musify.viewmodels.searchviewmodel.SearchFilter
import com.bombest.musify.viewmodels.searchviewmodel.SearchScreenUiState
import com.bombest.musify.viewmodels.searchviewmodel.SearchViewModel

@ExperimentalAnimationApi
@ExperimentalMaterialApi
@ExperimentalComposeUiApi
@ExperimentalFoundationApi
@Composable
fun MusifyNavigation(
    navController: NavHostController,
    playStreamable: (Streamable) -> Unit,
    onPausePlayback: () -> Unit,
    isFullScreenNowPlayingOverlayScreenVisible: Boolean
) {
    NavHost(
        navController = navController,
        startDestination = MusifyBottomNavigationDestinations.Library.route
    ) {
        navGraphWithDetailScreens(
            navGraphRoute = MusifyBottomNavigationDestinations.Home.route,
            startDestination = MusifyNavigationDestinations.HomeScreen.route,
            navController = navController,
            playStreamable = playStreamable,
            onPausePlayback = onPausePlayback
        ) { nestedController ->
            homeScreen(
                route = MusifyNavigationDestinations.HomeScreen.route,
                onCarouselCardClicked = {
                    nestedController.navigateToDetailScreen(searchResult = it.associatedSearchResult)
                }
            )
        }
        navGraphWithDetailScreens(
            navGraphRoute = MusifyBottomNavigationDestinations.Search.route,
            startDestination = MusifyNavigationDestinations.SearchScreen.route,
            navController = navController,
            playStreamable = playStreamable,
            onPausePlayback = onPausePlayback
        ) { nestedController ->
            searchScreen(
                route = MusifyNavigationDestinations.SearchScreen.route,
                onSearchResultClicked = nestedController::navigateToDetailScreen,
                isFullScreenNowPlayingScreenOverlayVisible = isFullScreenNowPlayingOverlayScreenVisible
            )
        }

        composable(MusifyBottomNavigationDestinations.Premium.route) {
            GetPremiumScreen()
        }

        navGraphWithDetailScreens(
            navGraphRoute = MusifyBottomNavigationDestinations.Library.route,
            startDestination = MusifyNavigationDestinations.LibraryScreen.route,
            navController = navController,
            playStreamable = playStreamable,
            onPausePlayback = onPausePlayback
        ) { nestedController ->
            libraryScreen(
                route = MusifyNavigationDestinations.LibraryScreen.route,
                onTrackClicked = { track ->
                    playStreamable(track)
                }
            )
        }
    }
}

@ExperimentalMaterialApi
@ExperimentalFoundationApi
private fun NavGraphBuilder.homeScreen(
    route: String,
    onCarouselCardClicked: (HomeFeedCarouselCardInfo) -> Unit
) {
    composable(route) {
        val homeFeedViewModel = hiltViewModel<HomeFeedViewModel>()
        val filters = remember {
            listOf(
                HomeFeedFilters.Music
            )
        }
        HomeScreen(
            timeBasedGreeting = homeFeedViewModel.greetingPhrase,
            homeFeedFilters = filters,
            currentlySelectedHomeFeedFilter = HomeFeedFilters.None,
            onHomeFeedFilterClick = {},
            carousels = homeFeedViewModel.homeFeedCarousels.value,
            onHomeFeedCarouselCardClick = onCarouselCardClicked,
            isErrorMessageVisible = homeFeedViewModel.uiState.value == HomeFeedViewModel.HomeFeedUiState.ERROR,
            isLoading = homeFeedViewModel.uiState.value == HomeFeedViewModel.HomeFeedUiState.LOADING,
            onErrorRetryButtonClick = homeFeedViewModel::refreshFeed
        )
    }
}

@ExperimentalAnimationApi
@ExperimentalFoundationApi
@ExperimentalComposeUiApi
@ExperimentalMaterialApi
private fun NavGraphBuilder.searchScreen(
    route: String,
    onSearchResultClicked: (SearchResult) -> Unit,
    isFullScreenNowPlayingScreenOverlayVisible: Boolean,
) {
    composable(route = route) {
        val viewModel = hiltViewModel<SearchViewModel>()
        val albums = viewModel.albumListForSearchQuery.collectAsLazyPagingItems()
        val artists = viewModel.artistListForSearchQuery.collectAsLazyPagingItems()
        val playlists = viewModel.playlistListForSearchQuery.collectAsLazyPagingItems()
        val tracks = viewModel.trackListForSearchQuery.collectAsLazyPagingItems()
        val pagingItems = remember {
            PagingItemsForSearchScreen(
                albums,
                artists,
                tracks,
                playlists
            )
        }
        val uiState by viewModel.uiState
        val isLoadingError by remember {
            derivedStateOf {
                tracks.loadState.refresh is LoadState.Error || tracks.loadState.append is LoadState.Error || tracks.loadState.prepend is LoadState.Error
            }
        }
        val controller = LocalSoftwareKeyboardController.current
        val genres = remember { viewModel.getAvailableGenres() }
        val filters = remember { SearchFilter.values().toList() }
        val currentlySelectedFilter by viewModel.currentlySelectedFilter
        val dynamicBackgroundResource by remember {
            derivedStateOf {
                val imageUrl = when (currentlySelectedFilter) {
                    SearchFilter.ALBUMS -> albums.itemSnapshotList.firstOrNull()?.albumArtUrlString
                    SearchFilter.TRACKS -> tracks.itemSnapshotList.firstOrNull()?.imageUrlString
                    SearchFilter.ARTISTS -> artists.itemSnapshotList.firstOrNull()?.imageUrlString
                    SearchFilter.PLAYLISTS -> playlists.itemSnapshotList.firstOrNull()?.imageUrlString
                }
                if (imageUrl == null) DynamicBackgroundResource.Empty
                else DynamicBackgroundResource.FromImageUrl(imageUrl)
            }
        }
        val currentlyPlayingTrack by viewModel.currentlyPlayingTrackStream.collectAsState(initial = null)
        Box(modifier = Modifier.dynamicBackground(dynamicBackgroundResource)) {
            SearchScreen(
                genreList = genres,
                searchScreenFilters = filters,
                onGenreItemClick = {},
                onSearchTextChanged = viewModel::search,
                isLoading = uiState == SearchScreenUiState.LOADING,
                pagingItems = pagingItems,
                onSearchQueryItemClicked = onSearchResultClicked,
                currentlySelectedFilter = viewModel.currentlySelectedFilter.value,
                onSearchFilterChanged = viewModel::updateSearchFilter,
                isSearchErrorMessageVisible = isLoadingError,
                onImeDoneButtonClicked = {
                    // Search only if there was an error while loading.
                    // A manual call to search() is not required
                    // when there is no error because, search()
                    // will be called automatically, everytime the
                    // search text changes. This prevents duplicate
                    // calls when the user manually clicks the done
                    // button after typing the search text, in
                    // which case, the keyboard will just be hidden.
                    if (isLoadingError) viewModel.search(it)
                    controller?.hide()
                },
                currentlyPlayingTrack = currentlyPlayingTrack,
                isFullScreenNowPlayingOverlayScreenVisible = isFullScreenNowPlayingScreenOverlayVisible,
                onErrorRetryButtonClick = viewModel::search,
                downloadManager = viewModel.downloadManager,
                onDownloadClick = viewModel::downloadTrack,
                onCancelDownload = viewModel::cancelDownload,
                onRetryDownload = viewModel::retryDownload
            )
        }
    }
}

@ExperimentalMaterialApi
@ExperimentalFoundationApi
private fun NavGraphBuilder.libraryScreen(
    route: String,
    onTrackClicked: (com.bombest.musify.domain.SearchResult.TrackSearchResult) -> Unit
) {
    composable(route) {
        val libraryViewModel = hiltViewModel<LibraryViewModel>()
        val currentlyPlayingTrack by libraryViewModel.currentlyPlayingTrack.collectAsState(initial = null)
        
        LibraryScreen(
            viewModel = libraryViewModel,
            currentlyPlayingTrack = currentlyPlayingTrack,
            onTrackClicked = onTrackClicked
        )
    }
}
