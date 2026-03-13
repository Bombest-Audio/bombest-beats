package com.bombest.music.ui.screens


import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bombest.music.ui.theme.BombestIcons
import com.bombest.music.ui.theme.LocalBombestTheme
import com.bombest.music.ui.theme.VisualizerThemeStyle
import com.bombest.music.ui.theme.ProgressStyle
import com.bombest.music.ui.components.GraffitiVisualizer
import com.bombest.music.ui.components.SprayPaintProgress
import com.bombest.music.visualizer.GraffitiWaveformVisualizer
import com.bombest.music.R
import com.bombest.music.data.NetworkModule
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.atan2

@Composable
fun PlayerScreen(
    currentMediaItem: MediaItem,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    amplitudes: List<Float> = emptyList(),
    isFavorite: Boolean = false,
    isDownloaded: Boolean = false,
    onFavorite: () -> Unit = {},
    onDownload: () -> Unit = {},
    onRemoveDownload: () -> Unit = {},
    onShare: () -> Unit = {},
    onRegisterPasskey: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Full Screen Background - Graffiti theme deep navy
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E23)) // Graffiti deep navy base
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF151933), Color(0xFF0B0E23)),
                    center = Offset.Unspecified,
                    radius = 2000f
                )
            )
            // .windowInsetsPadding(WindowInsets.statusBars) // padding top but let bg flow behind? No, user wants full screen.
            // Actually, for "Full Screen" visual effect, we usually want the background to go BEHIND the status bar.
            // So we shouldn't pad the background Box, but the content inside.
    ) {
        // Canvas background (GIF/video like Spotify Canvas)
        CanvasBackground(trackId = currentMediaItem.mediaId)
        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing), // Ensure content doesn't overlap system bars
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopRow(onClose = onClose, onRegisterPasskey = onRegisterPasskey)

            Spacer(Modifier.height(16.dp))

            ArtworkWithProgress(
                imageSize = 300.dp, // Slightly larger for impact
                currentPosition = currentPosition,
                duration = duration,
                onSeek = onSeek,
                artworkUri = currentMediaItem.mediaMetadata.artworkUri
            )

            Spacer(Modifier.height(24.dp))

            TimeRow(formatDuration(currentPosition), formatDuration(duration))

            Spacer(Modifier.height(32.dp))

            MidControlsRow(
                isFavorite = isFavorite,
                isDownloaded = isDownloaded,
                onFavorite = onFavorite,
                onDownload = onDownload,
                onRemoveDownload = onRemoveDownload,
                onShare = onShare
            )

            Spacer(Modifier.height(32.dp))

            // Graffiti Waveform Visualizer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                GraffitiWaveformVisualizer(
                    amplitudes = amplitudes,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.weight(1f)) // Push controls to bottom

            SongInfo(
                title = currentMediaItem.mediaMetadata.title?.toString() ?: "Unknown",
                artist = currentMediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist"
            )

            Spacer(Modifier.height(32.dp))

            TransportControls(
                isPlaying = isPlaying,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                isShuffleEnabled = isShuffleEnabled,
                repeatMode = repeatMode
            )
            
            Spacer(Modifier.height(32.dp))
        }
    }
}



@Composable
fun TopRow(onClose: () -> Unit, onRegisterPasskey: () -> Unit = {}) {
    var showMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButtonCircle(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color(0xFFF470FF)
            )
        }
        
        Box {
            IconButtonCircle(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = Color(0xFFF470FF)
                )
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(Color(0xFF1A1A2E))
            ) {
                DropdownMenuItem(
                    text = { Text("Add to Playlist", color = Color.White) },
                    onClick = { showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Go to Artist", color = Color.White) },
                    onClick = { showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Go to Album", color = Color.White) },
                    onClick = { showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Sleep Timer", color = Color.White) },
                    onClick = { showMenu = false }
                )
            }
        }
    }
}

@Composable
fun IconButtonCircle(onClick: () -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp) // Android minimum touch target
            .clip(CircleShape)
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            )
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
        content = content
    )
}


@Composable
private fun CanvasBackground(trackId: String) {
    var canvasState by remember { mutableStateOf<Pair<String?, String?>>(null to null) } // url to type
    val context = LocalContext.current
    LaunchedEffect(trackId) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val baseUrl = NetworkModule.getStreamBaseUrl()
                val url = "$baseUrl/track/$trackId/canvas"
                val client = OkHttpClient.Builder().build()
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val type = resp.header("X-Canvas-Type") ?: "gif"
                        canvasState = url to type
                    }
                }
            } catch (_: Exception) {}
        }
    }
    canvasState.let { (url, type) ->
        if (url != null && type != null) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                if (type == "gif") {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (type == "video") {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoURI(android.net.Uri.parse(url))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    mp.setVolume(0f, 0f)
                                    mp.start()
                                }
                            }
                        },
                        onRelease = { it.stopPlayback() }
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                )
            }
        }
    }
}

@Composable
fun ArtworkWithProgress(
    imageSize: Dp,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    artworkUri: android.net.Uri?
) {
    // Scrubbing Logic
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val safeDuration = if (duration > 0) duration else 1L
    val progress = if (isDragging) dragProgress else (currentPosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .size(imageSize)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { 
                        isDragging = false 
                        onSeek((dragProgress * safeDuration).toLong())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val touch = change.position
                        val dx = touch.x - center.x
                        val dy = touch.y - center.y
                        
                        // Calculate angle in degrees (0 is 3 o'clock, positive = clockwise)
                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        
                        // Spray-paint draws counter-clockwise from top (-90)
                        // So finger moving clockwise should DECREASE progress visually
                        // But we want clockwise = forward in time, so:
                        // Top (12:00) = 0%, Right (3:00) = 25%, Bottom (6:00) = 50%, Left (9:00) = 75%
                        var adjustedAngle = angle + 90f  // Shift so top is 0
                        if (adjustedAngle < 0) adjustedAngle += 360f
                        
                        // Since spray draws counter-clockwise, we need to invert
                        // So going clockwise increases progress (forward in time)
                        val newProgress = (1f - (adjustedAngle / 360f)).coerceIn(0f, 1f)
                        
                        dragProgress = newProgress
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Get current theme for conditional rendering
        val theme = LocalBombestTheme.current
        val useSprayPaint = theme.progressStyle == ProgressStyle.SPRAY_PAINT
        
        if (useSprayPaint) {
            // Spray-paint progress ring for Graffiti theme
            SprayPaintProgress(
                progress = progress,
                modifier = Modifier,
                size = imageSize,
                strokeWidth = 10.dp,
                colors = listOf(
                    Color(0xFFFF6B35),  // Orange
                    Color(0xFFE90060),  // Magenta
                    Color(0xFF8B5CF6)   // Purple
                )
            )
        } else {
            // Standard progress arc for default theme
        // Outer gradient ring + progress arc
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 10.dp.toPx()
            val radius = size.minDimension / 2f

            // Background track
            drawArc(
                color = Color(0xFF3F3F57),
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                size = Size(radius * 2, radius * 2),
                topLeft = Offset(
                    (size.width - radius * 2) / 2f,
                    (size.height - radius * 2) / 2f
                )
            )

            // Gradient progress arc
            val gradient = Brush.sweepGradient(
                listOf(
                    Color(0xFFFFB86C),
                    Color(0xFFFF6B81),
                    Color(0xFFC34CFF),
                    Color(0xFF5F5FFF)
                )
            )
            // Sweep gradient renders 0-360. We need to rotate or mask it to match our arc.
            // Using a simpler approach: rotate the canvas or just accept standard sweep.
            // For now, let's just use the brush.
            
            drawArc(
                brush = gradient,
                startAngle = 140f,
                sweepAngle = 260f * progress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                size = Size(radius * 2, radius * 2),
                topLeft = Offset(
                    (size.width - radius * 2) / 2f,
                    (size.height - radius * 2) / 2f
                )
            )

            // Knob at end of progress
            val angleRad = Math.toRadians((140f + 260f * progress).toDouble())
            
            val knobX = center.x + (radius) * kotlin.math.cos(angleRad).toFloat()
            val knobY = center.y + (radius) * kotlin.math.sin(angleRad).toFloat()
            // If we pass size(radius*2, radius*2), and radius = minDimension/2, then it fills the box.
            // The stroke is centered on the edge.
            
            // Let's stick to the user's logic which was:
            // val knobRadius = radius - strokeWidth / 2
            // That puts it "inside" the stroke?
            // Actually, if radius = size/2, then the arc stroke extends outside by width/2.
            // Standard Compose drawArc with Size(w, h) draws strictly inside? No.
            // Whatever, let's use the provided logic but verify visually.
            
            drawCircle(
                color = Color.White,
                radius = 11.dp.toPx(),
                center = Offset(knobX, knobY)
            )
            drawCircle(
                color = Color(0xFFFF6B81),
                radius = 7.dp.toPx(),
                center = Offset(knobX, knobY),
                style = Stroke(width = 4.dp.toPx())
            )
            }
        }

        // Artwork image - use default graffiti bomb if no artwork or load fails
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(artworkUri ?: R.drawable.default_album_art)
                .error(R.drawable.default_album_art)
                .placeholder(R.drawable.default_album_art)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(imageSize - 24.dp)
                .clip(CircleShape)
                .background(Color(0xFF0B0E23)) // Graffiti theme bg
        )
    }
}

@Composable
fun TimeRow(start: String, end: String) {
    Row(
        modifier = Modifier.fillMaxWidth(0.75f),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = start, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
        Text(text = end, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
fun MidControlsRow(
    isFavorite: Boolean = false,
    isDownloaded: Boolean = false,
    onFavorite: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit = {},
    onShare: () -> Unit
) {
    // Animation states
    var favoritePressed by remember { mutableStateOf(false) }
    var downloadPressed by remember { mutableStateOf(false) }
    var sharePressed by remember { mutableStateOf(false) }
    var downloadSuccess by remember { mutableStateOf(false) }
    
    // Dialog states
    var showDownloadConfirm by remember { mutableStateOf(false) }
    var showRemoveWarning by remember { mutableStateOf(false) }
    
    // Download confirmation dialog
    if (showDownloadConfirm) {
        AlertDialog(
            onDismissRequest = { showDownloadConfirm = false },
            title = { Text("Download Track?", color = Color.White) },
            text = { 
                Text(
                    "Save this track for offline listening. You can remove it later from the player menu.",
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadConfirm = false
                    downloadPressed = true
                    onDownload()
                }) {
                    Text("Download", color = Color(0xFFE90060))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadConfirm = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A2E)
        )
    }
    
    // Remove download warning dialog
    if (showRemoveWarning) {
        AlertDialog(
            onDismissRequest = { showRemoveWarning = false },
            title = { Text("Remove Download?", color = Color.White) },
            text = { 
                Text(
                    "This track will be removed from your device. You'll need to download it again to listen offline.",
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveWarning = false
                    onRemoveDownload()
                }) {
                    Text("Remove", color = Color(0xFFE90060))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveWarning = false }) {
                    Text("Keep", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A2E)
        )
    }
    
    // Scale animations
    val favoriteScale by animateFloatAsState(
        targetValue = if (favoritePressed) 1.3f else 1f,
        animationSpec = spring(dampingRatio = 0.3f, stiffness = 500f),
        label = "favoriteScale"
    )
    val downloadScale by animateFloatAsState(
        targetValue = if (downloadPressed) 1.3f else 1f,
        animationSpec = spring(dampingRatio = 0.3f, stiffness = 500f),
        label = "downloadScale"
    )
    val shareScale by animateFloatAsState(
        targetValue = if (sharePressed) 1.3f else 1f,
        animationSpec = spring(dampingRatio = 0.3f, stiffness = 500f),
        label = "shareScale"
    )
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Favorite button with bounce animation
        IconButtonCircle(onClick = {
            favoritePressed = true
            onFavorite()
        }) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) Color(0xFFFF6B35) else Color(0xFFF48FFF),
                modifier = Modifier.graphicsLayer { scaleX = favoriteScale; scaleY = favoriteScale }
            )
        }
        
        // Download button - shows check when downloaded, tapping again shows remove warning
        IconButtonCircle(onClick = {
            if (!isDownloaded) {
                showDownloadConfirm = true
            } else {
                showRemoveWarning = true
            }
        }) {
            Icon(
                imageVector = if (isDownloaded) Icons.Default.Check else Icons.Default.Download,
                contentDescription = if (isDownloaded) "Downloaded - tap to remove" else "Download",
                tint = if (isDownloaded) Color(0xFF4CAF50) else Color(0xFFF48FFF),
                modifier = Modifier.graphicsLayer { scaleX = downloadScale; scaleY = downloadScale }
            )
        }
        
        // Share button with bounce
        IconButtonCircle(onClick = {
            sharePressed = true
            onShare()
        }) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                tint = Color(0xFFF48FFF),
                modifier = Modifier.graphicsLayer { scaleX = shareScale; scaleY = shareScale }
            )
        }
    }
    
    // Reset animation states
    LaunchedEffect(favoritePressed) {
        if (favoritePressed) {
            kotlinx.coroutines.delay(150)
            favoritePressed = false
        }
    }
    LaunchedEffect(downloadPressed) {
        if (downloadPressed) {
            kotlinx.coroutines.delay(150)
            downloadPressed = false
        }
    }
    LaunchedEffect(sharePressed) {
        if (sharePressed) {
            kotlinx.coroutines.delay(150)
            sharePressed = false
        }
    }
    LaunchedEffect(downloadSuccess) {
        if (downloadSuccess) {
            kotlinx.coroutines.delay(1500)
            downloadSuccess = false
        }
    }
}

@Composable
fun SongInfo(title: String, artist: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = artist,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.6f),
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TransportControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    isShuffleEnabled: Boolean,
    repeatMode: Int
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Shuffle
        IconButton(onClick = onShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffleEnabled) Color(0xFFC27CFF) else Color.Gray
            )
        }

        // Previous
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = Color(0xFFC27CFF),
                modifier = Modifier.size(32.dp)
            )
        }

        // Play Wrapper
        PlayButton(isPlaying = isPlaying, onClick = onPlayPause)

        // Next
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = Color(0xFFC27CFF),
                modifier = Modifier.size(32.dp)
            )
        }

        // Repeat
        IconButton(onClick = onRepeat) {
            Icon(
                imageVector = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                contentDescription = "Repeat",
                tint = if (repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) Color(0xFFC27CFF) else Color.Gray
            )
        }
    }
}

@Composable
fun PlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .shadow(18.dp, CircleShape, clip = false)
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        Color(0xFFFFB86C),
                        Color(0xFFFF6B81),
                        Color(0xFFC34CFF),
                        Color(0xFF4F7BFF)
                    )
                ),
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF18192C), Color(0xFF050712)),
                    center = Offset.Unspecified,
                    radius = 260f
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            // Draw Play or Pause symbol
            Canvas(modifier = Modifier.size(20.dp)) {
                if (isPlaying) {
                    // Draw Pause
                    val barWidth = size.width * 0.3f
                    drawRect(color = Color.White, topLeft = Offset(0f, 0f), size = Size(barWidth, size.height))
                    drawRect(color = Color.White, topLeft = Offset(size.width - barWidth, 0f), size = Size(barWidth, size.height))
                } else {
                    // Draw Play
                    val path = Path().apply {
                        moveTo(size.width * 0.1f, size.height * 0.0f)
                        lineTo(size.width * 1.0f, size.height * 0.5f)
                        lineTo(size.width * 0.1f, size.height * 1.0f)
                        close()
                    }
                    drawPath(path, color = Color.White)
                }
            }
        }
    }
}

enum class VisualizerType {
    Graffiti,
    SingleWave
}

@Composable
fun WaveformVisualizer(
    modifier: Modifier = Modifier,
    amplitudes: List<Float>
) {
    if (amplitudes.isEmpty()) return

    // Create a snapshot of amplitudes to force recomposition on SnapshotStateList changes
    val currentAmplitudes by remember(amplitudes) {
        derivedStateOf { amplitudes.toList() }
    }

    // Get current theme style
    val theme = LocalBombestTheme.current
    val defaultType = if (theme.visualizerStyle == VisualizerThemeStyle.GRAFFITI) 
        VisualizerType.Graffiti else VisualizerType.SingleWave
    
    // State for toggling modes
    var visualizerType by remember { mutableStateOf(defaultType) }
    
    // Cycle modes on click
    val nextMode = {
        visualizerType = when (visualizerType) {
            VisualizerType.Graffiti -> VisualizerType.SingleWave
            VisualizerType.SingleWave -> VisualizerType.Graffiti
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                // We use pointerInput click to consume event and ensure it hits
                detectTapGestures { nextMode() }
            }
    ) {
        // Graffiti mode uses a separate Composable
        if (visualizerType == VisualizerType.Graffiti) {
            GraffitiVisualizer(
                amplitudes = currentAmplitudes,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Other modes use Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f
                val count = currentAmplitudes.size

            when (visualizerType) {
                VisualizerType.SingleWave -> {
                    // Single Line (Clean Oscilloscope)
                    val path = Path()
                    path.moveTo(0f, centerY)
                    val stepX = width / (count + 1)
                    val points = mutableListOf<Offset>()
                    points.add(Offset(0f, centerY))
                    
                    for (i in 0 until count) {
                         val x = (i + 1) * stepX
                         val amp = currentAmplitudes[i] * 4.0f // High boost for line
                         val yOffset = amp * (height / 2f) * 0.8f
                         // Alternate for "jagged" oscilloscope look or smooth?
                         // Let's do smooth wave
                         val y = centerY - yOffset
                         points.add(Offset(x, y))
                    }
                    points.add(Offset(width, centerY))
                    
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) {
                         val p0 = points[i-1]
                         val p1 = points[i]
                         val cx = (p0.x + p1.x) / 2
                         path.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    }
                    
                    // Stroke only
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                           colors = listOf(Color(0xFFFFB86C), Color(0xFFFF6B81), Color(0xFFC34CFF))
                        ),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                
                    // Mirror reflection (faint)
                     val pathMirror = Path()
                     pathMirror.moveTo(points[0].x, centerY + (centerY - points[0].y))
                     for (i in 1 until points.size) {
                         val p0 = points[i-1]
                         val p1 = points[i]
                         val mirrorY0 = centerY + (centerY - p0.y)
                         val mirrorY1 = centerY + (centerY - p1.y)
                         val cx = (p0.x + p1.x) / 2
                         pathMirror.cubicTo(cx, mirrorY0, cx, mirrorY1, p1.x, mirrorY1)
                     }
                     drawPath(
                        path = pathMirror,
                        color = Color.White.copy(alpha=0.1f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                
                VisualizerType.Graffiti -> {
                    // Handled by GraffitiVisualizer Composable above
                }
            }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

