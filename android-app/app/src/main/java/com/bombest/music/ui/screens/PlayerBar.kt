package com.bombest.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bombest.music.ui.theme.BombestIcons
import com.bombest.music.ui.theme.BombestRed

@Composable
fun PlayerBar(
    currentMediaItem: MediaItem?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit
) {
    if (currentMediaItem == null) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding() // Avoid bottom nav gesture area
            .clickable(onClick = onClick)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface, 
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art (Rounded)
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    modifier = Modifier.size(60.dp)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(currentMediaItem.mediaMetadata.artworkUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Title and Artist
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentMediaItem.mediaMetadata.title?.toString() ?: "Unknown Title",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentMediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Controls
                // Play Button - Prominent
                FilledIconButton(
                    onClick = onPlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) BombestIcons.Pause else BombestIcons.Play,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = BombestIcons.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
