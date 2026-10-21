package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethora.chat.core.reactions.ReactionEmojis

/**
 * Quick reaction bar at the top of the long-press menu, plus a button that opens
 * the full picker. Each emoji is a shortcode on the wire (`joy`, `+1`, ...).
 * Mirrors web MessageInteractions (quick bar + ArrowButton).
 */
@Composable
internal fun QuickReactionBar(
    quickReactions: List<String>,
    mine: Set<String>,
    showPickerButton: Boolean,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        quickReactions.take(8).forEach { shortcode ->
            EmojiCell(
                shortcode = shortcode,
                selected = shortcode in mine,
                size = 36.dp,
                fontSize = 22.sp,
                onClick = { onReact(shortcode) }
            )
        }
        if (showPickerButton) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "More reactions") { onTogglePicker() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = "More reactions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(if (pickerOpen) 180f else 0f)
                )
            }
        }
    }
}

/**
 * Full emoji picker, grouped by category. A tap reports the shortcode.
 * Sized by the caller (the menu gives it a fixed height).
 */
@Composable
internal fun EmojiPickerGrid(
    mine: Set<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = modifier.fillMaxWidth().height(220.dp).padding(horizontal = 4.dp)
    ) {
        ReactionEmojis.PICKER_CATEGORIES.forEach { (category, shortcodes) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                )
            }
            items(shortcodes) { shortcode ->
                EmojiCell(
                    shortcode = shortcode,
                    selected = shortcode in mine,
                    size = 36.dp,
                    fontSize = 20.sp,
                    onClick = { onPick(shortcode) }
                )
            }
        }
    }
}

@Composable
private fun EmojiCell(
    shortcode: String,
    selected: Boolean,
    size: Dp,
    fontSize: TextUnit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
            )
            .clickable(onClickLabel = shortcode) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = ReactionEmojis.glyphFor(shortcode), fontSize = fontSize)
    }
}
