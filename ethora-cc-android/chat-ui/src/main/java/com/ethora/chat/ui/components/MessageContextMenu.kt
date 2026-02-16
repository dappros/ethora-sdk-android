package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.ethora.chat.core.models.Message

/**
 * Message context menu component - shows on long press.
 * Copy for all messages; Edit and Delete only for own messages.
 * Reactions and Reply are not shown.
 * Uses overlay in same container (no Popup) so position matches message bounds.
 *
 * @param containerWidthPx  width of the container (messages area); if > 0, bounds are relative to container
 * @param containerHeightPx height of the container; if > 0, used for placement logic
 */
@Composable
fun MessageContextMenu(
    message: Message,
    isUser: Boolean,
    visible: Boolean,
    tapX: Float,
    tapY: Float,
    boundsLeft: Float,
    boundsTop: Float,
    boundsRight: Float,
    boundsBottom: Float,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    containerWidthPx: Float = 0f,
    containerHeightPx: Float = 0f,
    modifier: Modifier = Modifier
) {
    if (!visible || message.isDeleted == true) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val useContainer = containerWidthPx > 0f && containerHeightPx > 0f
    val widthPx = if (useContainer) containerWidthPx else screenWidthPx
    val heightPx = if (useContainer) containerHeightPx else screenHeightPx

    val menuWidth = 240.dp
    val menuWidthPx = with(density) { menuWidth.toPx() }
    val menuHeightPx = with(density) { 200.dp.toPx() }
    val menuHeightAbovePx = with(density) {
        if (isUser) 125.dp.toPx() else 48.dp.toPx()
    }
    val gapPx = with(density) { 8.dp.toPx() }

    val spaceBelow = heightPx - boundsBottom - gapPx
    val spaceAbove = boundsTop - gapPx
    val minX = gapPx
    val maxX = (widthPx - menuWidthPx - gapPx).coerceAtLeast(gapPx)
    // Align menu to message side: right edge under bubble for own messages, left for others
    val adjustedX = if (isUser) {
        (boundsRight - menuWidthPx - gapPx).coerceIn(minX, maxX)
    } else {
        (boundsLeft + gapPx).coerceIn(minX, maxX)
    }

    val rawYAbove = boundsTop - gapPx - menuHeightAbovePx
    val adjustedY = when {
        spaceBelow >= menuHeightPx -> boundsBottom + gapPx
        spaceAbove >= menuHeightPx -> rawYAbove.coerceAtLeast(gapPx)
        else -> {
            val fallbackY = if (spaceBelow >= spaceAbove) boundsBottom + gapPx else rawYAbove
            fallbackY.coerceIn(gapPx, (heightPx - menuHeightPx - gapPx).coerceAtLeast(gapPx))
        }
    }

    // Overlay in same container so offset is in the same coordinate system as bounds
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(adjustedX.roundToInt(), adjustedY.roundToInt()) }
                .align(Alignment.TopStart)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* consume clicks so overlay doesn't dismiss */ }
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
