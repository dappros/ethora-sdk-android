package com.ethora.chat.core.config

/**
 * Chat color configuration
 */
data class ChatColors(
    val primary: String,
    val secondary: String,
    /** Optional dark-mode override for [primary]. When null, [primary] is used in both modes. */
    val primaryDark: String? = null,
    /** Optional dark-mode override for [secondary]. When null, [secondary] is used in both modes. */
    val secondaryDark: String? = null,
    /** Header (toolbar) background colour. When null, falls back to Material surface. */
    val headerColor: String? = null,
    val headerColorDark: String? = null,
    /** Input bar background colour. When null, falls back to Material surface. */
    val inputBarColor: String? = null,
    val inputBarColorDark: String? = null,
    /** Input text colour. When null, falls back to Material onSurface. */
    val inputTextColor: String? = null,
    val inputTextColorDark: String? = null
)
