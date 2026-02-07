package com.bombest.musify.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.bombest.musify.R
import com.bombest.musify.data.download.DownloadManager
import com.bombest.musify.data.download.DownloadStatus

/**
 * A composable that displays the download status for a track with appropriate icon/indicator.
 * Shows different states: Not downloaded (download icon), Downloading (progress circle),
 * Downloaded (checkmark), Failed (error icon with retry).
 *
 * @param trackId The ID of the track to show download status for
 * @param downloadManager The DownloadManager instance to observe download state
 * @param onDownloadClick Callback when download icon is clicked (to start download)
 * @param onCancelClick Callback when cancel icon is clicked (during download)
 * @param onRetryClick Callback when retry icon is clicked (on failure)
 * @param modifier Modifier to be applied to the indicator
 * @param iconSize Size of the icon (default 24.dp)
 */
@Composable
fun DownloadStatusIndicator(
    trackId: String,
    downloadManager: DownloadManager,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp
) {
    val downloadQueue by downloadManager.downloadQueue.collectAsState()
    val isDownloaded = downloadManager.isDownloaded(trackId)
    
    val task = downloadQueue.find { it.track.id == trackId }
    val status = task?.status
    
    Box(
        modifier = modifier.size(iconSize),
        contentAlignment = Alignment.Center
    ) {
        when {
            // Downloaded state - show checkmark
            isDownloaded -> {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = Color(0xFF1DB954), // Spotify green
                    modifier = Modifier.size(iconSize)
                )
            }
            
            // Downloading state - show progress indicator
            status == DownloadStatus.DOWNLOADING -> {
                val progress = task?.progress ?: 0f
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colors.primary
                )
                // Cancel button overlay
                IconButton(
                    onClick = onCancelClick,
                    modifier = Modifier.size(iconSize)
                ) {
                    // Invisible button for cancel - progress indicator is visible
                }
            }
            
            // Queued state - show progress indicator (indeterminate)
            status == DownloadStatus.QUEUED -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colors.primary
                )
            }
            
            // Failed state - show error icon with retry
            status == DownloadStatus.FAILED -> {
                IconButton(
                    onClick = onRetryClick,
                    modifier = Modifier.size(iconSize)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Download failed - tap to retry",
                        tint = MaterialTheme.colors.error,
                        modifier = Modifier.size(iconSize * 0.8f)
                    )
                }
            }
            
            // Canceled or not downloaded - show download icon
            else -> {
                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.size(iconSize)
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(
                            id = R.drawable.ic_outline_download_for_offline_24
                        ),
                        contentDescription = "Download",
                        modifier = Modifier.size(iconSize * 0.8f)
                    )
                }
            }
        }
    }
}
