package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethora.chat.core.config.ChatColors

/**
 * "New messages" divider rendered between the last-read and first-unread
 * message in a room's list. Port of web's NewMessageLabel.tsx — a centered
 * pill with the config primary colour.
 *
 * Invoked from [com.ethora.chat.ui.components.ChatRoomView] when the current
 * item's id is `"delimiter-new"`. The delimiter Message itself is synthesised
 * by [com.ethora.chat.core.store.MessageStore.normalizeDelimiterPosition] and
 * never hits persistence.
 */
@Composable
fun NewMessageDivider(colors: ChatColors?) {
    val primary = remember(colors?.primary) {
        parseHexColor(colors?.primary)
    }
    val resolvedPrimary = primary ?: MaterialTheme.colorScheme.primary
    val pillBackground = resolvedPrimary.copy(alpha = 0.12f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(118.dp),
            color = pillBackground
        ) {
            Text(
                text = "New Messages",
                color = resolvedPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}

/**
 * Parse a hex colour string (`"#0052CD"`, `"0052CD"`, `"#AA0052CD"`) to a
 * Compose [Color]. Returns null on any parse error so callers can fall back
 * to the theme primary.
 */
private fun parseHexColor(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().let { if (it.startsWith("#")) it else "#$it" }
    return try {
        Color(android.graphics.Color.parseColor(normalized))
    } catch (_: IllegalArgumentException) {
        null
    }
}
