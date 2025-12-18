package com.bombest.music.ui.screens

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bombest.music.R
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.authDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Composable
fun LibraryScreen(
    playlist: List<MediaItem>,
    onTrackClick: (MediaItem) -> Unit,
    currentMediaItem: MediaItem?,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onDelete: (MediaItem) -> Unit = {}
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
    
    // Delete dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<MediaItem?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    
    // Nested scroll connection for pull-to-refresh
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // If we have a pull offset and user is scrolling up (negative), consume it
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
                // If we're at the top and user pulls down (positive), build up pull offset
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
                // When user releases, check if we should trigger refresh
                if (pullOffset >= refreshThreshold && !isRefreshing) {
                    pullOffset = 0f  // Reset immediately
                    onRefresh()
                } else {
                    pullOffset = 0f
                }
                return Velocity.Zero
            }
        }
    }

    // Delete function
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
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                
                val request = Request.Builder()
                    .url("https://bom.best/beats/api/track/$trackId")
                    .addHeader("Authorization", "Bearer $token")
                    .delete()
                    .build()
                
                withContext(Dispatchers.IO) {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d("LibraryScreen", "Track $trackId deleted successfully")
                    } else {
                        val body = response.body?.string() ?: ""
                        Log.e("LibraryScreen", "Delete failed: ${response.code} - $body")
                        deleteError = when (response.code) {
                            401 -> "Unauthorized"
                            403 -> "Admin access required"
                            404 -> "Track not found"
                            else -> "Error ${response.code}"
                        }
                    }
                    response.close()
                }
                
                showDeleteDialog = false
                val deletedItem = trackToDelete
                trackToDelete = null
                
                // Remove from local list after successful delete
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
    
    // Delete confirmation dialog
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        // Content with offset for pull effect
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .offset(y = with(density) { pullOffset.toDp() }),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(playlist) { item ->
                TrackItem(
                    item = item,
                    isPlaying = item.mediaId == currentMediaItem?.mediaId,
                    onClick = { onTrackClick(item) },
                    onLongClick = {
                        trackToDelete = item
                        showDeleteDialog = true
                        deleteError = null
                    }
                )
            }
        }
        
        // Pull/Refresh indicator at top - always visible when pulling or refreshing
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = with(density) { (pullOffset - 60f).coerceAtLeast(0f).toDp() }),
            contentAlignment = Alignment.TopCenter
        ) {
            if (isRefreshing || pullOffset > 10) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E)),
                    shape = androidx.compose.foundation.shape.CircleShape,
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
                            // Show progress toward refresh threshold
                            CircularProgressIndicator(
                                progress = (pullOffset / refreshThreshold).coerceIn(0f, 1f),
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
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val containerColor = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface
    val contentColor = if (isPlaying) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
    
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
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
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            )
            
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
            
            if (isPlaying) {
                 // Animated visualizer placeholder or icon
                 Text("II", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
