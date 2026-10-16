package com.ethora.chat.core.config

/**
 * Static UI captions (interface text such as "Search..." or "Type message").
 * Independent from [TranslationsConfig], which translates messages.
 *
 * @param locale BCP-47 tag from the host or device ("en", "fr-CA"). Captions resolve to its
 *   base language. Unset, the reader's chat language (or the account's app language) is used.
 * @param strings Overrides or extends any built-in caption by key, e.g. "action.send" to "Go".
 */
data class I18nConfig(
    val locale: String? = null,
    val strings: Map<String, String>? = null
)
