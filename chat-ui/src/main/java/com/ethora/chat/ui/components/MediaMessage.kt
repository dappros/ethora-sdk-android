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
import com.ethora.chat.core.util.FileSizeFormatter

/**
 * Media message component - displays images, videos, audio, or files based on mimetype
 */
@Composable
fun MediaMessage(
    message: Message,
    isUser: Boolean,
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
                isUser = isUser,
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

    var loadFailed by remember(model) { mutableStateOf(false) }
    val showFallback = model == null || loadFailed

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        if (!showFallback) {
            AsyncImage(
                model = model,
                contentDescription = fileName,
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 300.dp)
                    .heightIn(min = 120.dp, max = 400.dp),
                contentScale = ContentScale.Fit,
                onError = { loadFailed = true }
            )
        } else {
            MediaFallbackIcon(fileName = fileName)
        }
    }
}

@Composable
private fun MediaFallbackIcon(fileName: String) {
    Box(
        modifier = Modifier
            .widthIn(min = 160.dp)
            .heightIn(min = 120.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.InsertDriveFile,
            contentDescription = fileName,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
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
    isUser: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    var previewFailed by remember(previewUrl) { mutableStateOf(false) }
    val showIcon = previewUrl.isNullOrBlank() || previewFailed

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .widthIn(max = 300.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!showIcon) {
            AsyncImage(
                model = previewUrl,
                contentDescription = fileName,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                onError = { previewFailed = true }
            )
        } else {
            Icon(
                imageVector = when {
                    mimeType.contains("pdf") -> Icons.Default.PictureAsPdf
                    else -> Icons.Default.InsertDriveFile
                },
                contentDescription = "File",
                modifier = Modifier.size(48.dp),
                tint = contentColor.copy(alpha = 0.9f)
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
                color = contentColor,
                maxLines = 1
            )
            if (size != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = FileSizeFormatter.format(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.72f)
                )
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
