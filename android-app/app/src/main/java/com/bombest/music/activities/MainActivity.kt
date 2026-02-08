package com.bombest.music.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bombest.music.data.Track
import com.bombest.music.player.MusicPlayer
import com.bombest.music.player.PlaybackService
import com.bombest.music.ui.LibraryScreen
import com.bombest.music.ui.PlayerScreen
import com.bombest.music.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private lateinit var musicPlayer: MusicPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        musicPlayer = MusicPlayer(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MusicApp(musicPlayer)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        musicPlayer.release()
    }
}

@Composable
fun MusicApp(musicPlayer: MusicPlayer, viewModel: MainViewModel = viewModel()) {
    var currentTrack by remember { mutableStateOf<Track?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryScreen(
            viewModel = viewModel,
            onTrackClick = { track ->
                currentTrack = track
                musicPlayer.play(track.s3Url)
                isPlaying = true
                // Start background service to keep playback alive
                val intent = android.content.Intent(context, PlaybackService::class.java).apply {
                    putExtra(PlaybackService.EXTRA_URL, track.s3Url)
                }
                context.startService(intent)
            }
        )
        PlayerScreen(
            track = currentTrack,
            isPlaying = isPlaying,
            onPlayPause = {
                val player = musicPlayer.exoPlayer
                if (player.isPlaying) {
                    player.pause()
                    isPlaying = false
                } else {
                    player.play()
                    isPlaying = true
                }
            }
        )
    }
}