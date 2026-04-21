package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Full-screen image viewer with zoom and download functionality
 */
@Composable
fun FullScreenImageViewer(
    imageUrl: String,
    fileName: String,
    imageUrls: List<String> = emptyList(),
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isDownloading by remember { mutableStateOf(false) }
    val gallery = remember(imageUrl, imageUrls) { if (imageUrls.isEmpty()) listOf(imageUrl) else imageUrls }
    val startIndex = initialIndex.coerceIn(0, (gallery.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val currentIndex by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex.coerceIn(0, (gallery.size - 1).coerceAtLeast(0))
        }
    }
    val currentUrl = gallery.getOrElse(currentIndex) { imageUrl }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Gallery with swipe
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(gallery, key = { index, url -> "$index:$url" }) { _, url ->
                AsyncImage(
                    model = url,
                    contentDescription = fileName,
                    modifier = Modifier
                        .fillParentMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                        .pointerInput(url) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset += pan
                            }
                        },
                    contentScale = ContentScale.Fit
                )
            }
        }
        
        // Top bar with close and download buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Download button
            IconButton(
                onClick = {
                    scope.launch {
                        isDownloading = true
                        try {
                            downloadImage(context, currentUrl, fileName)
                        } catch (e: Exception) {
                            Log.e("FullScreenImageViewer", "Error downloading image", e)
                        } finally {
                            isDownloading = false
                        }
                    }
                },
                enabled = !isDownloading,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        CircleShape
                    )
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (gallery.size > 1) {
            Text(
                text = "${currentIndex + 1}/${gallery.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

private suspend fun downloadImage(context: android.content.Context, imageUrl: String, fileName: String) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection()
            connection.connect()
            
            val inputStream: InputStream = connection.getInputStream()
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            
            FileOutputStream(file).use { output ->
                inputStream.copyTo(output)
            }
            
            // Notify media scanner
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(file)
            context.sendBroadcast(mediaScanIntent)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "Image saved to Downloads",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e("FullScreenImageViewer", "Error downloading image", e)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    "Failed to download image",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
