package com.ethora.chat.core.config

/**
 * Light or dark UI, mirroring the web `colorScheme` key (`'light' | 'dark' | 'system'`).
 */
enum class ChatColorScheme {
    LIGHT,
    DARK,
    SYSTEM
}

/**
 * Resolves the configured scheme to a dark flag.
 *
 * Returns `true` for dark, `false` for light, and `null` when the OS setting
 * should decide. [ChatColorScheme] wins over the legacy [forceDarkTheme]
 * boolean when both are set. With neither set the result is `null`
 * (follow the system), which is the Android default and differs from web,
 * where an unset `colorScheme` renders light.
 */
fun resolveDarkTheme(colorScheme: ChatColorScheme?, forceDarkTheme: Boolean?): Boolean? = when (colorScheme) {
    ChatColorScheme.LIGHT -> false
    ChatColorScheme.DARK -> true
    ChatColorScheme.SYSTEM -> null
    null -> forceDarkTheme
}
