package com.ethora.chat.core.config

/**
 * Secondary send button configuration
 */
data class SecondarySendButtonConfig(
    val enabled: Boolean,
    val messageEdit: String,
    val label: String? = null,
    val buttonStyles: Map<String, Any>? = null,
    val hideInputSendButton: Boolean = false,
    val overwriteEnterClick: Boolean = false
)
