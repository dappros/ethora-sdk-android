package com.ethora.chat.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chat input component with media support
 */
@Composable
fun ChatInput(
    onSendMessage: (String) -> Unit,
    onSendMedia: ((File, String) -> Unit)? = null,
    onStartTyping: (() -> Unit)? = null,
    onStopTyping: (() -> Unit)? = null,
    editText: String? = null,
    onEditCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var text by remember { mutableStateOf(editText ?: "") }
    var selectedFile by remember { mutableStateOf<Pair<File, String>?>(null) }
    var isFocused by remember { mutableStateOf(false) }
    var typingJob by remember { mutableStateOf<Job?>(null) }
    
    // Update text when editText changes
    LaunchedEffect(editText) {
        if (editText != null) {
            text = editText
        }
    }
    
    // Debounce typing indicator (matches web: setTimeout in useComposing)
    LaunchedEffect(text, isFocused) {
        typingJob?.cancel()
        if (text.isBlank() || !isFocused) {
            onStopTyping?.invoke()
        } else if (text.isNotBlank() && isFocused && editText == null) {
            // Only send typing indicator if not in edit mode
            onStartTyping?.invoke()
            // Debounce: send stop typing after 100ms of no typing (matches web)
            typingJob = coroutineScope.launch {
                delay(100)
                onStopTyping?.invoke()
            }
        }
    }
    
    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val file = File(context.cacheDir, "temp_${System.currentTimeMillis()}")
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                
                // Validate file size (max 50MB)
                val maxSizeBytes = 50 * 1024 * 1024L // 50MB
                val fileSize = file.length()
                
                if (fileSize > maxSizeBytes) {
                    // Show error - file too large
                    android.util.Log.w("ChatInput", "File too large: ${fileSize} bytes (max: $maxSizeBytes)")
                    // TODO: Show error toast/snackbar
                    file.delete() // Clean up
                    return@let
                }
                
                // Validate file type (optional - allow all for now)
                selectedFile = Pair(file, mimeType)
            } catch (e: Exception) {
                android.util.Log.e("ChatInput", "Error copying file", e)
                // TODO: Show error toast/snackbar
            }
        }
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            // File preview
            selectedFile?.let { (file, mimeType) ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        when {
                            mimeType.startsWith("image/") -> {
                                AsyncImage(
                                    model = file,
                                    contentDescription = "Image preview",
                                    modifier = Modifier.size(60.dp),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            mimeType.startsWith("video/") -> {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = "File",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                            Text(
                                text = formatFileSize(file.length()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        
                        IconButton(
                            onClick = { selectedFile = null }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Attach button (hidden in edit mode)
                if (onSendMedia != null && editText == null) {
                    IconButton(
                        onClick = { filePickerLauncher.launch("*/*") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach file",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                // Cancel button (only in edit mode)
                if (editText != null && onEditCancel != null) {
                    IconButton(
                        onClick = { onEditCancel() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                OutlinedTextField(
                    value = text,
                    onValueChange = { 
                        text = it
                        // Send typing indicator when user starts typing (matches web: onFocus)
                        if (it.isNotBlank() && isFocused && onStartTyping != null && editText == null) {
                            onStartTyping()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            isFocused = focusState.isFocused
                            if (focusState.isFocused && text.isNotBlank() && onStartTyping != null && editText == null) {
                                // Send start typing on focus (matches web: onFocus)
                                onStartTyping()
                            } else if (!focusState.isFocused && onStopTyping != null) {
                                // Send stop typing on blur (matches web: onBlur)
                                onStopTyping()
                            }
                        },
                    placeholder = { 
                        Text(
                            if (editText != null) "Edit message..." else "Type a message...",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        ) 
                    },
                    shape = RoundedCornerShape(24.dp),
                    singleLine = false,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                
                // Send button - visible when there's text or file (or in edit mode)
                if (text.isNotBlank() || selectedFile != null || editText != null) {
                    FloatingActionButton(
                        onClick = {
                            if (selectedFile != null && onSendMedia != null && editText == null) {
                                val (file, mimeType) = selectedFile!!
                                onSendMedia(file, mimeType)
                                selectedFile = null
                            } else if (text.isNotBlank()) {
                                onSendMessage(text.trim())
                                text = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .offset(y = (-3).dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 6.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    // Disabled send button
                    IconButton(
                        onClick = { },
                        modifier = Modifier
                            .size(48.dp)
                            .offset(y = (-3).dp),
                        enabled = false
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
