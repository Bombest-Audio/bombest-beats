package com.bombest.musify.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bombest.musify.R
import com.bombest.musify.ui.theme.graffitiOrange
import com.bombest.musify.ui.theme.graffitiPink
import com.bombest.musify.ui.theme.graffitiPurple
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animation states
    val infiniteTransition = rememberInfiniteTransition(label = "splash_animation")
    
    // Logo scale animation with gentle pulse effect
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    // Logo alpha animation for fade-in
    var logoAlpha by remember { mutableFloatStateOf(0f) }
    var textAlpha by remember { mutableFloatStateOf(0f) }
    
    val animatedLogoAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = logoAlpha,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "logo_alpha"
    )
    
    val animatedTextAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = textAlpha,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "text_alpha"
    )
    
    // Glow effect animation
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    LaunchedEffect(Unit) {
        // Fade in logo
        delay(100)
        logoAlpha = 1f
        
        // Staggered animation for text
        delay(400)
        textAlpha = 1f
        
        delay(2000)
        
        // Complete animation
        onAnimationComplete()
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        graffitiOrange,
                        graffitiPink,
                        graffitiPurple
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Logo with enhanced animations and visual polish
        Column(
            modifier = Modifier
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Glow effect behind logo
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .alpha(glowAlpha)
                    .blur(20.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.4f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
            
            // Logo container with shadow and glow
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .alpha(animatedLogoAlpha)
                    .scale(scale)
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(28.dp),
                        spotColor = Color.White.copy(alpha = 0.3f)
                    )
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                Color.White.copy(alpha = 0.9f),
                                Color.White.copy(alpha = 0.7f)
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                // App icon
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Bombest Beats Logo",
                    modifier = Modifier
                        .size(100.dp)
                        .padding(16.dp),
                    contentScale = ContentScale.Fit
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // App name with fade-in animation
            Text(
                text = "Bombest Beats",
                style = MaterialTheme.typography.h4,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .alpha(animatedTextAlpha)
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}
