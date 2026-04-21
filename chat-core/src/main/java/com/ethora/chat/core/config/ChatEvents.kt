package com.ethora.chat.core.config

import com.ethora.chat.core.models.User
import com.ethora.chat.core.store.ChatConnectionState

data class OutgoingSendInput(
    val roomJid: String,
    val messageType: MessageType,
    val text: String? = null,
    val parentMessageId: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val metadata: Map<String, Any>? = null
)

sealed class SendDecision {
    object Cancel : SendDecision()
    data class Proceed(val input: OutgoingSendInput) : SendDecision()
}

sealed class ChatEvent {
    data class MessageSent(
        val roomJid: String,
        val messageId: String,
        val messageType: MessageType,
        val user: User?,
        val text: String? = null,
        val metadata: Map<String, Any>? = null
    ) : ChatEvent()

    data class MessageFailed(
        val roomJid: String,
        val messageType: MessageType,
        val text: String? = null,
        val error: Throwable? = null,
        val metadata: Map<String, Any>? = null
    ) : ChatEvent()

    data class MessageEdited(
        val roomJid: String,
        val messageId: String,
        val newText: String
    ) : ChatEvent()

    data class MessageDeleted(
        val roomJid: String,
        val messageId: String
    ) : ChatEvent()

    data class ReactionSent(
        val roomJid: String,
        val messageId: String,
        val emoji: String
    ) : ChatEvent()

    data class MediaUploadResult(
        val roomJid: String,
        val fileName: String?,
        val mimeType: String?,
        val success: Boolean,
        val error: Throwable? = null
    ) : ChatEvent()

    data class ConnectionChanged(
        val state: ChatConnectionState
    ) : ChatEvent()
}
