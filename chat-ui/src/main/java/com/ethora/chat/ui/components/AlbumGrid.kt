package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ethora.chat.core.models.IndexedAttachment
import com.ethora.chat.core.models.MessageAttachment

/** Album shapes, as on web (helpers/albumLayout.ts). */
internal enum class AlbumKind { FULL, PAIR, HERO, QUAD }

/** [visible] tiles are drawn; [hidden] items fold into the "+N" overlay on the last one. */
internal data class AlbumLayout(val kind: AlbumKind, val visible: Int, val hidden: Int)

internal object AlbumLayouts {
    fun forCount(count: Int): AlbumLayout = when {
        count <= 1 -> AlbumLayout(AlbumKind.FULL, count.coerceIn(0, 1), 0)
        count == 2 -> AlbumLayout(AlbumKind.PAIR, 2, 0)
        count == 3 -> AlbumLayout(AlbumKind.HERO, 3, 0)
        count == 4 -> AlbumLayout(AlbumKind.QUAD, 4, 0)
        else -> AlbumLayout(AlbumKind.QUAD, 4, count - 4)
    }
}

private val GRID_WIDTH = 260.dp
private val GAP = 3.dp

/**
 * Album of the visual attachments (images and videos) of one message.
 * [onTileClick] receives the index of the tapped attachment in the message's list.
 */
@Composable
fun AlbumGrid(
    tiles: List<IndexedAttachment>,
    onTileClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val layout = AlbumLayouts.forCount(tiles.size)
    val shown = tiles.take(layout.visible)
    val overflow = layout.hidden

    @Composable
    fun tile(position: Int, tileModifier: Modifier) {
        val item = shown[position]
        val isLast = position == shown.lastIndex
        AlbumTile(
            attachment = item.attachment,
            overlayText = if (isLast && overflow > 0) "+$overflow" else null,
            onClick = { onTileClick(item.index) },
            modifier = tileModifier
        )
    }

    Column(
        modifier = modifier
            .width(GRID_WIDTH)
            .clip(RoundedCornerShape(12.dp))
    ) {
        when (layout.kind) {
            AlbumKind.FULL -> {
                if (shown.isNotEmpty()) tile(0, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            }
            AlbumKind.PAIR -> Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                tile(0, Modifier.weight(1f).fillMaxHeight())
                tile(1, Modifier.weight(1f).fillMaxHeight())
            }
            AlbumKind.HERO -> Row(Modifier.fillMaxWidth().height(220.dp), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                tile(0, Modifier.weight(2f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GAP)) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            AlbumKind.QUAD -> Column(Modifier.fillMaxWidth().height(260.dp), verticalArrangement = Arrangement.spacedBy(GAP)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    tile(0, Modifier.weight(1f).fillMaxHeight())
                    tile(1, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    tile(2, Modifier.weight(1f).fillMaxHeight())
                    tile(3, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(
    attachment: MessageAttachment,
    overlayText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewSource = if (attachment.isImage) {
        attachment.locationPreview?.takeIf { it.isNotBlank() } ?: attachment.location
    } else {
        attachment.locationPreview?.takeIf { it.isNotBlank() }
    }
    val imageUrl = rememberSecureFileUrl(previewSource)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val model = coilModelFor(imageUrl)
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = attachment.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (attachment.isVideo) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
        if (overlayText != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = overlayText,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp
                )
            }
        }
    }
}
