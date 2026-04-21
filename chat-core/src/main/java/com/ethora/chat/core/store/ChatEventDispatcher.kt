package com.ethora.chat.core.store

import com.ethora.chat.core.config.ChatEvent
import com.ethora.chat.core.config.MessageFailedEvent
import com.ethora.chat.core.config.MessageSentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ChatEventDispatcher {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun emit(event: ChatEvent) {
        val config = ChatStore.getConfig() ?: return

        config.onChatEvent?.invoke(event)

        val handlers = config.eventHandlers ?: return
        when (event) {
            is ChatEvent.MessageSent -> {
                val onMessageSent = handlers.onMessageSent ?: return
                val user = event.user ?: return
                scope.launch {
                    onMessageSent(
                        MessageSentEvent(
                            message = event.text ?: "",
                            roomJID = event.roomJid,
                            user = user,
                            messageType = event.messageType,
                            metadata = event.metadata
                        )
                    )
                }
            }
            is ChatEvent.MessageFailed -> {
                handlers.onMessageFailed?.invoke(
                    MessageFailedEvent(
                        message = event.text ?: "",
                        roomJID = event.roomJid,
                        error = event.error ?: IllegalStateException("Unknown send error"),
                        messageType = event.messageType
                    )
                )
            }
            is ChatEvent.MessageEdited -> {
                val user = UserStore.currentUser.value ?: return
                handlers.onMessageEdited?.invoke(
                    com.ethora.chat.core.config.MessageEditedEvent(
                        messageId = event.messageId,
                        newMessage = event.newText,
                        roomJID = event.roomJid,
                        user = user
                    )
                )
            }
            else -> Unit
        }
    }
}
