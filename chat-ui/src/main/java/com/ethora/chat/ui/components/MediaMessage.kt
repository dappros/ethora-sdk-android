package com.ethora.chat.ui.components

import androidx.compose.foundation.clickable
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
import coil3.compose.AsyncImage
import com.ethora.chat.core.models.Message

/**
 * Media message component - displays images, videos, audio, or files based on mimetype
 */
@Composable
fun MediaMessage(
    message: Message,
    onMediaClick: (Message) -> Unit,
    modifier: Modifier = Modifier
) {
    val mimeType = message.mimetype ?: ""
    val fileUrl = message.location
    val previewUrl = message.locationPreview
    val fileName = message.originalName ?: message.fileName ?: "MediaFile"
    
    when {
        mimeType.startsWith("image/") -> {
            ImageMessage(
                imageUrl = fileUrl ?: previewUrl ?: "",
                previewUrl = previewUrl,
                fileName = fileName,
                onClick = { onMediaClick(message) },
                modifier = modifier
            )
        }
        mimeType.startsWith("video/") -> {
            VideoMessage(
                videoUrl = fileUrl ?: "",
                fileName = fileName,
                onClick = { onMediaClick(message) },
                modifier = modifier
            )
        }
        mimeType.startsWith("audio/") || mimeType.contains("application/octet-stream") -> {
            AudioMessage(
                audioUrl = fileUrl ?: "",
                fileName = fileName,
                onClick = { onMediaClick(message) },
                modifier = modifier
            )
        }
        else -> {
            // Unsupported type (PDF, etc.) - show file download component
            FileMessage(
                fileUrl = fileUrl ?: "",
                fileName = fileName,
                mimeType = mimeType,
                size = message.size,
                previewUrl = previewUrl,
                onClick = { onMediaClick(message) },
                modifier = modifier
            )
        }
    }
}

/**
 * Image message component
 */
@Composable
private fun ImageMessage(
    imageUrl: String,
    previewUrl: String?,
    fileName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Blank string != null; fall through to imageUrl when preview is empty
    // (important for local-file optimistic messages where preview may be "").
    // Convert a local absolute path or file:// URI to a java.io.File model so
    // Coil's FileFetcher picks it up reliably — some builds don't resolve
    // bare "file://" strings as model.
    val rawUrl = previewUrl?.takeIf { it.isNotBlank() } ?: imageUrl.takeIf { it.isNotBlank() }
    val model: Any? = rawUrl?.let { url ->
        when {
            url.startsWith("file://") -> java.io.File(url.removePrefix("file://"))
            url.startsWith("/") -> java.io.File(url)
            else -> url
        }
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = fileName,
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 300.dp)
                    .heightIn(min = 120.dp, max = 400.dp),
                contentScale = ContentScale.Fit,
                onError = {
                    // Swallow — placeholder below keeps the bubble visible.
                }
            )
        } else {
            // Placeholder bubble so the message is never a zero-size blob
            // while we wait for upload / CDN URL to arrive.
            Box(
                modifier = Modifier
                    .widthIn(min = 160.dp)
                    .heightIn(min = 120.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = fileName,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Video message component
 */
@Composable
private fun VideoMessage(
    videoUrl: String,
    fileName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .heightIn(max = 200.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play video",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * Audio message component
 */
@Composable
private fun AudioMessage(
    audioUrl: String,
    fileName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1
            )
            AudioPlayerView(
                audioUrl = audioUrl,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * File message component (for unsupported types like PDF)
 */
@Composable
private fun FileMessage(
    fileUrl: String,
    fileName: String,
    mimeType: String,
    size: String?,
    previewUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Show preview image if available, otherwise show file icon
            if (previewUrl != null && previewUrl.isNotBlank()) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = fileName,
                    modifier = Modifier.size(60.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = when {
                        mimeType.contains("pdf") -> Icons.Default.PictureAsPdf
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = "File",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = formatFileName(fileName, 20),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (size != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatFileSize(size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Format file name (truncate if too long)
 */
private fun formatFileName(name: String, maxLength: Int): String {
    val dotIndex = name.lastIndexOf('.')
    val extension = if (dotIndex != -1) name.substring(dotIndex) else ""
    val baseName = if (dotIndex != -1) name.substring(0, dotIndex) else name
    
    return if (baseName.length + extension.length <= maxLength) {
        name
    } else {
        val shortenedBaseName = baseName.substring(0, maxLength - extension.length - 3)
        "$shortenedBaseName...$extension"
    }
}

/**
 * Format file size
 */
private fun formatFileSize(sizeInBytes: String): String {
    val size = sizeInBytes.toLongOrNull() ?: return "Unknown size"
    
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}
