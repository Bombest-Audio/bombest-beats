package com.bombest.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bombest.music.data.Track

@Composable
fun PlayerScreen(
    track: Track?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = track?.title ?: "No track selected")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { onPlayPause() }) {
                Text(if (isPlaying) "Pause" else "Play")
            }
        }
    }
}
