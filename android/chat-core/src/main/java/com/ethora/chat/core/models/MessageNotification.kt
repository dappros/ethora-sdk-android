package com.ethora.chat.core.models

import java.util.Date
import java.util.UUID

/**
 * Message notification model
 */
data class MessageNotification(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val roomJID: String,
    val roomName: String,
    val senderName: String,
    val messagePreview: String,
    val timestamp: Date = Date()
)
