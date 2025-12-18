package com.bombest.music.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.bombest.music.data.api.Playlist
import com.bombest.music.data.api.Track
import com.bombest.music.data.NetworkModule
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    isLoading: Boolean,
    onCreatePlaylist: (String) -> Unit,
    onPlaylistClick: (Int) -> Unit,
    onPlayPlaylist: (Int, String) -> Unit = { _, _ -> },
    onDeletePlaylist: (Int) -> Unit,
    onBack: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create Playlist", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF15192A),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0A0D14)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFFE90060)
                )
            } else if (playlists.isEmpty()) {
                Text(
                    text = "No playlists yet.\nTap + to create one!",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(playlists) { playlist ->
                        PlaylistItem(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.id) },
                            onPlay = { onPlayPlaylist(playlist.id, playlist.name) },
                            onDelete = { onDeletePlaylist(playlist.id) }
                        )
                    }
                }
            }
        }
    }
    
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE90060),
                        cursorColor = Color(0xFFE90060)
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylist(newPlaylistName)
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Create", color = Color(0xFFE90060))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1A1D2E)
        )
    }
}

@Composable
fun PlaylistItem(
    playlist: Playlist,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.count} ${if (playlist.count == 1) "track" else "tracks"}",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
            
            Row {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color(0xFFE90060))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Int = 0,
    playlistName: String,
    tracks: List<Track>,
    allTracks: List<Track> = emptyList(),
    stagedTrackIds: List<Int> = emptyList(),
    isLoading: Boolean,
    onTrackClick: (Track) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onToggleStage: (Int) -> Unit = {},
    onAddTracks: (List<Int>) -> Unit = {},
    onDismiss: () -> Unit = {},
    onBack: () -> Unit
) {
    var showAddTracksDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlistName, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (tracks.isNotEmpty()) {
                        IconButton(onClick = { onTrackClick(tracks[0]) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play All", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { showAddTracksDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Tracks", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF15192A),
                    titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddTracksDialog = true },
                containerColor = Color(0xFFE90060)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Tracks", tint = Color.White)
            }
        },
        containerColor = Color(0xFF0A0D14)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFFE90060)
                )
            } else if (tracks.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No tracks in this playlist",
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showAddTracksDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE90060))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Tracks")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tracks) { track ->
                        PlaylistTrackItem(
                            track = track,
                            onClick = { onTrackClick(track) },
                            onRemove = { onRemoveTrack(track.id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddTracksDialog) {
        val existingTrackIds = tracks.map { it.id }.toSet()
        val availableTracks = allTracks.filter { it.id !in existingTrackIds }
        
        DragAndDropTrackPicker(
            availableTracks = availableTracks,
            stagedTrackIds = stagedTrackIds,
            onToggleStage = onToggleStage,
            onAddTracks = {
                onAddTracks(stagedTrackIds.toList())
                showAddTracksDialog = false
            },
            onDismiss = {
                onDismiss()
                showAddTracksDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragAndDropTrackPicker(
    availableTracks: List<Track>,
    stagedTrackIds: List<Int>,
    onToggleStage: (Int) -> Unit,
    onAddTracks: () -> Unit,
    onDismiss: () -> Unit
) {
    var draggingTrackId by remember { mutableStateOf<Int?>(null) }
    var fingerPosition by remember { mutableStateOf(Offset.Zero) }
    var bucketPosition by remember { mutableStateOf(Offset.Zero) }
    var bucketSize by remember { mutableStateOf(IntSize.Zero) }
    var isHoveringBucket by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(true) }
    
    val bucketScale by animateFloatAsState(if (isHoveringBucket) 1.2f else 1f)
    val bucketColor by animateColorAsState(if (isHoveringBucket) Color(0xFFE90060) else Color(0xFF1A1D2E))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D14))
            .zIndex(100f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Drag Tracks to Bucket", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                            contentDescription = "Toggle View",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF15192A),
                    titleContentColor = Color.White
                )
            )
            
            if (availableTracks.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("No more tracks to add", color = Color.Gray)
                }
            } else {
                Box(modifier = Modifier.weight(0.7f)) {
                    if (isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(availableTracks, key = { it.id }) { track ->
                                val isStaged = stagedTrackIds.contains(track.id)
                                TrackGridItem(
                                    track = track,
                                    isStaged = isStaged,
                                    bucketPosition = bucketPosition,
                                    bucketSize = bucketSize,
                                    onDragStart = { globalTouchPosition ->
                                        draggingTrackId = track.id
                                        fingerPosition = globalTouchPosition
                                    },
                                    onDrag = { globalFingerPosition -> fingerPosition = globalFingerPosition },
                                    onDragEnd = {
                                        if (isHoveringBucket && !isStaged) onToggleStage(track.id)
                                        draggingTrackId = null
                                        isHoveringBucket = false
                                    },
                                    onHoverChange = { hovering -> isHoveringBucket = hovering }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(availableTracks, key = { it.id }) { track ->
                                val isStaged = stagedTrackIds.contains(track.id)
                                TrackListItem(
                                    track = track,
                                    isStaged = isStaged,
                                    bucketPosition = bucketPosition,
                                    bucketSize = bucketSize,
                                    onDragStart = { globalTouchPosition ->
                                        draggingTrackId = track.id
                                        fingerPosition = globalTouchPosition
                                    },
                                    onDrag = { globalFingerPosition -> fingerPosition = globalFingerPosition },
                                    onDragEnd = {
                                        if (isHoveringBucket && !isStaged) onToggleStage(track.id)
                                        draggingTrackId = null
                                        isHoveringBucket = false
                                    },
                                    onHoverChange = { hovering -> isHoveringBucket = hovering }
                                )
                            }
                        }
                    }
                }
            }
            
            // Bucket Area (Bottom 30%)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.3f)
                    .onGloballyPositioned { 
                        bucketPosition = it.positionInRoot()
                        bucketSize = it.size
                    }
                    .background(Color(0xFF0F121B))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .graphicsLayer(scaleX = bucketScale, scaleY = bucketScale)
                            .shadow(if (isHoveringBucket) 20.dp else 0.dp, RoundedCornerShape(50))
                            .background(bucketColor, RoundedCornerShape(50))
                            .border(2.dp, if (isHoveringBucket) Color.White else Color.Transparent, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = if (stagedTrackIds.isEmpty()) "Drag tracks here" else "${stagedTrackIds.size} tracks staged",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (stagedTrackIds.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onAddTracks,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE90060)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Done, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Changes")
                        }
                    }
                }
            }
        }
        
        // Floating Dragged Item
        draggingTrackId?.let { id ->
            val track = availableTracks.find { it.id == id }
            track?.let {
                val boxSize = with(LocalDensity.current) { 100.dp.toPx() }
                Box(
                    modifier = Modifier
                        .offset { 
                            IntOffset(
                                (fingerPosition.x - boxSize / 2).roundToInt(), 
                                (fingerPosition.y - boxSize / 2).roundToInt()
                            ) 
                        }
                        .size(100.dp)
                        .shadow(10.dp, RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A1D2E), RoundedCornerShape(8.dp))
                        .zIndex(200f),
                    contentAlignment = Alignment.Center
                ) {
                    TrackArt(track = it, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
fun TrackListItem(
    track: Track,
    isStaged: Boolean,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onHoverChange: (Boolean) -> Unit,
    bucketPosition: Offset,
    bucketSize: IntSize
) {
    var itemPosition by remember { mutableStateOf(Offset.Zero) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { itemPosition = it.positionInRoot() }
            .pointerInput(bucketPosition, bucketSize) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { touchOffset -> onDragStart(itemPosition + touchOffset) },
                    onDrag = { change, _ ->
                        change.consume()
                        onDrag(itemPosition + change.position)
                        val globalPointerY = itemPosition.y + change.position.y
                        onHoverChange(globalPointerY > bucketPosition.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isStaged) Color(0xFFE90060).copy(alpha = 0.2f) else Color(0xFF1A1D2E)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(50.dp)) {
                TrackArt(track = track, modifier = Modifier.fillMaxSize())
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isStaged) {
                Icon(Icons.Default.Done, contentDescription = null, tint = Color(0xFFE90060))
            }
        }
    }
}

@Composable
fun TrackGridItem(
    track: Track,
    isStaged: Boolean,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onHoverChange: (Boolean) -> Unit,
    bucketPosition: Offset,
    bucketSize: IntSize
) {
    var itemPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isStaged) Color(0xFFE90060).copy(alpha = 0.3f) else Color(0xFF1A1D2E))
            .onGloballyPositioned { itemPosition = it.positionInRoot() }
            .pointerInput(bucketPosition, bucketSize) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { touchOffset -> onDragStart(itemPosition + touchOffset) },
                    onDrag = { change, _ ->
                        change.consume()
                        onDrag(itemPosition + change.position)
                        
                        // Accurate hover detection
                        val globalPointerY = itemPosition.y + change.position.y
                        onHoverChange(globalPointerY > bucketPosition.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        TrackArt(track = track, modifier = Modifier.fillMaxSize())
        
        // Semi-transparent overlay with Title/Artist so it's not "empty"
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                        startY = 50f
                    )
                )
                .padding(8.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        if (isStaged) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFE90060).copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Done, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun TrackArt(track: Track, modifier: Modifier = Modifier) {
    val artUrl = if (track.album_id != null) {
        "${NetworkModule.currentBaseUrl}/album/${track.album_id}/art"
    } else null

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (artUrl != null) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Textual Fallback for "Empty Tiles"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF25293E)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = track.title.take(1).uppercase(),
                    color = Color.White.copy(alpha = 0.2f),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
fun PlaylistTrackItem(
    track: Track,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = Color.Gray,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Gray)
            }
        }
    }
}
