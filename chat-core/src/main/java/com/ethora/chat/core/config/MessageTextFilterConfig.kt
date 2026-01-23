package com.ethora.chat.core.config

/**
 * Message text filter configuration
 */
data class MessageTextFilterConfig(
    val enabled: Boolean,
    val filterFunction: (String) -> String
)
