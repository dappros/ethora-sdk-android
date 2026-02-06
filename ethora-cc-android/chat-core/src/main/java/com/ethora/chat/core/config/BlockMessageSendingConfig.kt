package com.ethora.chat.core.config

/**
 * Block message sending configuration
 */
data class BlockMessageSendingConfig(
    val enabled: Boolean,
    val timeout: Long = 300_000L, // 5 minutes in milliseconds
    val onTimeout: ((String) -> Unit)? = null
)
