package com.bombest.music.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bombest.music.R
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.NetworkModule
import com.bombest.music.data.authDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    playlist: List<MediaItem>,
    onTrackClick: (MediaItem) -> Unit,
    currentMediaItem: MediaItem?,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onDelete: (MediaItem) -> Unit = {},
    libraryState: com.bombest.music.ui.viewmodel.MainViewModel.LibraryState =
        com.bombest.music.ui.viewmodel.MainViewModel.LibraryState.LOADED,
    playbackError: String? = null,
    onDismissError: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Pull-to-refresh state
    var pullOffset by remember { mutableFloatStateOf(0f) }
    val refreshThreshold = 120f
    val density = LocalDensity.current

    // Reset offset when refresh completes
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) {
            pullOffset = 0f
        }
    }

    // Multi-select state
    val selectedIds = remember { mutableStateListOf<String>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    // Back press exits selection mode
    BackHandler(enabled = isSelectionMode) {
        selectedIds.clear()
    }

    // Delete dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<MediaItem?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    // Bulk delete state
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var bulkDeleteProgress by remember { mutableIntStateOf(0) }
    var bulkDeleteTotal by remember { mutableIntStateOf(0) }
    var bulkDeleteErrors by remember { mutableIntStateOf(0) }

    // Nested scroll connection for pull-to-refresh
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pullOffset > 0 && available.y < 0) {
                    val consumed = if (-available.y >= pullOffset) {
                        val old = pullOffset
                        pullOffset = 0f
                        old
                    } else {
                        pullOffset += available.y
                        -available.y
                    }
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y > 0 &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0 &&
                    !isRefreshing) {
                    pullOffset = (pullOffset + available.y * 0.5f).coerceIn(0f, 200f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOffset >= refreshThreshold && !isRefreshing) {
                    pullOffset = 0f
                    onRefresh()
                } else {
                    pullOffset = 0f
                }
                return Velocity.Zero
            }
        }
    }

    // Single delete function
    fun deleteTrack(mediaItem: MediaItem) {
        scope.launch {
            isDeleting = true
            deleteError = null
            try {
                val token = context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
                if (token == null) {
                    deleteError = "Not logged in"
                    isDeleting = false
                    return@launch
                }

                val trackId = mediaItem.mediaId.toIntOrNull()
                if (trackId == null) {
                    deleteError = "Invalid track ID"
                    isDeleting = false
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    NetworkModule.api.deleteTrack(trackId, "Bearer $token")
                }
                if (response.isSuccessful) {
                    Log.d("LibraryScreen", "Track $trackId deleted successfully")
                } else {
                    Log.e("LibraryScreen", "Delete failed: ${response.code()}")
                    deleteError = when (response.code()) {
                        401 -> "Unauthorized"
                        403 -> "Admin access required"
                        404 -> "Track not found"
                        else -> "Error ${response.code()}"
                    }
                }

                showDeleteDialog = false
                val deletedItem = trackToDelete
                trackToDelete = null

                if (deleteError == null && deletedItem != null) {
                    onDelete(deletedItem)
                }
            } catch (e: Exception) {
                Log.e("LibraryScreen", "Delete error", e)
                deleteError = e.message ?: "Failed to delete"
            }
            isDeleting = false
        }
    }

    // Bulk delete function
    fun bulkDeleteTracks() {
        val itemsToDelete = playlist.filter { it.mediaId in selectedIds }
        if (itemsToDelete.isEmpty()) return

        scope.launch {
            isDeleting = true
            deleteError = null
            bulkDeleteProgress = 0
            bulkDeleteTotal = itemsToDelete.size
            bulkDeleteErrors = 0

            try {
                val token = context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
                if (token == null) {
                    deleteError = "Not logged in"
                    isDeleting = false
                    return@launch
                }

                val deleted = mutableListOf<MediaItem>()

                for (item in itemsToDelete) {
                    val trackId = item.mediaId.toIntOrNull() ?: continue

                    try {
                        val response = withContext(Dispatchers.IO) {
                            NetworkModule.api.deleteTrack(trackId, "Bearer $token")
                        }
                        if (response.isSuccessful) {
                            deleted.add(item)
                        } else {
                            bulkDeleteErrors++
                            Log.e("LibraryScreen", "Bulk delete failed for $trackId: ${response.code()}")
                        }
                    } catch (e: Exception) {
                        bulkDeleteErrors++
                        Log.e("LibraryScreen", "Bulk delete error for $trackId", e)
                    }
                    bulkDeleteProgress++
                }

                // Remove from local list
                for (item in deleted) {
                    onDelete(item)
                }
                selectedIds.clear()
                showBulkDeleteDialog = false

                if (bulkDeleteErrors > 0) {
                    deleteError = "Deleted ${deleted.size}/${itemsToDelete.size} tracks ($bulkDeleteErrors failed)"
                }
            } catch (e: Exception) {
                Log.e("LibraryScreen", "Bulk delete error", e)
                deleteError = e.message ?: "Failed to delete"
            }
            isDeleting = false
        }
    }

    // Single delete confirmation dialog
    if (showDeleteDialog && trackToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    showDeleteDialog = false
                    trackToDelete = null
                }
            },
            title = { Text("Delete Track?", color = Color.White) },
            text = {
                Column {
                    Text(
                        "\"${trackToDelete?.mediaMetadata?.title}\"",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This will remove the track from the library. This cannot be undone.",
                        color = Color.Gray
                    )
                    deleteError?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, color = Color.Red)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { trackToDelete?.let { deleteTrack(it) } },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        trackToDelete = null
                    },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1A1D2E)
        )
    }

    // Bulk delete confirmation dialog
    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    showBulkDeleteDialog = false
                }
            },
            title = { Text("Delete ${selectedIds.size} Tracks?", color = Color.White) },
            text = {
                Column {
                    Text(
                        "This will remove ${selectedIds.size} tracks from the library. This cannot be undone.",
                        color = Color.Gray
                    )
                    if (isDeleting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (bulkDeleteTotal > 0) bulkDeleteProgress.toFloat() / bulkDeleteTotal else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFE90060),
                            trackColor = Color(0xFF2A2D3E)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Deleting $bulkDeleteProgress/$bulkDeleteTotal...",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    deleteError?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, color = Color.Red)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { bulkDeleteTracks() },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Delete All")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBulkDeleteDialog = false },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1A1D2E)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .semantics { contentDescription = "Library" }
    ) {
        // Playback error snackbar
        if (playbackError != null) {
            Snackbar(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                action = {
                    TextButton(onClick = onDismissError) {
                        Text("Dismiss", color = Color.White)
                    }
                },
                containerColor = Color(0xFFB71C1C)
            ) {
                Text(playbackError, color = Color.White)
            }
        }

        // Loading / error / empty states
        when (libraryState) {
            com.bombest.music.ui.viewmodel.MainViewModel.LibraryState.LOADING -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF6C63FF))
                }
                return@Box
            }
            com.bombest.music.ui.viewmodel.MainViewModel.LibraryState.ERROR -> {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Couldn't load library", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Check your connection and try again", color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRefresh) { Text("Retry") }
                }
                return@Box
            }
            com.bombest.music.ui.viewmodel.MainViewModel.LibraryState.EMPTY -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tracks yet", color = Color.Gray)
                }
                return@Box
            }
            com.bombest.music.ui.viewmodel.MainViewModel.LibraryState.LOADED -> { /* fall through to LazyColumn */ }
        }

        // Content with offset for pull effect
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .offset(y = with(density) { pullOffset.toDp() }),
            contentPadding = PaddingValues(
                top = if (isSelectionMode) 64.dp else 16.dp,
                bottom = 100.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(playlist) { item ->
                TrackItem(
                    item = item,
                    isPlaying = item.mediaId == currentMediaItem?.mediaId,
                    isSelected = item.mediaId in selectedIds,
                    isSelectionMode = isSelectionMode,
                    onClick = {
                        if (isSelectionMode) {
                            if (item.mediaId in selectedIds) {
                                selectedIds.remove(item.mediaId)
                            } else {
                                selectedIds.add(item.mediaId)
                            }
                        } else {
                            onTrackClick(item)
                        }
                    },
                    onLongClick = {
                        if (isSelectionMode) {
                            if (item.mediaId in selectedIds) {
                                selectedIds.remove(item.mediaId)
                            } else {
                                selectedIds.add(item.mediaId)
                            }
                        } else {
                            selectedIds.add(item.mediaId)
                        }
                    }
                )
            }
        }

        // Selection action bar
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it }
        ) {
            Surface(
                color = Color(0xFF1A1D2E),
                tonalElevation = 8.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedIds.clear() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                    Text(
                        "${selectedIds.size} selected",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    // Select all / deselect all
                    IconButton(onClick = {
                        val playableIds = playlist
                            .filter { it.mediaMetadata.isPlayable == true }
                            .map { it.mediaId }
                        if (selectedIds.size == playableIds.size) {
                            selectedIds.clear()
                        } else {
                            selectedIds.clear()
                            selectedIds.addAll(playableIds)
                        }
                    }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Select All", tint = Color.White)
                    }
                    // Delete selected
                    IconButton(onClick = {
                        deleteError = null
                        showBulkDeleteDialog = true
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = Color.Red)
                    }
                }
            }
        }

        // Pull/Refresh indicator at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = with(density) { (pullOffset - 60f).coerceAtLeast(0f).toDp() }),
            contentAlignment = Alignment.TopCenter
        ) {
            if (isRefreshing || pullOffset > 10) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E)),
                    shape = CircleShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color(0xFFE90060),
                                strokeWidth = 2.dp
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = { (pullOffset / refreshThreshold).coerceIn(0f, 1f) },
                                modifier = Modifier.size(24.dp),
                                color = if (pullOffset >= refreshThreshold) Color(0xFF4CAF50) else Color(0xFFE90060),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackItem(
    item: MediaItem,
    isPlaying: Boolean,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val containerColor = when {
        isSelected -> Color(0xFF2A1D3E)
        isPlaying -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        isSelected -> Color.White
        isPlaying -> MaterialTheme.colorScheme.onSecondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderMod = if (isSelected) {
        Modifier.border(2.dp, Color(0xFFE90060), RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(borderMod)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox or album art
            if (isSelectionMode) {
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.mediaMetadata.artworkUri ?: R.drawable.default_album_art)
                            .error(R.drawable.default_album_art)
                            .placeholder(R.drawable.default_album_art)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = Color(0xFFE90060),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.mediaMetadata.artworkUri ?: R.drawable.default_album_art)
                        .error(R.drawable.default_album_art)
                        .placeholder(R.drawable.default_album_art)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Album Art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.mediaMetadata.title?.toString() ?: "Unknown",
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }

            if (isPlaying && !isSelectionMode) {
                 Text("II", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
