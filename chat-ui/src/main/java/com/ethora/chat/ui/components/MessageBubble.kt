package com.ethora.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import coil.compose.AsyncImage
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User

/**
 * Message bubble component with user avatars and grouping
 */
@Composable
fun MessageBubble(
    message: Message,
    isUser: Boolean,
    showAvatar: Boolean = true,
    showUsername: Boolean = true,
    showTimestamp: Boolean = true,
    onMediaClick: ((Message) -> Unit)? = null,
    onLongPress: ((Float, Float) -> Unit)? = null,
    onAvatarClick: ((User) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .pointerInput(message.id) {
                detectTapGestures(
                    onLongPress = { offset ->
                        // Convert offset to screen coordinates
                        val screenX = offset.x
                        val screenY = offset.y
                        onLongPress?.invoke(screenX, screenY)
                    }
                )
            },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // Avatar for received messages (left side) - only show for other users
        if (!isUser) {
            if (showAvatar) {
                UserAvatar(
                    user = message.user,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(enabled = onAvatarClick != null && message.user.name != "Deleted User") {
                            onAvatarClick?.invoke(message.user)
                        }
                )
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Spacer(modifier = Modifier.width(52.dp))
            }
        }
        
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Username - only show if showUsername is true and not from user
            if (!isUser && showUsername) {
                Text(
                    text = message.user.fullName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )
            }
            
            Surface(
                modifier = Modifier.clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else (if (showAvatar) 4.dp else 20.dp),
                        bottomEnd = if (isUser) (if (showAvatar) 4.dp else 20.dp) else 20.dp
                    )
                ),
                color = if (isUser) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = if (isUser) 3.dp else 1.dp,
                tonalElevation = 0.dp
            ) {
                // Check if message is deleted
                if (message.isDeleted == true) {
                    // Show deleted message UI (matches web: DeletedMessage component)
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Delete icon container
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Deleted",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Text(
                            text = "This message was deleted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontStyle = FontStyle.Italic
                        )
                    }
                } else if (message.isMediafile == "true") {
                    // Show media component (only if not deleted)
                    MediaMessage(
                        message = message,
                        onMediaClick = { msg -> onMediaClick?.invoke(msg) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                } else {
                    // Show text message
                    Text(
                        text = message.body,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) 
                            MaterialTheme.colorScheme.onPrimary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.25
                    )
                }
            }
            
            // Timestamp - only show if showTimestamp is true
            if (showTimestamp) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Show "sending..." if pending and it's user's message
                    if (isUser && message.pending == true) {
                        Text(
                            text = "sending...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = formatTime(message.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
        
        // No avatar for sent messages (right side) - user's own messages don't show avatar
        if (isUser) {
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

/**
 * User avatar component
 */
@Composable
fun UserAvatar(
    user: com.ethora.chat.core.models.User,
    modifier: Modifier = Modifier
) {
    val avatarUrl = user.profileImage
    val initials = remember(user.id, user.firstName, user.lastName, user.name, user.username) {
        val firstName = user.firstName
        val lastName = user.lastName
        val name = user.name
        val username = user.username
        
        when {
            !firstName.isNullOrBlank() -> firstName.first().uppercase()
            !lastName.isNullOrBlank() -> lastName.first().uppercase()
            !name.isNullOrBlank() -> name.first().uppercase()
            !username.isNullOrBlank() -> username.first().uppercase()
            else -> user.id.firstOrNull()?.uppercase() ?: "?"
        }
    }
    
    var imageLoadFailed by remember(avatarUrl) { mutableStateOf(false) }
    
    val hasValidAvatarUrl = avatarUrl != null && 
            avatarUrl.isNotBlank() && 
            avatarUrl != "none" && 
            avatarUrl.isNotEmpty()
    
    val shouldShowImage = hasValidAvatarUrl && !imageLoadFailed
    val shouldShowPlaceholder = !shouldShowImage
    
    android.util.Log.d("UserAvatar", "Rendering avatar for ${user.fullName}, profileImage: ${avatarUrl?.take(50)}, shouldShowImage: $shouldShowImage, shouldShowPlaceholder: $shouldShowPlaceholder, initials: $initials")
    
    Surface(
        modifier = modifier.clip(CircleShape),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 1.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Show placeholder text when no image or image failed
            if (shouldShowPlaceholder) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            // Show image if available and not failed
            if (shouldShowImage) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = user.fullName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = {
                        android.util.Log.w("UserAvatar", "Failed to load image: $avatarUrl, showing placeholder")
                        imageLoadFailed = true
                    },
                    onSuccess = {
                        android.util.Log.d("UserAvatar", "Successfully loaded image: $avatarUrl")
                        imageLoadFailed = false
                    }
                )
            }
        }
    }
}

private fun formatTime(date: java.util.Date): String {
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(date)
}
