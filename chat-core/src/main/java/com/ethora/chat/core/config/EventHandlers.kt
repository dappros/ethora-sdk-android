package com.ethora.chat.core.config

import com.ethora.chat.core.models.User

/**
 * Message type
 */
enum class MessageType {
    TEXT,
    MEDIA
}

/**
 * Message sent event
 */
data class MessageSentEvent(
    val message: String,
    val roomJID: String,
    val user: User,
    val messageType: MessageType,
    val metadata: Map<String, Any>? = null
)

/**
 * Message failed event
 */
data class MessageFailedEvent(
    val message: String,
    val roomJID: String,
    val error: Throwable,
    val messageType: MessageType
)

/**
 * Message edited event
 */
data class MessageEditedEvent(
    val messageId: String,
    val newMessage: String,
    val roomJID: String,
    val user: User
)

/**
 * Chat event handlers
 */
data class ChatEventHandlers(
    val onMessageSent: (suspend (MessageSentEvent) -> Unit)? = null,
    val onMessageFailed: ((MessageFailedEvent) -> Unit)? = null,
    val onMessageEdited: ((MessageEditedEvent) -> Unit)? = null
)
