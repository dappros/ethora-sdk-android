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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlin.math.roundToInt
import androidx.compose.ui.window.PopupProperties
import com.ethora.chat.core.models.Message

/**
 * Message context menu component - shows on long press.
 * Copy for all messages; Edit and Delete only for own messages.
 * Reactions and Reply are not shown.
 */
@Composable
fun MessageContextMenu(
    message: Message,
    isUser: Boolean,
    visible: Boolean,
    x: Float,
    y: Float,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible || message.isDeleted == true) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val menuWidth = 240.dp
    val menuWidthPx = with(density) { menuWidth.toPx() }
    val menuHeightPx = with(density) { 200.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }

    val adjustedX = x.coerceIn(gapPx, (screenWidthPx - menuWidthPx - gapPx).coerceAtLeast(gapPx))
    val adjustedY = if (y + menuHeightPx + gapPx <= screenHeightPx) {
        y + gapPx
    } else if (y - menuHeightPx - gapPx >= gapPx) {
        y - menuHeightPx - gapPx
    } else {
        y.coerceIn(gapPx, (screenHeightPx - menuHeightPx - gapPx).coerceAtLeast(gapPx))
    }

    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(adjustedX.roundToInt(), adjustedY.roundToInt()),
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
            Column {
                // Copy (for all messages)
                ContextMenuItem(
                    text = "Copy",
                    icon = Icons.Default.ContentCopy,
                    onClick = {
                        onCopy()
                        onDismiss()
                    }
                )

                // Edit and Delete only for own messages
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
