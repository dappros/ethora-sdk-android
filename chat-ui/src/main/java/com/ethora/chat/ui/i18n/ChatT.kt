package com.ethora.chat.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import com.ethora.chat.core.i18n.ChatI18n
import com.ethora.chat.core.i18n.resolveUiLocale
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.ReaderLanguageStore

/**
 * Looks up SDK captions by key, with `{name}` placeholders. Port of web `useT()`.
 *
 * ```
 * val t = rememberChatTranslator()
 * Text(t("action.send"))
 * Text(t("presence.onlineCount", mapOf("count" to 3)))
 * ```
 */
class ChatTranslator internal constructor(
    /** The locale the captions resolved to, or null for the English default. */
    val locale: String?,
    private val table: Map<String, String>
) {
    operator fun invoke(key: String, vars: Map<String, Any>? = null): String =
        ChatI18n.interpolate(table[key] ?: key, vars)
}

/**
 * The caption table for the current locale. Re-resolves when the host config (`i18n.locale`,
 * `i18n.strings`), the account's app language or the reader's chat language changes.
 */
@Composable
fun rememberChatTranslator(): ChatTranslator {
    val config by ChatStore.config.collectAsState()
    val uiLanguage by ReaderLanguageStore.uiLanguage.collectAsState()
    val chatLanguage by ReaderLanguageStore.chatLanguage.collectAsState()
    val locale = resolveUiLocale(config, uiLanguage, chatLanguage)
    val overrides = config?.i18n?.strings
    return remember(locale, overrides) {
        ChatTranslator(locale, ChatI18n.resolveStringTable(locale, overrides))
    }
}
