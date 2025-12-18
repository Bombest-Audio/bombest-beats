package com.bombest.music.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MetadataEditDialog(
    initialTitle: String? = null,
    initialArtist: String? = null,
    initialAlbum: String? = null,
    isBatch: Boolean = false,
    onConfirm: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle ?: "") }
    var artist by remember { mutableStateOf(initialArtist ?: "") }
    var album by remember { mutableStateOf(initialAlbum ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1D2E),
        title = { 
            Text(
                if (isBatch) "Batch Edit Metadata" else "Edit Metadata",
                color = Color.White,
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isBatch) {
                    MetadataTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = "Title"
                    )
                }
                MetadataTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = "Artist",
                    placeholder = if (isBatch) "Leave blank to keep original" else null
                )
                MetadataTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = "Album",
                    placeholder = if (isBatch) "Leave blank to keep original" else null
                )
                
                if (isBatch) {
                    Text(
                        "Note: Blank fields will not be changed.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val metadata = mutableMapOf<String, String>()
                    if (!isBatch && title.isNotBlank()) metadata["title"] = title
                    if (artist.isNotBlank()) metadata["artist"] = artist
                    if (album.isNotBlank()) metadata["album"] = album
                    onConfirm(metadata)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE90060))
            ) {
                Text("Save Changes", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}

@Composable
private fun MetadataTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, fontSize = 12.sp) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFFE90060),
            unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
            cursorColor = Color(0xFFE90060),
            focusedLabelColor = Color(0xFFE90060),
            unfocusedLabelColor = Color.Gray,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        )
    )
}
