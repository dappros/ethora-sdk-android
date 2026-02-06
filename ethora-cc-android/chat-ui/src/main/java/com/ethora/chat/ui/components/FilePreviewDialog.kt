package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ethora.chat.core.models.Message

/**
 * File preview dialog for viewing media files in full screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewDialog(
    message: Message?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message == null) return
    
    val mimeType = message.mimetype ?: ""
    val fileUrl = message.location ?: ""
    val previewUrl = message.locationPreview
    val fileName = message.originalName ?: message.fileName ?: "MediaFile"
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            when {
                mimeType.startsWith("image/") -> {
                    // Full-screen image viewer with zoom
                    FullScreenImageViewer(
                        imageUrl = fileUrl.ifEmpty { previewUrl ?: "" },
                        fileName = fileName,
                        onDismiss = onDismiss
                    )
                }
                mimeType.startsWith("video/") -> {
                    // Native video player with loading and error handling
                    var isLoading by remember { mutableStateOf(true) }
                    var hasError by remember { mutableStateOf(false) }
                    
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header
                        TopAppBar(
                            title = { Text("Video preview") },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close"
                                    )
                                }
                            }
                        )
                        
                        // Video player with loading indicator
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (fileUrl.isNotBlank()) {
                                VideoPlayerView(
                                    videoUrl = fileUrl,
                                    onLoadingChange = { isLoading = it },
                                    onError = { hasError = true },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                hasError = true
                            }
                            
                            // Loading indicator
                            if (isLoading && !hasError) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                            
                            // Error state
                            if (hasError) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = "Error",
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = "Failed to load video",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        TextButton(onClick = onDismiss) {
                                            Text("Close")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                mimeType.contains("pdf") || mimeType == "application/pdf" -> {
                    // PDF viewer with loading and error handling
                    var isLoading by remember { mutableStateOf(true) }
                    var hasError by remember { mutableStateOf(false) }
                    
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header
                        TopAppBar(
                            title = { Text("PDF preview") },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close"
                                    )
                                }
                            }
                        )
                        
                        // PDF viewer with loading indicator
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (fileUrl.isNotBlank()) {
                                PDFViewer(
                                    pdfUrl = fileUrl,
                                    onLoadingChange = { isLoading = it },
                                    onError = { hasError = true },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                hasError = true
                            }
                            
                            // Loading indicator
                            if (isLoading && !hasError) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                            
                            // Error state
                            if (hasError) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PictureAsPdf,
                                            contentDescription = "PDF Error",
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = "Failed to load PDF",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "Please check your internet connection and try again",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        TextButton(onClick = onDismiss) {
                                            Text("Close")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Unsupported file type
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header
                        TopAppBar(
                            title = { Text("File preview") },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close"
                                    )
                                }
                            }
                        )
                        
                        // Content
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = "File",
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Unable to open the uploaded document. The file format is not supported by the system. Please upload a file in a compatible format. You still can download this file.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
