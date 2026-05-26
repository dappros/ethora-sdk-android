package com.ethora.chat.core.config

/**
 * Message bubble styling configuration
 */
data class MessageBubbleStyle(
    val backgroundMessageUser: String? = null,
    val backgroundMessage: String? = null,
    val colorUser: String? = null,
    val color: String? = null,
    val borderRadius: Float? = null,
    /** Optional dark-mode override for [backgroundMessageUser] (sent bubble). */
    val backgroundMessageUserDark: String? = null,
    /** Optional dark-mode override for [backgroundMessage] (received bubble). */
    val backgroundMessageDark: String? = null,
    /** Optional dark-mode override for [colorUser] (sent text). */
    val colorUserDark: String? = null,
    /** Optional dark-mode override for [color] (received text). */
    val colorDark: String? = null
)
