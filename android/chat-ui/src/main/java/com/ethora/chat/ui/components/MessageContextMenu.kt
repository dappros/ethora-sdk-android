package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethora.chat.core.models.Message

/**
 * Quick reaction emojis
 */
private val quickReactions = listOf(
    "😂", // joy
    "❤️", // heart
    "🔥", // fire
    "👍", // +1
    "😊", // smile
    "😱"  // scream
)

/**
 * Message context menu component - shows on long press
 */
@Composable
fun MessageContextMenu(
    message: Message,
    isUser: Boolean,
    isReply: Boolean,
    visible: Boolean,
    x: Float,
    y: Float,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReaction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible || message.isDeleted == true) return
    
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    
    // Convert pixel coordinates to dp
    val xDp = with(density) { x.toDp() }
    val yDp = with(density) { y.toDp() }
    
    // Calculate position to keep menu on screen
    val menuWidth = 240.dp
    val menuHeight = 350.dp
    
    val adjustedX = if ((xDp + menuWidth) > screenWidth) {
        (xDp - menuWidth).coerceAtLeast(8.dp)
    } else {
        xDp.coerceAtLeast(8.dp)
    }
    
    val adjustedY = if ((yDp + menuHeight) > screenHeight) {
        (screenHeight - menuHeight - 8.dp).coerceAtLeast(8.dp)
    } else {
        yDp.coerceAtLeast(8.dp)
    }
    
    // Overlay to dismiss menu
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss)
    ) {
        Popup(
            onDismissRequest = onDismiss,
            alignment = Alignment.TopStart,
            offset = androidx.compose.ui.unit.IntOffset(
                adjustedX.toIntPx(),
                adjustedY.toIntPx()
            ),
            properties = PopupProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Column(
                modifier = modifier
                    .width(menuWidth)
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(8.dp)
            ) {
                // Quick reactions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    quickReactions.forEach { emoji ->
                        Text(
                            text = emoji,
                            modifier = Modifier
                                .clickable { 
                                    onReaction(emoji)
                                    onDismiss()
                                }
                                .padding(4.dp),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }
                
                Divider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                
                // Menu items
                Column {
                    // Reply
                    if (!isReply) {
                        ContextMenuItem(
                            text = "Reply",
                            icon = Icons.Default.Reply,
                            onClick = {
                                onReply()
                                onDismiss()
                            }
                        )
                        Divider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                    
                    // Copy
                    ContextMenuItem(
                        text = "Copy",
                        icon = Icons.Default.ContentCopy,
                        onClick = {
                            onCopy()
                            onDismiss()
                        }
                    )
                    
                    // Edit (only for user's messages)
                    if (isUser) {
                        Divider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        ContextMenuItem(
                            text = "Edit",
                            icon = Icons.Default.Edit,
                            onClick = {
                                onEdit()
                                onDismiss()
                            }
                        )
                    }
                    
                    // Delete (only for user's messages)
                    if (isUser) {
                        Divider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        ContextMenuItem(
                            text = "Delete",
                            icon = Icons.Default.Delete,
                            onClick = {
                                onDelete()
                                onDismiss()
                            },
                            textColor = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/**
 * Context menu item
 */
@Composable
private fun ContextMenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = textColor
        )
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = textColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun androidx.compose.ui.unit.Dp.toIntPx(): Int {
    return this.value.toInt()
}
