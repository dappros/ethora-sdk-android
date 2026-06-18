package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethora.chat.core.config.ChatColors

/**
 * Centered banner for admin / system broadcasts (`<data isSystemMessage="true">`).
 *
 * Port of web's `SystemMessage.tsx`: a centered pill with the config primary
 * colour as text on the secondary colour as background, instead of a normal
 * chat bubble. Invoked from [ChatRoomView] when a message's `isSystemMessage`
 * is `"true"`.
 */
@Composable
fun SystemMessageBanner(messageText: String, colors: ChatColors?) {
    // Web defaults: text #0052cd on background #e7edf9.
    val textColor = remember(colors?.primary) {
        parseHexColorOrNull(colors?.primary) ?: Color(0xFF0052CD)
    }
    val background = remember(colors?.secondary) {
        parseHexColorOrNull(colors?.secondary) ?: Color(0xFFE7EDF9)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = background
        ) {
            Text(
                text = messageText,
                color = textColor,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

private fun parseHexColorOrNull(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().let { if (it.startsWith("#")) it else "#$it" }
    return try {
        Color(android.graphics.Color.parseColor(normalized))
    } catch (_: IllegalArgumentException) {
        null
    }
}
