package com.ethora.chat.core.config

/**
 * Account settings screen switches. Mirrors the `settings` block of the React Native
 * `ChatConfig` (web main has no equivalent screen yet).
 *
 * @param languages "Language" card: interface language (profile `appLanguage`) and the language
 *   messages are translated into (profile `chatLanguage`). Off by default.
 * @param changePassword "Change password" screen, sent to `PUT /v2/users/me/password`. Off by default.
 */
data class SettingsConfig(
    val languages: LanguagesSettingsConfig? = null,
    val changePassword: Boolean? = null
)

/**
 * @param enabled Shows the Language card.
 * @param appLanguages BCP-47 tags offered as the interface language. Defaults to the built-in list.
 * @param chatLanguages BCP-47 tags offered as the message translation language. Defaults to the built-in list.
 */
data class LanguagesSettingsConfig(
    val enabled: Boolean,
    val appLanguages: List<String>? = null,
    val chatLanguages: List<String>? = null
)
