package com.bombest.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bombest.music.R
import kotlin.math.abs

/**
 * Persistent Mini Player - Spotify-like bottom bar
 * 
 * Features:
 * - Always visible when track is loaded
 * - Rounded album art thumbnail
 * - Track title & artist (ellipsized)
 * - Play/Pause toggle with animation
 * - Next button
 * - Tap to expand to full Now Playing
 * - Swipe gestures for skip (optional)
 * - Graffiti theme gradient accent
 * - Progress indicator line
 */
@Composable
fun PlayerBar(
    currentMediaItem: MediaItem?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit = {},
    onClick: () -> Unit,
    progress: Float = 0f,  // 0.0 to 1.0 for progress bar
    modifier: Modifier = Modifier
) {
    if (currentMediaItem == null) return
    
    // Swipe state for gesture-based skip
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 100f
    
    // Play button animation
    val playButtonScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 1.05f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        label = "playScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        // Main card with shadow
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset > swipeThreshold) {
                                onPrevious()
                            } else if (swipeOffset < -swipeThreshold) {
                                onNext()
                            }
                            swipeOffset = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeOffset += dragAmount
                        }
                    )
                },
            color = Color(0xFF1A1D2E),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                // Progress indicator line at top
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0xFF2A2F42))
                ) {
                    // Gradient progress line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFFFF6B35),  // Orange
                                        Color(0xFFE90060),  // Magenta
                                        Color(0xFF8B5CF6)   // Purple
                                    )
                                )
                            )
                    )
                }
                
                // Main content row
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .graphicsLayer { translationX = swipeOffset * 0.3f },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Album Art (Rounded square)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(52.dp),
                        shadowElevation = 4.dp
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(currentMediaItem.mediaMetadata.artworkUri ?: R.drawable.default_album_art)
                                .error(R.drawable.default_album_art)
                                .placeholder(R.drawable.default_album_art)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Album Art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // Title and Artist
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = currentMediaItem.mediaMetadata.title?.toString()
                                ?.takeUnless { currentMediaItem.mediaId.startsWith("special:") }
                                ?: "Unknown",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentMediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Play/Pause Button - Prominent
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .graphicsLayer { 
                                scaleX = playButtonScale
                                scaleY = playButtonScale
                            }
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFE90060), Color(0xFFFF6B35))
                                )
                            )
                            .clickable(onClick = onPlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Next Button
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
