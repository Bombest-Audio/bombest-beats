package com.bombest.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bombest.music.data.api.Playlist
import com.bombest.music.data.api.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    isLoading: Boolean,
    onCreatePlaylist: (String) -> Unit,
    onPlaylistClick: (Int) -> Unit,
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
                    text = "${playlist.count} tracks",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
            
            Row {
                IconButton(onClick = onClick) {
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
    isLoading: Boolean,
    onTrackClick: (Track) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onAddTracks: (List<Int>) -> Unit = {},
    onBack: () -> Unit
) {
    var showAddTracksDialog by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf(setOf<Int>()) }
    
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
    
    // Add Tracks Dialog
    if (showAddTracksDialog) {
        val existingTrackIds = tracks.map { it.id }.toSet()
        val availableTracks = allTracks.filter { it.id !in existingTrackIds }
        
        AlertDialog(
            onDismissRequest = { 
                showAddTracksDialog = false 
                selectedTrackIds = emptySet()
            },
            title = { Text("Add Tracks", color = Color.White) },
            text = {
                if (availableTracks.isEmpty()) {
                    Text("All tracks are already in this playlist", color = Color.Gray)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(availableTracks) { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedTrackIds = if (track.id in selectedTrackIds) {
                                            selectedTrackIds - track.id
                                        } else {
                                            selectedTrackIds + track.id
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = track.id in selectedTrackIds,
                                    onCheckedChange = { checked ->
                                        selectedTrackIds = if (checked) {
                                            selectedTrackIds + track.id
                                        } else {
                                            selectedTrackIds - track.id
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFFE90060)
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = track.title,
                                        color = Color.White,
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
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedTrackIds.isNotEmpty()) {
                            onAddTracks(selectedTrackIds.toList())
                        }
                        showAddTracksDialog = false
                        selectedTrackIds = emptySet()
                    },
                    enabled = selectedTrackIds.isNotEmpty()
                ) {
                    Text("Add ${if (selectedTrackIds.isNotEmpty()) "(${selectedTrackIds.size})" else ""}", 
                         color = if (selectedTrackIds.isNotEmpty()) Color(0xFFE90060) else Color.Gray)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddTracksDialog = false 
                    selectedTrackIds = emptySet()
                }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1D2E)
        )
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
