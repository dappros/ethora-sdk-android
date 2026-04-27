package com.ethora.chat.ui.components

import android.net.Uri
import android.graphics.Bitmap
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ethora.chat.core.store.PendingMediaSendQueue
import com.ethora.chat.core.util.FileSizeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Chat input component with media support
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ChatInput(
    onSendMessage: (String, String?) -> Unit,
    onSendMedia: ((File, String) -> Unit)? = null,
    onStartTyping: (() -> Unit)? = null,
    onStopTyping: (() -> Unit)? = null,
    editText: String? = null,
    onEditCancel: (() -> Unit)? = null,
    replyingToMessage: com.ethora.chat.core.models.Message? = null,
    onReplyCancel: (() -> Unit)? = null,
    canSendMessage: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var text by remember { mutableStateOf(editText ?: "") }
    var selectedFile by remember { mutableStateOf<Pair<File, String>?>(null) }
    var isFocused by remember { mutableStateOf(false) }
    var idleTypingJob by remember { mutableStateOf<Job?>(null) }
    // Tracks whether we've already sent a "composing" for the current focus session
    // so we don't spam the server on every keystroke. Matches web: one composing on
    // focus, one paused on blur / idle timeout.
    var composingSent by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }

    fun sendStop() {
        if (composingSent) {
            composingSent = false
            onStopTyping?.invoke()
        }
    }
    fun sendStart() {
        if (!composingSent && editText == null) {
            composingSent = true
            onStartTyping?.invoke()
        }
    }
    fun resetIdleTimer() {
        idleTypingJob?.cancel()
        if (!composingSent) return
        idleTypingJob = coroutineScope.launch {
            // Auto-pause after 3s of no typing; keeps XMPP chatter bounded.
            delay(3_000)
            sendStop()
        }
    }

    // Update text when editText changes
    LaunchedEffect(editText) {
        if (editText != null) {
            text = editText
        }
    }

    // Stop typing when the composable leaves composition (navigation, screen close).
    DisposableEffect(Unit) {
        onDispose {
            idleTypingJob?.cancel()
            if (composingSent) onStopTyping?.invoke()
        }
    }
    
    fun setSelectedFromUri(uri: Uri, fallbackMime: String = "application/octet-stream") {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: fallbackMime
            val displayName = getDisplayName(context, uri)
                ?: "upload_${System.currentTimeMillis()}${extensionForMime(mimeType)}"
            val safeName = PendingMediaSendQueue.sanitizeFileName(displayName)
            val pendingDir = PendingMediaSendQueue.pendingUploadsDir(context).apply { mkdirs() }
            val file = uniqueFile(pendingDir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Unable to open selected file")
            val maxSizeBytes = 50 * 1024 * 1024L // 50MB
            val fileSize = file.length()
            if (fileSize > maxSizeBytes) {
                android.util.Log.w("ChatInput", "File too large: ${fileSize} bytes (max: $maxSizeBytes)")
                file.delete()
                Toast.makeText(context, "File too large (max 50MB)", Toast.LENGTH_SHORT).show()
                return
            }
            selectedFile = Pair(file, mimeType)
        } catch (e: Exception) {
            android.util.Log.e("ChatInput", "Error reading selected media", e)
            Toast.makeText(context, "Failed to open selected file", Toast.LENGTH_SHORT).show()
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { setSelectedFromUri(it) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { setSelectedFromUri(it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            try {
                val pendingDir = PendingMediaSendQueue.pendingUploadsDir(context).apply { mkdirs() }
                val photoFile = uniqueFile(pendingDir, "camera_${System.currentTimeMillis()}.jpg")
                FileOutputStream(photoFile).use { stream ->
                    it.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                }
                selectedFile = Pair(photoFile, "image/jpeg")
            } catch (e: Exception) {
                android.util.Log.e("ChatInput", "Error saving camera photo", e)
                Toast.makeText(context, "Failed to capture photo", Toast.LENGTH_SHORT).show()
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
                    // Opaque surface so the preview does not blend with chat messages
                    // behind the input area. Matches web: file preview stacks above the
                    // text field inside the input container, never overlaying chat.
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
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
                                text = FileSizeFormatter.format(file.length()),
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
            
            // Reply preview
            replyingToMessage?.let { msg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Colored vertical bar
                        Surface(
                            modifier = Modifier
                                .width(4.dp)
                                .height(32.dp),
                            shape = RoundedCornerShape(2.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {}
                        
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = "Replying to ${msg.user.fullName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = msg.body,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        
                        IconButton(
                            onClick = { onReplyCancel?.invoke() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel reply",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            
            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Attach button (hidden in edit mode)
                if (onSendMedia != null && editText == null) {
                    IconButton(
                        onClick = { showAttachSheet = true }
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
                        // One "composing" per focus session; the 3s idle timer handles
                        // sending "paused" after the user stops typing — no per-keystroke
                        // XMPP traffic.
                        if (it.isNotBlank() && isFocused && editText == null) {
                            sendStart()
                            resetIdleTimer()
                        } else if (it.isBlank()) {
                            sendStop()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            isFocused = focusState.isFocused
                            if (focusState.isFocused && text.isNotBlank() && editText == null) {
                                sendStart()
                                resetIdleTimer()
                            } else if (!focusState.isFocused) {
                                idleTypingJob?.cancel()
                                sendStop()
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
                            // Always pause typing on send — bounds XMPP traffic and matches
                            // the user's actual state (they finished composing this message).
                            idleTypingJob?.cancel()
                            sendStop()
                            if (selectedFile != null && onSendMedia != null && editText == null) {
                                val (file, mimeType) = selectedFile!!
                                selectedFile = null
                                onSendMedia(file, mimeType)
                            } else if (text.isNotBlank()) {
                                val textToSend = text.trim()
                                text = ""
                                onSendMessage(textToSend, replyingToMessage?.id)
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .offset(y = (-3).dp),
                        containerColor = if (canSendMessage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 6.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (canSendMessage) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    // Disabled send button
                    IconButton(
                        onClick = { },
                        modifier = Modifier
                            .size(44.dp)
                            .offset(y = (-3).dp),
                        enabled = false
                    ) {
                        Surface(
                            modifier = Modifier.size(44.dp),
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
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAttachSheet && onSendMedia != null && editText == null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            // fillMaxWidth is the default for ModalBottomSheet; explicit modifier guarantees
            // edge-to-edge even when parent wraps in a constrained Surface.
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Attach media",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                ListItem(
                    headlineContent = { Text("Media") },
                    supportingContent = { Text("Photo or video from gallery") },
                    leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        mediaPickerLauncher.launch(arrayOf("image/*", "video/*"))
                    }
                )

                ListItem(
                    headlineContent = { Text("Camera") },
                    supportingContent = { Text("Take a photo") },
                    leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        cameraLauncher.launch(null)
                    }
                )

                ListItem(
                    headlineContent = { Text("File") },
                    supportingContent = { Text("PDF, docs and other files") },
                    leadingContent = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private fun getDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()
}

private fun extensionForMime(mimeType: String): String {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    return extension?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
}

private fun uniqueFile(directory: File, fileName: String): File {
    val dotIndex = fileName.lastIndexOf('.')
    val base = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    var candidate = File(directory, fileName)
    var index = 1
    while (candidate.exists()) {
        candidate = File(directory, "${base}_$index$extension")
        index++
    }
    return candidate
}
