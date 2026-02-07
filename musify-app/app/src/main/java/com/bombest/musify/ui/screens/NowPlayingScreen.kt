package com.bombest.musify.ui.screens

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import com.bombest.musify.R
import com.bombest.musify.domain.Streamable
import com.bombest.musify.ui.components.AsyncImageWithPlaceholder
import com.bombest.musify.ui.components.GraffitiCircularProgress
import com.bombest.musify.ui.components.GraffitiVisualizer
import com.bombest.musify.ui.dynamicTheme.dynamicbackgroundmodifier.DynamicBackgroundResource
import com.bombest.musify.ui.dynamicTheme.dynamicbackgroundmodifier.dynamicBackground
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.ContentScale
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.abs

// collecting the flow within the composable scopes the collector to the composable.
// This ensures that the collection of flow is stopped as soon this composable
// is removed from composition. Therefore, this composables use parameters of type
// Flow.
@Composable
fun NowPlayingScreen(
    streamable: Streamable,
    playbackProgressFlow: Flow<Float>,
    timeElapsedStringFlow: Flow<String>,
    playbackDurationRange: ClosedFloatingPointRange<Float>,
    isPlaybackPaused: Boolean,
    totalDurationOfCurrentTrackProvider: () -> String,
    onCloseButtonClicked: () -> Unit,
    onShuffleButtonClicked: () -> Unit,
    onSkipPreviousButtonClicked: () -> Unit,
    onPlayButtonClicked: () -> Unit,
    onPauseButtonClicked: () -> Unit,
    onSkipNextButtonClicked: () -> Unit,
    onRepeatButtonClicked: () -> Unit,
    onSliderValueChange: (Float) -> Unit,
    isShuffled: Boolean = false,
    repeatMode: com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode = com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.NONE,
    onScrubStart: (() -> Unit)? = null,
    onScrubProgress: ((Float) -> Unit)? = null,
    onScrubEnd: (() -> Unit)? = null,
    audioSessionId: Int? = null
) {
    var isImageLoadingPlaceholderVisible by remember { mutableStateOf(true) }
    var isFavorite by remember { mutableStateOf(false) }
    val dynamicBackgroundResource = remember {
        DynamicBackgroundResource.FromImageUrl(streamable.streamInfo.imageUrl)
    }
    
    // Graffiti theme colors: deep navy base, orange → magenta → violet gradient
    val deepNavy = Color(0xFF0B0E23)
    val accentOrange = Color(0xFFFF6B35)
    val accentMagenta = Color(0xFFE90060)
    val accentViolet = Color(0xFF8B5CF6)
    
    // Check for reduced motion (simplified - respect user preferences)
    val enableMotion = remember { true } // Can be tied to accessibility settings later
    
    // Detect foldable device state and cover screen
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightDp = configuration.screenHeightDp
    val screenWidthDp = configuration.screenWidthDp
    
    // Detect cover screen (external display when phone is closed)
    // Razr cover screen: ~800x600px (~267x200dp at 3x density) or similar small dimensions
    val isCoverScreen = remember(screenHeightDp, screenWidthDp) {
        // Cover screen is very small - typically less than 300dp in either dimension
        screenHeightDp < 300 || screenWidthDp < 300
    }
    
    // Detect if device is folded at 90° (main screen still visible but folded)
    // Typical Razr unfolded: ~876dp height, folded at 90°: ~438dp height
    val isFolded = remember(screenHeightDp, screenWidthDp, isCoverScreen) {
        if (isCoverScreen) false else {
            val aspectRatio = screenWidthDp.toFloat() / screenHeightDp.toFloat()
            val isPortrait = screenHeightDp > screenWidthDp
            
            if (isPortrait) {
                // In portrait mode:
                // - Unfolded Razr: height ~876dp, aspect ratio ~0.42 (21:9)
                // - Folded at 90°: height ~438dp, aspect ratio ~0.85 (more square)
                // More aggressive detection: height between 400-600dp with square-ish aspect ratio
                (screenHeightDp in 400..600 && aspectRatio > 0.65f) || 
                (screenHeightDp < 500 && aspectRatio > 0.6f)
            } else {
                // In landscape mode: folded state has reduced width
                screenWidthDp < 600 && aspectRatio < 1.5f
            }
        }
    }
    
    // Calculate hero region offset to avoid fold line
    // When folded at 90°, move hero region significantly up to keep it fully visible on upper segment
    val heroVerticalOffsetDp = remember(isFolded, screenHeightDp) {
        if (isFolded) {
            // More aggressive offset: move up by 30% of screen height
            // This ensures the entire circular album art stays well above the fold line
            -screenHeightDp * 0.30f // Negative offset moves it up
        } else {
            0f
        }
    }
    
    // Scale down hero region when folded to ensure it fits comfortably on one screen segment
    val heroScaleWhenFolded = remember(isFolded) {
        if (isFolded) 0.75f else 1f // Scale down to 75% when folded for better fit
    }
    
    // Animation for play/pause button scale
    val playPauseScale by animateFloatAsState(
        targetValue = if (isPlaybackPaused) 1f else 1.05f,
        animationSpec = spring(dampingRatio = 0.6f)
    )
    
    // Hero region scale for play/pause conductor effect
    var heroScale by remember { mutableFloatStateOf(1f) }
    var ringGlowPulse by remember { mutableFloatStateOf(0f) }
    
    // Animate hero scale on play/pause press
    LaunchedEffect(isPlaybackPaused) {
        if (!isPlaybackPaused && enableMotion) {
            // Play: subtle scale up
            heroScale = 1.02f
            ringGlowPulse = 1f
            kotlinx.coroutines.delay(200)
            heroScale = 1f
            // Decay glow pulse
            ringGlowPulse = 0f
        } else if (isPlaybackPaused && enableMotion) {
            // Pause: gentle settle
            heroScale = 0.98f
            kotlinx.coroutines.delay(300)
            heroScale = 1f
        }
    }
    
    val animatedHeroScale by animateFloatAsState(
        targetValue = heroScale,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
    )
    
    val animatedRingGlow by animateFloatAsState(
        targetValue = ringGlowPulse,
        animationSpec = tween(durationMillis = 300, easing = LinearEasing)
    )
    
    // Bomb micro-motion: slow scale pulse while playing
    val bombPulsePhase by animateFloatAsState(
        targetValue = if (!isPlaybackPaused && enableMotion) 1f else 0f,
        animationSpec = if (!isPlaybackPaused && enableMotion) {
            infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            spring(dampingRatio = 0.8f)
        }
    )
    val bombScale = if (enableMotion && !isPlaybackPaused) {
        1f + sin(bombPulsePhase * kotlin.math.PI.toFloat()) * 0.015f // ±1.5% pulse
    } else {
        1f
    }
    
    // Grain shimmer intensity (subtle noise overlay)
    val shimmerPhase by animateFloatAsState(
        targetValue = if (!isPlaybackPaused && enableMotion) 1f else 0f,
        animationSpec = if (!isPlaybackPaused && enableMotion) {
            infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        } else {
            spring(dampingRatio = 0.9f)
        }
    )
    val shimmerAlpha = if (enableMotion && !isPlaybackPaused) {
        0.05f + sin(shimmerPhase * kotlin.math.PI.toFloat() * 2f) * 0.03f // Very subtle shimmer
    } else {
        0f
    }
    
    // Fuse glow on energy spikes (simulated - will use real energy when available)
    val currentProgress by playbackProgressFlow.collectAsState(initial = 0f)
    val simulatedEnergy = remember(currentProgress) {
        // Simulate energy spikes at certain progress points
        val energyBase = 0.3f + (currentProgress / 100f) * 0.4f
        val energySpike = sin(currentProgress * 0.15f) * 0.3f
        (energyBase + energySpike).coerceIn(0.2f, 0.9f)
    }
    val fuseGlowAlpha = if (enableMotion && !isPlaybackPaused && simulatedEnergy > 0.6f) {
        (simulatedEnergy - 0.6f) * 0.5f // Glow when energy is high
    } else {
        0f
    }
    
    // Show cover screen layout if on external display, otherwise show full layout
    if (isCoverScreen) {
        CoverScreenLayout(
            streamable = streamable,
            isPlaybackPaused = isPlaybackPaused,
            playbackProgressFlow = playbackProgressFlow,
            onPlayButtonClicked = onPlayButtonClicked,
            onPauseButtonClicked = onPauseButtonClicked,
            onSkipNextButtonClicked = onSkipNextButtonClicked,
            onSkipPreviousButtonClicked = onSkipPreviousButtonClicked,
            onShuffleButtonClicked = onShuffleButtonClicked,
            onRepeatButtonClicked = onRepeatButtonClicked,
            isShuffled = isShuffled,
            repeatMode = repeatMode
        )
        return
    }
    
    // All built-in compose layouts don't use a surface to display the content.
    // This means, if there is a list of clickable tracks displayed behind
    // the layout, then it will be possible to click them even if they are
    // not visible. To prevent such a behavior, surround the NowPlayingScreen
    // content with a surface.
    Surface(
        color = deepNavy // Deep navy/asphalt black base
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dynamicBackground(dynamicBackgroundResource)
        ) {
            // Subtle gradient overlay for graffiti theme with vignette effect
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // Base gradient overlay
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    deepNavy.copy(alpha = 0.8f)
                                )
                            )
                        )
                        // Radial vignette to pull focus to center
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    deepNavy.copy(alpha = 0.4f)
                                ),
                                radius = size.maxDimension * 0.8f,
                                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.4f)
                            )
                        )
                    }
            )
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                // Top bar with better spacing
                Header(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 8.dp),
                    onCloseButtonClicked = onCloseButtonClicked,
                    onTrailingButtonClick = {}
                )
                
                // Adjust spacing when folded to accommodate hero region repositioning
                Spacer(
                    modifier = Modifier
                        .weight(if (isFolded) 0.05f else 0.05f)
                        .offset(y = heroVerticalOffsetDp.dp)
                )
                
                // Hero region: Circular progress bar with album art in center
                // Enhanced with glow effects, micro-motion, and conductor effects
                // Repositioned when folded to avoid fold line
                Box(
                    modifier = Modifier
                        .size(340.dp)
                        .scale(animatedHeroScale * heroScaleWhenFolded) // Conductor + fold adjustment
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center
                ) {
                    // Animated glow intensity based on playback state (with pulse on play)
                    val baseGlowAlpha = if (!isPlaybackPaused) 0.3f else 0.1f
                    val glowAlpha = baseGlowAlpha + (animatedRingGlow * 0.2f) // Pulse on play
                    
                    val animatedGlowAlpha by animateFloatAsState(
                        targetValue = glowAlpha,
                        animationSpec = spring(dampingRatio = 0.7f)
                    )
                    
                    // Outer glow layer - subtle mist effect (with pulse on play)
                    Box(
                        modifier = Modifier
                            .size(380.dp)
                            .drawBehind {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            accentMagenta.copy(alpha = animatedGlowAlpha * 0.5f),
                                            accentViolet.copy(alpha = animatedGlowAlpha * 0.3f),
                                            Color.Transparent
                                        ),
                                        radius = 190.dp.toPx()
                                    ),
                                    radius = 190.dp.toPx()
                                )
                            }
                    )
                    
                    // Inner glow layer - more defined when playing (decays on pause)
                    val innerGlowAlpha = if (!isPlaybackPaused) animatedGlowAlpha else {
                        // Decay on pause: gentle fade instead of abrupt stop
                        animatedGlowAlpha * 0.3f
                    }
                    if (innerGlowAlpha > 0.05f) {
                        Box(
                            modifier = Modifier
                                .size(360.dp)
                                .drawBehind {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                accentOrange.copy(alpha = innerGlowAlpha * 0.4f),
                                                accentMagenta.copy(alpha = innerGlowAlpha * 0.2f),
                                                Color.Transparent
                                            ),
                                            radius = 180.dp.toPx()
                                        ),
                                        radius = 180.dp.toPx()
                                    )
                                }
                        )
                    }
                    
                    // Album art with micro-motion: subtle scale pulse, grain shimmer, fuse glow
                    // Draw album art before progress ring so thumb appears on top
                    Box(
                        modifier = Modifier
                            .size(290.dp)
                            .scale(bombScale) // Slow pulse while playing
                            .drawBehind {
                                // Rotating soft shadow (wow detail: slow rotation)
                                val shadowRotation = if (enableMotion && !isPlaybackPaused) {
                                    (currentProgress / 100f) * 360f // Rotate with progress
                                } else 0f
                                
                                // Subtle shadow for depth (rotates slowly)
                                rotate(shadowRotation, androidx.compose.ui.geometry.Offset(145.dp.toPx(), 145.dp.toPx())) {
                                    drawCircle(
                                        color = Color.Black.copy(alpha = 0.3f),
                                        radius = 145.dp.toPx(),
                                        center = androidx.compose.ui.geometry.Offset(145.dp.toPx(), 145.dp.toPx())
                                    )
                                }
                                
                                // Fuse glow on energy spikes (top of bomb)
                                if (fuseGlowAlpha > 0f && enableMotion) {
                                    val fuseCenter = androidx.compose.ui.geometry.Offset(145.dp.toPx(), 20.dp.toPx())
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                accentOrange.copy(alpha = fuseGlowAlpha),
                                                Color.Transparent
                                            ),
                                            radius = 30.dp.toPx()
                                        ),
                                        radius = 30.dp.toPx(),
                                        center = fuseCenter
                                    )
                                }
                                
                                // Grain shimmer overlay (very subtle)
                                if (shimmerAlpha > 0f && enableMotion) {
                                    // Draw subtle noise pattern (simplified as radial gradient variation)
                                    for (i in 0..8) {
                                        val angle = (i * 45f) + (shimmerPhase * 360f)
                                        val angleRad = Math.toRadians(angle.toDouble())
                                        val distance = 120.dp.toPx() + sin(shimmerPhase * kotlin.math.PI.toFloat() * 4f + i) * 5.dp.toPx()
                                        val x = 145.dp.toPx() + cos(angleRad).toFloat() * distance
                                        val y = 145.dp.toPx() + sin(angleRad).toFloat() * distance
                                        drawCircle(
                                            color = Color.White.copy(alpha = shimmerAlpha * 0.3f),
                                            radius = 2.dp.toPx(),
                                            center = androidx.compose.ui.geometry.Offset(x, y)
                                        )
                                    }
                                }
                            }
                    ) {
                        AsyncImageWithPlaceholder(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            model = streamable.streamInfo.imageUrl,
                            contentDescription = null,
                            onImageLoadingFinished = { isImageLoadingPlaceholderVisible = false },
                            isLoadingPlaceholderVisible = isImageLoadingPlaceholderVisible,
                            onImageLoading = { isImageLoadingPlaceholderVisible = true }
                        )
                    }
                    
                    // Graffiti circular progress ring - drawn after album art so thumb appears on top
                    // Energy-reactive with musical jitter and magnetic scrub
                    // Now with turntable-style audio scrubbing
                    // Key based on track identity to reset progress when track changes
                    key(streamable.streamInfo.title + streamable.streamInfo.subtitle + streamable.streamInfo.imageUrl) {
                        GraffitiCircularProgress(
                            progress = currentProgress,
                            onSeek = onSliderValueChange,
                            modifier = Modifier.fillMaxSize(),
                            size = 340.dp,
                            strokeWidth = 16.dp,
                            energyTier = null, // Will simulate energy if not provided
                            isPlaying = !isPlaybackPaused,
                            onScrubStart = onScrubStart,
                            onScrubProgress = onScrubProgress,
                            onScrubEnd = onScrubEnd
                        )
                    }
                }
                
                Spacer(modifier = Modifier.size(40.dp))
                
                // Track metadata with improved typography hierarchy
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = streamable.streamInfo.title,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.h5.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = streamable.streamInfo.subtitle,
                        fontWeight = FontWeight.Normal,
                        style = MaterialTheme.typography.subtitle1.copy(
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
                        ),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
                
                Spacer(modifier = Modifier.size(16.dp))
                
                // Time text display (no progress bar on mobile - circular progress handles seeking)
                // Combined format: elapsed/total (e.g., "1:45/2:09")
                val timeElapsedString by timeElapsedStringFlow.collectAsState(initial = "00:00")
                Text(
                    text = "${timeElapsedString}/${totalDurationOfCurrentTrackProvider()}",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption.copy(
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
                    )
                )
                
                Spacer(modifier = Modifier.size(16.dp))
                
                // Graffiti visualizer between timer and controls
                // Always visible with fallback animation when audioSessionId is invalid
                GraffitiVisualizer(
                    audioSessionId = audioSessionId ?: 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    barCount = 48,
                    smoothingFactor = 0.5f
                )
                
                Spacer(modifier = Modifier.size(16.dp)) // Spacing before controls
                
                // Transport controls with Spotify-style spacing and integrated depth
                PlaybackControls(
                    modifier = Modifier.fillMaxWidth(),
                    isPlayIconVisible = isPlaybackPaused,
                    playPauseScale = playPauseScale,
                    onSkipPreviousButtonClicked = onSkipPreviousButtonClicked,
                    onPlayButtonClicked = {
                        // Conductor effect: trigger hero scale and ring glow
                        heroScale = 1.02f
                        ringGlowPulse = 1f
                        onPlayButtonClicked()
                    },
                    onPauseButtonClicked = {
                        // Conductor effect: gentle settle
                        heroScale = 0.98f
                        ringGlowPulse = 0f
                        onPauseButtonClicked()
                    },
                    onSkipNextButtonClicked = onSkipNextButtonClicked,
                    onRepeatButtonClicked = onRepeatButtonClicked,
                    onShuffleButtonClicked = onShuffleButtonClicked,
                    isShuffled = isShuffled,
                    repeatMode = repeatMode,
                    enableMotion = enableMotion
                )
                
                Spacer(modifier = Modifier.size(24.dp))
                
                // Secondary actions footer - integrated with reduced contrast
                Footer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    onAvailableDevicesButtonClicked = {},
                    onShareButtonClicked = {},
                    onFavoriteButtonClicked = { isFavorite = !isFavorite },
                    onAddToPlaylistButtonClicked = {},
                    isFavorite = isFavorite,
                    enableMotion = enableMotion
                )
                
                Spacer(modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun Header(
    modifier: Modifier = Modifier,
    onCloseButtonClicked: () -> Unit,
    onTrailingButtonClick: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val expandMoreIcon = painterResource(R.drawable.ic_expand_more_24)
        val moreHorizIcon = painterResource(id = R.drawable.ic_more_horiz_24)
        IconButton(modifier = Modifier.offset(x = (-16).dp), // accommodate for increased size of icon because of touch target sizing
            onClick = onCloseButtonClicked,
            content = { Icon(painter = expandMoreIcon, contentDescription = null) })
        Text(
            text = "Now playing",
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(modifier = Modifier.offset(x = (16).dp), // accommodate for increased size of icon because of touch target sizing
            onClick = onTrailingButtonClick,
            content = { Icon(painter = moreHorizIcon, contentDescription = null) })
    }
}

@Composable
private fun Footer(
    modifier: Modifier = Modifier,
    onShareButtonClicked: () -> Unit,
    onAvailableDevicesButtonClicked: () -> Unit,
    onFavoriteButtonClicked: () -> Unit,
    onAddToPlaylistButtonClicked: () -> Unit,
    isFavorite: Boolean = false,
    enableMotion: Boolean = true
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Favorite/Like button - reduced contrast when inactive
        IconButton(
            onClick = onFavoriteButtonClicked,
            modifier = Modifier
                .size(48.dp)
                .alpha(if (isFavorite) 1f else 0.5f)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (isFavorite) Color(0xFFE90060) else MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
        
        // Add to playlist button - reduced contrast for depth
        IconButton(
            onClick = onAddToPlaylistButtonClicked,
            modifier = Modifier
                .size(48.dp)
                .alpha(0.5f)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_add_circle_outline_24),
                contentDescription = "Add to playlist",
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
        
        // Available devices button - reduced contrast for depth
        val availableDevicesIcon = painterResource(id = R.drawable.ic_available_devices)
        IconButton(
            onClick = onAvailableDevicesButtonClicked,
            modifier = Modifier
                .size(48.dp)
                .alpha(0.5f)
        ) {
            Icon(
                painter = availableDevicesIcon,
                contentDescription = "Available devices",
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
        
        // Share button - reduced contrast for depth
        IconButton(
            onClick = onShareButtonClicked,
            modifier = Modifier
                .size(48.dp)
                .alpha(0.5f)
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = "Share",
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    modifier: Modifier = Modifier,
    isPlayIconVisible: Boolean,
    playPauseScale: Float = 1f,
    onSkipPreviousButtonClicked: () -> Unit,
    onShuffleButtonClicked: () -> Unit,
    onPlayButtonClicked: () -> Unit,
    onPauseButtonClicked: () -> Unit,
    onSkipNextButtonClicked: () -> Unit,
    onRepeatButtonClicked: () -> Unit,
    isShuffled: Boolean = false,
    repeatMode: com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode = com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.NONE,
    enableMotion: Boolean = true
) {
    // Graffiti theme accent colors
    val accentMagenta = Color(0xFFE90060)
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Shuffle button - Spotify-style: always visible with clear active state
        IconButton(
            onClick = onShuffleButtonClicked,
            modifier = Modifier
                .size(56.dp)
                .alpha(if (isShuffled) 1f else 0.7f) // More visible when inactive (Spotify style)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_round_shuffle_24),
                contentDescription = if (isShuffled) "Shuffle on" else "Shuffle off",
                tint = if (isShuffled) accentMagenta else MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp)
            )
        }
        
        // Previous button - integrated with depth via opacity
        IconButton(
            onClick = onSkipPreviousButtonClicked,
            modifier = Modifier
                .size(56.dp)
                .alpha(0.8f) // Slightly reduced for depth
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_previous_24),
                contentDescription = "Previous",
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.9f),
                modifier = Modifier.size(32.dp)
            )
        }
        
        // Play/Pause button - hero button with scale animation (conductor)
        IconButton(
            onClick = if (isPlayIconVisible) onPlayButtonClicked else onPauseButtonClicked,
            modifier = Modifier
                .size(88.dp)
                .scale(playPauseScale)
        ) {
            Icon(
                painter = if (isPlayIconVisible) painterResource(R.drawable.ic_play_circle_filled_24)
                else painterResource(R.drawable.ic_pause_circle_filled_24),
                contentDescription = if (isPlayIconVisible) "Play" else "Pause",
                tint = Color.White,
                modifier = Modifier.size(80.dp)
            )
        }
        
        // Next button - integrated with depth via opacity
        IconButton(
            onClick = onSkipNextButtonClicked,
            modifier = Modifier
                .size(56.dp)
                .alpha(0.8f) // Slightly reduced for depth
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next_24),
                contentDescription = "Next",
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.9f),
                modifier = Modifier.size(32.dp)
            )
        }
        
        // Repeat button - Spotify-style: always visible with clear active state
        IconButton(
            onClick = onRepeatButtonClicked,
            modifier = Modifier
                .size(56.dp)
                .alpha(if (repeatMode != com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.NONE) 1f else 0.7f)
        ) {
            // Show different icons based on repeat mode
            val (repeatIcon, repeatDescription) = when (repeatMode) {
                com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.NONE -> {
                    R.drawable.ic_round_repeat_24 to "Repeat off"
                }
                com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.ALL -> {
                    R.drawable.ic_round_repeat_24 to "Repeat all"
                }
                com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.ONE -> {
                    R.drawable.ic_round_repeat_one_24 to "Repeat one"
                }
            }
            
            Icon(
                painter = painterResource(repeatIcon),
                contentDescription = repeatDescription,
                tint = if (repeatMode != com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.NONE) 
                    accentMagenta 
                else 
                    MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun ProgressSliderWithTimeText(
    modifier: Modifier = Modifier,
    currentTimeElapsedStringFlow: Flow<String>,
    currentPlaybackProgressFlow: Flow<Float>,
    totalDurationOfTrack: String,
    playbackDurationRange: ClosedFloatingPointRange<Float>,
    onSliderValueChange: (Float) -> Unit
) {
    val currentProgress by currentPlaybackProgressFlow.collectAsState(initial = 0f)
    val timeElapsedString by currentTimeElapsedStringFlow.collectAsState(initial = "00:00")
    var sliderValue by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    
    // Update slider value from flow only when not dragging
    LaunchedEffect(currentProgress) {
        if (!isDragging) {
            sliderValue = currentProgress
        }
    }
    
    Column(modifier = modifier) {
        Slider(
            modifier = Modifier.fillMaxWidth(),
            value = sliderValue,
            valueRange = playbackDurationRange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Color.White
            ),
            onValueChange = { newValue ->
                isDragging = true
                sliderValue = newValue
            },
            onValueChangeFinished = {
                isDragging = false
                onSliderValueChange(sliderValue)
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = timeElapsedString, style = MaterialTheme.typography.caption
            )
            Text(
                text = totalDurationOfTrack, style = MaterialTheme.typography.caption
            )
        }
    }
}

/**
 * Cover screen layout for Razr external display
 * Shows compact album art and essential playback controls
 */
@Composable
private fun CoverScreenLayout(
    streamable: Streamable,
    isPlaybackPaused: Boolean,
    playbackProgressFlow: Flow<Float>,
    onPlayButtonClicked: () -> Unit,
    onPauseButtonClicked: () -> Unit,
    onSkipNextButtonClicked: () -> Unit,
    onSkipPreviousButtonClicked: () -> Unit,
    onShuffleButtonClicked: () -> Unit,
    onRepeatButtonClicked: () -> Unit,
    isShuffled: Boolean = false,
    repeatMode: com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode = com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.NONE
) {
    val deepNavy = Color(0xFF0B0E23)
    val accentMagenta = Color(0xFFE90060)
    val currentProgress by playbackProgressFlow.collectAsState(initial = 0f)
    
    Surface(
        color = deepNavy,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // Compact album art
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
            ) {
                AsyncImageWithPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    model = streamable.streamInfo.imageUrl,
                    contentDescription = "Album art",
                    onImageLoadingFinished = {},
                    isLoadingPlaceholderVisible = false,
                    onImageLoading = {}
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Track info
            Text(
                text = streamable.streamInfo.title,
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = streamable.streamInfo.subtitle,
                style = MaterialTheme.typography.caption,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Compact progress indicator
            LinearProgressIndicator(
                progress = currentProgress / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color(0xFFE90060), // Accent magenta
                backgroundColor = Color.White.copy(alpha = 0.2f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Playback controls (main transport)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous button
                IconButton(
                    onClick = onSkipPreviousButtonClicked,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous_24),
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // Play/Pause button (larger)
                IconButton(
                    onClick = if (isPlaybackPaused) onPlayButtonClicked else onPauseButtonClicked,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        painter = if (isPlaybackPaused) 
                            painterResource(R.drawable.ic_play_circle_filled_24)
                        else 
                            painterResource(R.drawable.ic_pause_circle_filled_24),
                        contentDescription = if (isPlaybackPaused) "Play" else "Pause",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
                
                // Next button
                IconButton(
                    onClick = onSkipNextButtonClicked,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next_24),
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Shuffle and Repeat controls (compact row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle button
                IconButton(
                    onClick = onShuffleButtonClicked,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_round_shuffle_24),
                        contentDescription = if (isShuffled) "Shuffle on" else "Shuffle off",
                        tint = if (isShuffled) accentMagenta else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                // Repeat button
                IconButton(
                    onClick = onRepeatButtonClicked,
                    modifier = Modifier.size(40.dp)
                ) {
                    val (repeatIcon, repeatDescription) = when (repeatMode) {
                        com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.NONE -> {
                            R.drawable.ic_round_repeat_24 to "Repeat off"
                        }
                        com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.ALL -> {
                            R.drawable.ic_round_repeat_24 to "Repeat all"
                        }
                        com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.ONE -> {
                            R.drawable.ic_round_repeat_one_24 to "Repeat one"
                        }
                    }
                    
                    Icon(
                        painter = painterResource(repeatIcon),
                        contentDescription = repeatDescription,
                        tint = if (repeatMode != com.bombest.musify.musicplayer.PlaybackQueueManager.RepeatMode.NONE) 
                            accentMagenta 
                        else 
                            Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
