package com.bombest.music.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bombest.music.haptics.HapticGrooveEngine
import com.bombest.music.haptics.HapticPatternLibrary
import com.bombest.music.haptics.HapticPreferences
import kotlinx.coroutines.launch

/**
 * "Feel the Beat" haptics settings card.
 * 
 * Provides a toggle to enable beat-synced haptics and
 * an intensity slider with Low/Medium/High options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticsSettingsCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Observe preferences
    val isEnabled by HapticPreferences.isEnabled(context).collectAsState(initial = false)
    val intensity by HapticPreferences.intensity(context).collectAsState(
        initial = HapticPatternLibrary.Intensity.MEDIUM
    )
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row with toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Vibration,
                        contentDescription = null,
                        tint = if (isEnabled) Color(0xFFE90060) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Feel the Beat",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Haptic feedback synced to music",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
                
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            HapticPreferences.setEnabled(context, enabled)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFE90060),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF2A2D3E)
                    )
                )
            }
            
            // Intensity selector (only visible when enabled)
            if (isEnabled) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Intensity",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HapticPatternLibrary.Intensity.entries.forEach { level ->
                            FilterChip(
                                selected = intensity == level,
                                onClick = {
                                    scope.launch {
                                        HapticPreferences.setIntensity(context, level)
                                    }
                                },
                                label = {
                                    Text(
                                        text = level.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontSize = 12.sp
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFE90060),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0xFF2A2D3E),
                                    labelColor = Color.Gray
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
