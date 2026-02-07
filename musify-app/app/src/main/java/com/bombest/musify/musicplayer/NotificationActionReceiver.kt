package com.bombest.musify.musicplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("NotificationActionReceiver", "Received action: ${intent.action}")
        
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = intent.action
        }
        
        when (intent.action) {
            PlaybackService.ACTION_DISMISS,
            PlaybackService.ACTION_STOP -> {
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            PlaybackService.ACTION_PREVIOUS,
            PlaybackService.ACTION_PLAY_PAUSE,
            PlaybackService.ACTION_NEXT,
            PlaybackService.ACTION_SHUFFLE,
            PlaybackService.ACTION_REPEAT,
            PlaybackService.ACTION_ADD -> {
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
