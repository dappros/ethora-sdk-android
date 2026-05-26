package com.ethora.chat.core.config

/**
 * Background chat configuration
 */
data class BackgroundChatConfig(
    val color: String? = null,
    val image: String? = null,
    /** Optional dark-mode override for [color]. When null, [color] is used in both modes. */
    val colorDark: String? = null,
    /** Optional dark-mode override for [image]. When null, [image] is used in both modes. */
    val imageDark: String? = null
)
