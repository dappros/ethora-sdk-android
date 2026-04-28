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
    onResend: (() -> Unit)? = null,
    containerWidthPx: Float = 0f,
    containerHeightPx: Float = 0f,
    modifier: Modifier = Modifier
) {
    if (!visible || message.isDeleted == true) return

    // Resend is offered for any own message that hasn't been confirmed sent —
    // either still in the optimistic `pending` window OR explicitly flagged as
    // `sendFailed` (the auto-retry timer fired without a server echo, or the
    // initial XMPP send returned null). User-initiated retry is always
    // permitted regardless of `RetryConfig.autoRetry` — that flag only
    // controls SILENT background retries.
    val canResend = isUser &&
        (message.pending == true || message.sendFailed == true) &&
        onResend != null

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val useContainer = containerWidthPx > 0f && containerHeightPx > 0f
    val widthPx = if (useContainer) containerWidthPx else screenWidthPx
    val heightPx = if (useContainer) containerHeightPx else screenHeightPx

    val menuWidth = 240.dp
    val menuWidthPx = with(density) { menuWidth.toPx() }
    // Visible items: Copy is always shown; for own messages the menu also shows
    // either Retry (when canResend) OR Edit, plus Delete. Received messages
    // show Copy only. Each ContextMenuItem is ~40 dp; dividers between items
    // are 8 dp; the surrounding Column has 8 dp top + 8 dp bottom padding.
    val visibleItemCount = if (isUser) 3 else 1
    val itemHeightDp = 40
    val dividerHeightDp = 8
    val menuChromeDp = 16 // 8 dp top + 8 dp bottom padding
    val menuHeightDp = menuChromeDp +
        (itemHeightDp * visibleItemCount) +
        (dividerHeightDp * (visibleItemCount - 1).coerceAtLeast(0))
    val menuHeightPx = with(density) { menuHeightDp.dp.toPx() }
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

    // Anchor to the bubble: prefer ABOVE so the message itself stays visible,
    // fall back to BELOW only when there's not enough room above.
    val rawYAbove = boundsTop - gapPx - menuHeightPx
    val adjustedY = when {
        spaceAbove >= menuHeightPx -> rawYAbove.coerceAtLeast(gapPx)
        spaceBelow >= menuHeightPx -> boundsBottom + gapPx
        else -> {
            val fallbackY = if (spaceAbove >= spaceBelow) rawYAbove else boundsBottom + gapPx
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
                    if (canResend) {
                        Divider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        ContextMenuItem(
                            text = "Retry",
                            icon = Icons.Default.Refresh,
                            onClick = {
                                onResend?.invoke()
                                onDismiss()
                            }
                        )
                    }
                    if (!canResend) {
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
