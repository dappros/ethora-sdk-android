package com.ethora.chat.core.config

/**
 * Chat header additional element configuration
 * Mirrors web: { enabled: boolean, element: any }
 */
data class ChatHeaderAdditionalConfig(
    val enabled: Boolean,
    val element: (() -> Unit)? = null // For Android, this will be a composable lambda
)
