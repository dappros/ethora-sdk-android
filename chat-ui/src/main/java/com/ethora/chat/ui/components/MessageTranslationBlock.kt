package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ethora.chat.core.config.TranslateMode
import com.ethora.chat.core.i18n.resolveMessageTranslation
import com.ethora.chat.core.i18n.resolveReaderLocale
import com.ethora.chat.core.i18n.resolveTranslateMode
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.ReaderLanguageStore
import com.ethora.chat.ui.i18n.rememberChatTranslator

/**
 * Message body with the web translation rules (`Message.tsx`, `TranslatedMessageBody`,
 * `MessageTranslate.tsx`).
 *
 *  - Translation off, or nothing translated for this reader: [content] shows the body as sent.
 *  - Auto: a real translation replaces the body, with the original quoted above it.
 *  - Manual: the body stays as sent, with a "Translate" link on incoming messages. The link shows
 *    the translation, and "Show original" switches back.
 *
 * Only translations that arrived with the message are shown. The SDK never calls a translation
 * service.
 *
 * @param content Renders the given text, typically the formatted message text.
 */
@Composable
fun MessageTranslationBlock(
    message: Message,
    isUser: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable (text: String) -> Unit
) {
    val t = rememberChatTranslator()
    val config by ChatStore.config.collectAsState()
    val chatLanguage by ReaderLanguageStore.chatLanguage.collectAsState()
    val readerMode by ReaderLanguageStore.translateMode.collectAsState()
    var revealed by remember(message.id) { mutableStateOf(false) }

    val translates = config?.translates
    if (translates?.enabled != true) {
        content(message.body)
        return
    }

    val mode = resolveTranslateMode(translates, readerMode)
    val resolved = resolveMessageTranslation(message, resolveReaderLocale(config, chatLanguage))

    if (mode == TranslateMode.MANUAL) {
        // Own messages never get a Translate link (web: showsTranslateRow requires !isUser).
        if (isUser || !resolved.needsTranslation || !resolved.hasTranslation) {
            content(message.body)
            return
        }
        Column(modifier = modifier) {
            content(if (revealed) resolved.displayText else resolved.originalText)
            Text(
                text = if (revealed) t("action.showOriginal") else t("action.translate"),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accentColor,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { revealed = !revealed }
            )
        }
        return
    }

    // Auto
    if (!resolved.hasTranslation) {
        content(message.body)
        return
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = resolved.originalText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            )
        }
        content(resolved.displayText)
    }
}
