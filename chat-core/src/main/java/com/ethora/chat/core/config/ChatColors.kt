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
    val secondaryDark: String? = null
)
