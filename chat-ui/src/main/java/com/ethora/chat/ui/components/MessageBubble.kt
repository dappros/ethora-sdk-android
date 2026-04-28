package com.ethora.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import coil3.compose.AsyncImage
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import com.ethora.chat.core.store.PendingMediaSendStatus
import java.util.regex.Pattern

/**
 * Message bubble component with user avatars and grouping
 */
@Composable
fun MessageBubble(
    message: Message,
    isUser: Boolean,
    parentMessage: Message? = null,
    showAvatar: Boolean = true,
    showUsername: Boolean = true,
    showTimestamp: Boolean = true,
    onMediaClick: ((Message) -> Unit)? = null,
    onLongPress: ((tapX: Float, tapY: Float, boundsLeft: Float, boundsTop: Float, boundsRight: Float, boundsBottom: Float) -> Unit)? = null,
    onFailedClick: ((tapX: Float, tapY: Float, boundsLeft: Float, boundsTop: Float, boundsRight: Float, boundsBottom: Float) -> Unit)? = null,
    onAvatarClick: ((User) -> Unit)? = null,
    pendingMediaStatus: PendingMediaSendStatus? = null,
    sendFailed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var bubbleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var surfaceCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .onGloballyPositioned { rowCoordinates = it }
            .pointerInput(message.id) {
                detectTapGestures(
                    onTap = { offset ->
                        if (sendFailed && onFailedClick != null) {
                            val rowCoords = rowCoordinates
                            val coordsForBounds = surfaceCoordinates ?: bubbleCoordinates ?: rowCoords
                            if (coordsForBounds != null) {
                                val tapRoot = (rowCoords ?: coordsForBounds).localToRoot(Offset(offset.x, offset.y))
                                val topLeft = coordsForBounds.localToRoot(Offset(0f, 0f))
                                val bottomRight = coordsForBounds.localToRoot(Offset(coordsForBounds.size.width.toFloat(), coordsForBounds.size.height.toFloat()))
                                onFailedClick.invoke(
                                    tapRoot.x, tapRoot.y,
                                    topLeft.x, topLeft.y, bottomRight.x, bottomRight.y
                                )
                            }
                        }
                    },
                    onLongPress = { offset ->
                        val rowCoords = rowCoordinates
                        val coordsForBounds = surfaceCoordinates ?: bubbleCoordinates ?: rowCoords
                        if (coordsForBounds != null) {
                            val tapRoot = (rowCoords ?: coordsForBounds).localToRoot(Offset(offset.x, offset.y))
                            val topLeft = coordsForBounds.localToRoot(Offset(0f, 0f))
                            val bottomRight = coordsForBounds.localToRoot(Offset(coordsForBounds.size.width.toFloat(), coordsForBounds.size.height.toFloat()))
                            onLongPress?.invoke(
                                tapRoot.x, tapRoot.y,
                                topLeft.x, topLeft.y, bottomRight.x, bottomRight.y
                            )
                        } else {
                            onLongPress?.invoke(offset.x, offset.y, offset.x, offset.y, offset.x, offset.y)
                        }
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
                .widthIn(max = 280.dp)
                .onGloballyPositioned { bubbleCoordinates = it },
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
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = if (isUser) 20.dp else (if (showAvatar) 4.dp else 20.dp),
                            bottomEnd = if (isUser) (if (showAvatar) 4.dp else 20.dp) else 20.dp
                        )
                    )
                    .onGloballyPositioned { surfaceCoordinates = it },
                // Deleted bubbles render in a neutral, dimmed gray regardless
                // of sender — keeping the full primary colour for a deleted
                // own-message read as a normal active bubble. Match Telegram /
                // WhatsApp style.
                color = if (message.isDeleted == true)
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = if (message.isDeleted == true) 0.dp else if (isUser) 3.dp else 1.dp,
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
                } else {
                    Column {
                        // Show quoted message if it exists
                        parentMessage?.let { parent ->
                            QuotedMessage(
                                message = parent,
                                isUser = isUser,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)
                            )
                        }
                        
                        if (message.isMediafile == "true") {
                    // Show media component (only if not deleted)
                    MediaMessage(
                        message = message,
                        isUser = isUser,
                        onMediaClick = { msg -> onMediaClick?.invoke(msg) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                } else {
                    // Show text message with formatting
                    FormattedMessageText(
                        text = message.body,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        textColor = if (isUser) 
                            MaterialTheme.colorScheme.onPrimary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4
                    )
                    val firstUrl = remember(message.body) { extractFirstUrl(message.body) }
                    if (!firstUrl.isNullOrBlank()) {
                        UrlPreviewCard(
                            url = firstUrl,
                            isUser = isUser,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
    
    // Reactions
    message.reaction?.let { reactions ->
        if (reactions.isNotEmpty()) {
            ReactionsRow(
                reactions = reactions,
                isUser = isUser,
                modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp)
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
                    if (isUser && (message.pending == true || pendingMediaStatus != null || sendFailed)) {
                        Text(
                            text = if (sendFailed) {
                                "⚠ Sending failed. Tap to retry or delete."
                            } else {
                                when (pendingMediaStatus) {
                                    PendingMediaSendStatus.FAILED_WAITING_RETRY -> "failed, will retry"
                                    PendingMediaSendStatus.PERMANENTLY_FAILED -> "failed"
                                    PendingMediaSendStatus.READY_TO_SEND -> "retrying..."
                                    PendingMediaSendStatus.UPLOADING -> "sending..."
                                    PendingMediaSendStatus.QUEUED -> "sending..."
                                    PendingMediaSendStatus.SENT,
                                    null -> "sending..."
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sendFailed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            }
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

/**
 * Formatted message text with Markdown and autolink support
 */
@Composable
private fun FormattedMessageText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: androidx.compose.ui.graphics.Color,
    lineHeight: androidx.compose.ui.unit.TextUnit
) {
    val uriHandler = LocalUriHandler.current
    val annotatedText = remember(text) {
        buildFormattedText(text, textColor)
    }

    ClickableText(
        text = annotatedText,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(
            lineHeight = lineHeight,
            color = textColor
        ),
        onClick = { offset ->
            annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()
                ?.let { annotation ->
                    runCatching { uriHandler.openUri(annotation.item) }
                }
        }
    )
}

/**
 * Data class for text formatting matches
 */
private data class TextMatch(val start: Int, val end: Int, val type: String)

/**
 * Build formatted text with Markdown and autolink
 */
private fun buildFormattedText(text: String, defaultColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    val normalizedText = text
        .lineSequence()
        .joinToString("\n") { line ->
            when {
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val cleaned = line.trimStart().drop(2)
                    "• $cleaned"
                }
                line.trimStart().startsWith("> ") -> {
                    val cleaned = line.trimStart().drop(2)
                    "❝ $cleaned"
                }
                else -> line
            }
        }

    return buildAnnotatedString {
        // URL pattern (http, https, www)
        val urlPattern = Pattern.compile(
            "(?i)\\b(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+|www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Markdown patterns
        val boldPattern = Pattern.compile("\\*\\*(.+?)\\*\\*")
        val italicPattern = Pattern.compile("\\*(.+?)\\*")
        val codePattern = Pattern.compile("`(.+?)`")
        
        val matches = mutableListOf<TextMatch>()
        
        // Find all URLs
        val urlMatcher = urlPattern.matcher(normalizedText)
        while (urlMatcher.find()) {
            matches.add(TextMatch(urlMatcher.start(), urlMatcher.end(), "url"))
        }
        
        // Find Markdown formatting (avoid overlapping with URLs)
        val boldMatcher = boldPattern.matcher(normalizedText)
        while (boldMatcher.find()) {
            val start = boldMatcher.start()
            val end = boldMatcher.end()
            // Check if this overlaps with a URL
            val overlapsUrl = matches.any { it.start < end && it.end > start }
            if (!overlapsUrl) {
                matches.add(TextMatch(start, end, "bold"))
            }
        }
        
        val italicMatcher = italicPattern.matcher(normalizedText)
        while (italicMatcher.find()) {
            val start = italicMatcher.start()
            val end = italicMatcher.end()
            val overlapsUrl = matches.any { it.start < end && it.end > start }
            val overlapsBold = matches.any { it.start < end && it.end > start && it.type == "bold" }
            if (!overlapsUrl && !overlapsBold) {
                matches.add(TextMatch(start, end, "italic"))
            }
        }
        
        val codeMatcher = codePattern.matcher(normalizedText)
        while (codeMatcher.find()) {
            val start = codeMatcher.start()
            val end = codeMatcher.end()
            val overlapsUrl = matches.any { it.start < end && it.end > start }
            if (!overlapsUrl) {
                matches.add(TextMatch(start, end, "code"))
            }
        }
        
        // Sort matches by start position
        matches.sortBy { it.start }
        
        // Build annotated string
        var currentIndex = 0
        for (match in matches) {
            val start = match.start
            val end = match.end
            val type = match.type
            // Add text before match
            if (start > currentIndex) {
                append(normalizedText.substring(currentIndex, start))
            }
            
            // Add formatted text
            when (type) {
                "url" -> {
                    val url = normalizedText.substring(start, end)
                    val fullUrl = if (url.startsWith("www.")) "https://$url" else url
                    withStyle(
                        style = SpanStyle(
                            color = defaultColor.copy(alpha = 0.8f),
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(url)
                    }
                    addStringAnnotation(
                        tag = "URL",
                        annotation = fullUrl,
                        start = length - (end - start),
                        end = length
                    )
                }
                "bold" -> {
                    val content = normalizedText.substring(start + 2, end - 2) // Remove ** markers
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(content)
                    }
                }
                "italic" -> {
                    val content = normalizedText.substring(start + 1, end - 1) // Remove * marker
                    withStyle(
                        style = SpanStyle(
                            fontStyle = FontStyle.Italic
                        )
                    ) {
                        append(content)
                    }
                }
                "code" -> {
                    val content = normalizedText.substring(start + 1, end - 1) // Remove ` markers
                    withStyle(
                        style = SpanStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            background = defaultColor.copy(alpha = 0.2f)
                        )
                    ) {
                        append(content)
                    }
                }
            }
            
            currentIndex = end
        }
        
        // Add remaining text
        if (currentIndex < normalizedText.length) {
            append(normalizedText.substring(currentIndex))
        }
    }
}

private fun extractFirstUrl(text: String): String? {
    val urlPattern = Pattern.compile(
        "(?i)\\b(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+|www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
        Pattern.CASE_INSENSITIVE
    )
    val matcher = urlPattern.matcher(text)
    if (!matcher.find()) return null
    val raw = matcher.group()
    return if (raw.startsWith("www.")) "https://$raw" else raw
}

@Composable
private fun UrlPreviewCard(
    url: String,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val previews by UrlPreviewStore.previews.collectAsState()
    val uriHandler = LocalUriHandler.current
    val preview = previews[url]

    LaunchedEffect(url) {
        UrlPreviewStore.prefetch(url)
    }

    // Opaque gray-white surface regardless of own/received — 12% alpha over
    // the primary colour of an own bubble made the card read as "dimmed /
    // disabled". Keep the preview visually prominent so the URL is actually
    // readable against both purple (user) and light-gray (other) backgrounds.
    val previewBg = androidx.compose.ui.graphics.Color(0xFFF4F4F6) // soft gray-white
    val previewText = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.clickable {
            runCatching { uriHandler.openUri(url) }
        },
        shape = RoundedCornerShape(12.dp),
        color = previewBg
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val title = preview?.title?.takeIf { it.isNotBlank() } ?: url
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                color = previewText
            )
            val description = preview?.description?.takeIf { it.isNotBlank() }
            if (description != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    color = previewText.copy(alpha = 0.75f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = preview?.host ?: url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Quoted message for replies
 */
@Composable
private fun QuotedMessage(
    message: Message,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        color = if (isUser) 
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f) 
        else 
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored vertical bar
            Surface(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp),
                shape = RoundedCornerShape(1.5.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            ) {}
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = message.user.fullName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = if (message.isDeleted == true) "Message deleted" else message.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) 
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Row for message reactions
 */
@Composable
private fun ReactionsRow(
    reactions: Map<String, com.ethora.chat.core.models.ReactionMessage>,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    // Group reactions by emoji
    val counts = mutableMapOf<String, Int>()
    reactions.values.forEach { reactionMsg ->
        reactionMsg.emoji.forEach { emoji ->
            counts[emoji] = (counts[emoji] ?: 0) + 1
        }
    }
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        counts.forEach { (emoji, count) ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, 
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = emoji, fontSize = 12.sp)
                    if (count > 1) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(date: java.util.Date): String {
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(date)
}
