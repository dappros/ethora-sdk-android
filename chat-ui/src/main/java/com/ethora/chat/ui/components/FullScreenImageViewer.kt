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
import androidx.compose.material.icons.filled.Warning
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
import coil3.compose.AsyncImage
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
                // Coil 3's default FileFetcher only handles `java.io.File` / `Uri`
                // models reliably. Passing a raw "file:///..." String fails silently
                // on some devices, leaving the viewer black. Convert local paths to
                // a File so the fetcher picks the right loader. Http(s) URLs go
                // through as String unchanged.
                val model: Any = when {
                    url.isBlank() -> url
                    url.startsWith("file://") -> java.io.File(url.removePrefix("file://"))
                    url.startsWith("/") -> java.io.File(url)
                    else -> url
                }
                var loadFailed by remember(url) { mutableStateOf(false) }
                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = model,
                        contentDescription = fileName,
                        modifier = Modifier
                            .fillMaxSize()
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
                        contentScale = ContentScale.Fit,
                        onError = { err ->
                            loadFailed = true
                            Log.w(
                                "FullScreenImageViewer",
                                "Failed to load image: $url (${err.result.throwable.message})"
                            )
                        }
                    )
                    if (loadFailed || url.isBlank()) {
                        // Surface a visible error instead of a silent black screen.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.White.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Failed to load image",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
        
        // Top bar with close and download buttons. The Dialog renders edge-to-edge
        // (decorFitsSystemWindows = false), so we must explicitly pad the buttons
        // below the status bar / any device cutout — otherwise the round buttons
        // sit under the system UI and become untappable.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
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
                    // Keep the counter above the gesture/3-button navigation bar.
                    .navigationBarsPadding()
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
